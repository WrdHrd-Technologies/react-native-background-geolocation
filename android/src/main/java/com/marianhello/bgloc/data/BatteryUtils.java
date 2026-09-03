package com.marianhello.bgloc.data;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

public final class BatteryUtils {
    private static final String TAG = "BatteryUtils";
    private static final long CACHE_TTL_MS = 120_000L; 

    private static final Object sLock = new Object();
    private static long sLastFetchElapsedRealtime = 0L;
    private static BatteryInfo sCachedInfo = new BatteryInfo(-1, false);

    private BatteryUtils() {
        throw new UnsupportedOperationException("Utility layer cannot be instantiated.");
    }

    @NonNull
    public static BatteryInfo getBatteryStatus(@NonNull Context context) {
        long now = SystemClock.elapsedRealtime();

        synchronized (sLock) {
            // Return cached battery info if within TTL and previously resolved
            if (sCachedInfo.getBatteryLevel() >= 0 && (now - sLastFetchElapsedRealtime < CACHE_TTL_MS)) {
                return sCachedInfo;
            }

            Context appContext = context.getApplicationContext();
            int level = -1;
            boolean isCharging = false;
            boolean hardwareQuerySuccess = false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    BatteryManager batteryManager = (BatteryManager) appContext.getSystemService(Context.BATTERY_SERVICE);
                    if (batteryManager != null) {
                        level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                        int status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);

                        if (level >= 0 && level <= 100 && status != 0) {
                            isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                    status == BatteryManager.BATTERY_STATUS_FULL);
                            hardwareQuerySuccess = true;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Hardware sensor query execution faltered. Escalating to sticky broadcast fallback.");
                }
            }

            if (!hardwareQuerySuccess) {
                try {
                    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                    Intent batteryStatus = appContext.registerReceiver(null, ifilter);

                    if (batteryStatus != null) {
                        int rawLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

                        if (rawLevel >= 0 && scale > 0) {
                            level = (int) Math.min(100, Math.max(0, (rawLevel / (double) scale) * 100));
                        }

                        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Fallback broadcast extraction failed.", ex);
                }
            }

            sCachedInfo = new BatteryInfo(level, isCharging);
            sLastFetchElapsedRealtime = now;
            return sCachedInfo;
        }
    }
}