package com.marianhello.utils;

import android.content.Context;
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

    private static final AtomicLong sClockOffsetMs = new AtomicLong(0);
    private static final AtomicBoolean sIsInitialized = new AtomicBoolean(false);

    private RealTimeHelper() {
        throw new UnsupportedOperationException("Time-sync infrastructure configuration layers cannot be instantiated.");
    }

    @NonNull
    public static Date now() {
        if (!sIsInitialized.get() || sClockOffsetMs.get() == 0) {
            return new Date();
        }

        try {
            long verifiedTimeMs = SystemClock.elapsedRealtime() + sClockOffsetMs.get();
            return new Date(verifiedTimeMs);
        } catch (Exception e) {
            Log.w(TAG, "Monotonic computation failed. Falling back instantly to standard system time window.");
            return new Date();
        }
    }

    public static void initialize(@NonNull final Context context) {
        final Context appContext = context.getApplicationContext();

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "Initializing true-time framework bindings safely on the main thread...");

                    RealTime.builder(appContext)
                            .withTimeServer("https://google.com")
                            .withTimeServer("https://time.nist.gov")
                            .build(date -> {
                                if (date != null) {
                                    long currentDeviceUptimeMs = SystemClock.elapsedRealtime();
                                    long trueNetworkTimeMs = date.getTime();

                                    sClockOffsetMs.set(trueNetworkTimeMs - currentDeviceUptimeMs);
                                    sIsInitialized.set(true);

                                    Log.i(TAG, "TrueTime offset calibrated cleanly. Network Sync Verified: " + date);
                                }
                            });

                } catch (Exception e) {
                    Log.e(TAG, "Fatal barrier split: TrueTime synchronization framework initialization failed.", e);
                }
            }
        });
    }

    public static void shutdown() {

    }
}