package com.marianhello.utils;

import android.media.AudioManager;
import android.util.Log;
import com.marianhello.logging.LoggerManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public final class ToneGenerator {
    private static final String TAG = "ToneGenerator";

    public static final class Tone {
        public static final int BEEP = android.media.ToneGenerator.TONE_PROP_BEEP;
        public static final int BEEP_BEEP_BEEP = android.media.ToneGenerator.TONE_CDMA_CONFIRM;
        public static final int LONG_BEEP = android.media.ToneGenerator.TONE_CDMA_ABBR_ALERT;
        public static final int DOODLY_DOO = android.media.ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE;
        public static final int CHIRP_CHIRP_CHIRP = android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD;
        public static final int DIALTONE = android.media.ToneGenerator.TONE_SUP_RINGTONE;

        private Tone() {}
    }

    private final int mStreamType;
    private final int mVolume;
    private final ScheduledExecutorService mExecutor;
    private final org.slf4j.Logger logger;
    
    private final Object mLock = new Object();
    private android.media.ToneGenerator mToneGenerator;

    public ToneGenerator(int streamType, int volume) {
        this.mStreamType = (streamType != 0) ? streamType : AudioManager.STREAM_NOTIFICATION;
        this.mVolume = Math.min(Math.max(volume, 0), 100); // Clamp volume strictly within 0-100% bounds
        
        this.mExecutor = Executors.newSingleThreadScheduledExecutor();
        this.logger = LoggerManager.getLogger(ToneGenerator.class);
    }

    private android.media.ToneGenerator getOrCreateToneGenerator() {
        synchronized (mLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new android.media.ToneGenerator(mStreamType, mVolume);
                } catch (Exception e) {
                    Log.e(TAG, "Fatal boundary condition: Operating system rejected native audio descriptor allocation request.", e);
                }
            }
            return mToneGenerator;
        }
    }

    public void startTone(final int toneType, final int durationMs) {
        final int safeDuration = Math.max(durationMs, 50);

        try {
            mExecutor.execute(() -> {
                android.media.ToneGenerator generator = getOrCreateToneGenerator();
                if (generator == null) return;

                synchronized (mLock) {
                    try {
                        generator.stopTone();
                        generator.startTone(toneType, safeDuration);
                    } catch (Exception e) {
                        logger.debug("Exception encountered during active audio pipeline playback: {}", e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Task execution rejected: Tone generator pipeline is shutting down.");
        }
    }

    public void release() {
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        synchronized (mLock) {
            if (mToneGenerator != null) {
                try {
                    mToneGenerator.release(); 
                } catch (Exception e) {
                    Log.w(TAG, "Error finalizing native sound framework mapping layers.", e);
                }
                mToneGenerator = null;
            }
        }
    }
}