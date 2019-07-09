package com.chienpm.zecorder.ui.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import com.chienpm.zecorder.ui.utils.MyUtils;
import com.chienpm.zecorder.ui.utils.MyUtils.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class RecordingService extends Service {

    private final IBinder mIBinder = new RecordingUsingMuxerBinder();

    private static final String TAG = "chienpm";
    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private MediaMuxer mMuxer;
    private Surface mInputSurface;
    private MediaCodec mVideoCodec;
    private MediaCodec mAudioCodec;

    private boolean mMuxerStarted;
    private int mVideoTrackIndex = -1;
    private int mAudioTrackIndex = -1;

    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private MediaCodec.Callback mVideoCodecCallback;
    private Intent mScreenCaptureIntent;
    private int mScreenCaptureResultCode;
    private MediaCodec.Callback mAudioCodecCallback;


    public class RecordingUsingMuxerBinder extends Binder{
        public RecordingService getService(){
            return RecordingService.this;
        }
    }

    public RecordingService() {

    }

    @Override
    public void onCreate() {
        super.onCreate();
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(
                android.content.Context.MEDIA_PROJECTION_SERVICE);

        mVideoCodecCallback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                Log.d(TAG, "Input Buffer Avail");
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                ByteBuffer encodedData = mVideoCodec.getOutputBuffer(index);
                if (encodedData == null) {
                    throw new RuntimeException("couldn't fetch buffer at index " + index);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }

                if (info.size != 0) {
                    if (mMuxerStarted) {
                        encodedData.position(info.offset);          //update current video position
                        encodedData.limit(info.offset + info.size);
                        mMuxer.writeSampleData(mVideoTrackIndex, encodedData, info);
                    }
                }

                mVideoCodec.releaseOutputBuffer(index, false);

            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "VIDEO MediaCodec " + codec.getName() + " onError:", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.d(TAG, "VIDEO Output Format changed");
                if (mVideoTrackIndex >= 0) {
                    throw new RuntimeException("VIDEO format changed twice");
                }
                mVideoTrackIndex = mMuxer.addTrack(mVideoCodec.getOutputFormat());
                if (!mMuxerStarted && mVideoTrackIndex >= 0) {
                    mMuxer.start();
                    mMuxerStarted = true;
                }
            }
        };

        mAudioCodecCallback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                Log.d(TAG, "AUDIO Input Buffer Avail");
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                ByteBuffer encodedData = mAudioCodec.getOutputBuffer(index);
                if (encodedData == null) {
                    throw new RuntimeException("AUDIO couldn't fetch buffer at index " + index);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }

                if (info.size != 0) {
                    if (mMuxerStarted) {
                        encodedData.position(info.offset);          //update current video position
                        encodedData.limit(info.offset + info.size);
                        mMuxer.writeSampleData(mAudioTrackIndex, encodedData, info);
                    }
                }

                mAudioCodec.releaseOutputBuffer(index, false);

            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "Audio MediaCodec " + codec.getName() + " onError:", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.d(TAG, "AUDIO Output Format changed");
                if (mAudioTrackIndex >= 0) {
                    throw new RuntimeException("AUDIO format changed twice");
                }
                mAudioTrackIndex = mMuxer.addTrack(mAudioCodec.getOutputFormat());
                if (!mMuxerStarted && mAudioTrackIndex >= 0) {
                    mMuxer.start();
                    mMuxerStarted = true;
                }
            }
        };

    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "RecordingService: onBind()");
        mScreenCaptureIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        mScreenCaptureResultCode = mScreenCaptureIntent.getIntExtra(MyUtils.SCREEN_CAPTURE_INTENT_RESULT_CODE, MyUtils.RESULT_CODE_FAILED);
        Log.d(TAG, "onBind: "+ mScreenCaptureIntent);
        return mIBinder;
    }

    public void startRecording() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mScreenCaptureResultCode, mScreenCaptureIntent);
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay;
        if (dm != null) {
            defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        } else {
            throw new IllegalStateException("Cannot display manager?!?");
        }
        if (defaultDisplay == null) {
            throw new RuntimeException("No display found.");
        }

        // Get the display size and density.
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int screenDensity = metrics.densityDpi;

        prepareVideoEncoder(screenWidth, screenHeight);
        prepareAudioEncoder();

        try {
            File outputFile = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES) + "/Zecorder", "Screen-record-" +
                    Long.toHexString(System.currentTimeMillis()) + ".mp4");
            if (!outputFile.getParentFile().exists()) {
                outputFile.getParentFile().mkdirs();
            }
            mMuxer = new MediaMuxer(outputFile.getCanonicalPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        prepareVideoEncoder(screenWidth, screenHeight);
        prepareAudioEncoder();



        // Start the video input.
        mMediaProjection.createVirtualDisplay("Recording Display", screenWidth,
                screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR/* flags */, mInputSurface,
                null /* callback */, null /* handler */);
    }

    private void prepareVideoEncoder(int width, int height) {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);
        int frameRate = 30; // 30 fps

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 seconds between I-frames

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoCodec = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            mVideoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoCodec.createInputSurface();
            mVideoCodec.setCallback(mVideoCodecCallback);
            mVideoCodec.start();
        } catch (IOException e) {
            releaseEncoders();
        }
    }

    private void prepareAudioEncoder() {
        MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE,
                MediaUtils.AUDIO_SAMPLING_RATE, MediaUtils.AUDIO_CHANNEL);

        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_STEREO);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, MediaUtils.AUDIO_BITRATE);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, MediaUtils.AUDIO_CHANNEL);
        audioFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        try{
            mAudioCodec = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
            mAudioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            mAudioCodec.setCallback(mAudioCodecCallback);

            mAudioCodec.start();
        }
        catch (IOException e){
            releaseEncoders();
        }
    }

    public void stopRecording() {
        releaseEncoders();
    }


    private void releaseEncoders() {
        if (mMuxer != null) {
            if (mMuxerStarted) {
                mMuxer.stop();
            }
            mMuxer.release();
            mMuxer = null;
            mMuxerStarted = false;
        }
        if (mVideoCodec != null) {
            mVideoCodec.stop();
            mVideoCodec.release();
            mVideoCodec = null;
        }
        if(mAudioCodec != null){
            mAudioCodec.stop();
            mAudioCodec.release();
            mAudioCodec = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        mVideoTrackIndex = -1;
    }




}
