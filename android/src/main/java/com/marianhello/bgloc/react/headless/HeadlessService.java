package com.marianhello.bgloc.react.headless;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;
import com.marianhello.bgloc.sync.NotificationHelper;

public class HeadlessService extends HeadlessJsTaskService {
    private static final String TAG = "HeadlessService";
    public static final String TASK_KEY = "com.marianhello.bgloc.react.headless.Task";
    private static final long TASK_TIMEOUT_MS = 60000;

    private static final String CHANNEL_ID = "bg_loc_headless_channel";
    private static final int NOTIFICATION_ID = 9921;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationHelper.registerSyncChannel(this);
            String targetChannelId = NotificationHelper.SYNC_CHANNEL_ID;

            int appIconResId = getApplicationContext().getApplicationInfo().icon;
            if (appIconResId == 0) {
                appIconResId = android.R.drawable.sym_def_app_icon;
            }

            Notification notification = new NotificationCompat.Builder(this, targetChannelId)
                    .setContentTitle("Syncing Data")
                    .setContentText("Processing location updates...")
                    .setSmallIcon(appIconResId)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true)
                    .setLocalOnly(true)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to satisfy foreground contract in onCreate, attempting local fallback channel setup.", e);
            handleEmergencyFallbackForegroundService();
        }
    }

    /**
     * Emergency isolated fallback loop if global NotificationHelper calls raise unexpected framework errors.
     */
    private void handleEmergencyFallbackForegroundService() {
        try {
            createNotificationChannel();
            int fallbackIcon = getApplicationContext().getApplicationInfo().icon;
            if (fallbackIcon == 0) fallbackIcon = android.R.drawable.sym_def_app_icon;

            Notification fallbackNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Syncing Data")
                    .setContentText("Processing updates...")
                    .setSmallIcon(fallbackIcon)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, fallbackNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, fallbackNotification);
            }
        } catch (Exception fatal) {
            Log.e(TAG, "Critical: Complete failure to claim foreground state capabilities.", fatal);
        }
    }

    @Override
    protected @Nullable HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        if (intent == null) return null;

        Bundle extras = intent.getExtras();
        if (extras != null) {
            try {
                WritableMap bridgePayload = Arguments.fromBundle(extras);
                if (bridgePayload == null) bridgePayload = Arguments.createMap();

                bridgePayload.putDouble("native_execution_timestamp", System.currentTimeMillis());
                return new HeadlessJsTaskConfig(TASK_KEY, bridgePayload, TASK_TIMEOUT_MS, true);
            } catch (Exception e) {
                Log.e(TAG, "Payload serialization exception", e);
            }
        }
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Headless Sync Channel",
                    NotificationManager.IMPORTANCE_MIN
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }
}