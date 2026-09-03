package com.marianhello.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import ir.programmerplus.realtime.RealTime;

public final class RealTimeHelper {
    private static final String TAG = "RealTimeHelper";
    private static final String PREFS_NAME = "bgloc_truetime_store";
    private static final String KEY_CLOCK_OFFSET = "calibrated_clock_offset_ms";
    private static final String KEY_LAST_CALIBRATED_WALL = "last_calibrated_wall_time_ms";

    private static final AtomicLong sClockOffsetMs = new AtomicLong(0);
    private static final AtomicBoolean sIsInitialized = new AtomicBoolean(false);
    private static SharedPreferences sPrefs;

    private RealTimeHelper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    @NonNull
    public static Date now() {
        if (sIsInitialized.get() && sClockOffsetMs.get() != 0) {
            long verifiedTimeMs = SystemClock.elapsedRealtime() + sClockOffsetMs.get();
            return new Date(verifiedTimeMs);
        }

        // Rollback clamp fallback: If not yet synced, clamp against last known good wall time
        long currentWallTime = System.currentTimeMillis();
        if (sPrefs != null) {
            long lastGoodWallTime = sPrefs.getLong(KEY_LAST_CALIBRATED_WALL, 0);
            if (currentWallTime < lastGoodWallTime) {
                Log.w(TAG, "Clock rollback detected before calibration. Clamping to last known valid timestamp.");
                return new Date(lastGoodWallTime);
            }
        }

        return new Date(currentWallTime);
    }

    public static long nowMillis() {
        return now().getTime();
    }

    public static synchronized void initialize(@NonNull final Context context) {
        final Context appContext = context.getApplicationContext();
        sPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        long savedOffset = sPrefs.getLong(KEY_CLOCK_OFFSET, 0);
        if (savedOffset != 0) {
            sClockOffsetMs.set(savedOffset);
            sIsInitialized.set(true);
            Log.i(TAG, "Restored cached TrueTime monotonic offset from disk: " + savedOffset);
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Log.i(TAG, "Calibrating RealTime with network servers...");

                RealTime.builder(appContext)
                        .withTimeServer("https://google.com")
                        .withTimeServer("https://time.nist.gov")
                        .build(date -> {
                            if (date != null) {
                                calibrateTime(date.getTime(), "RealTime");
                            }
                        });

            } catch (Exception e) {
                Log.e(TAG, "RealTime network calibration failed.", e);
            }
        });
    }

    /**
     * Unified calibration hook: can be called by RealTime or LocationSyncWorker HTTP Date headers.
     */
    public static void calibrateTime(long verifiedUtcTimeMs, String source) {
        if (verifiedUtcTimeMs <= 0) return;

        long currentDeviceUptimeMs = SystemClock.elapsedRealtime();
        long computedOffset = verifiedUtcTimeMs - currentDeviceUptimeMs;

        sClockOffsetMs.set(computedOffset);
        sIsInitialized.set(true);

        if (sPrefs != null) {
            sPrefs.edit()
                    .putLong(KEY_CLOCK_OFFSET, computedOffset)
                    .putLong(KEY_LAST_CALIBRATED_WALL, verifiedUtcTimeMs)
                    .apply();
        }

        Log.i(TAG, "TrueTime calibrated via [" + source + "]. Network UTC: " + new Date(verifiedUtcTimeMs));
    }

    public static void shutdown() {
        sIsInitialized.set(false);
    }
}