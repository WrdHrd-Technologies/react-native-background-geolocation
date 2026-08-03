package com.marianhello.bgloc.react.headless;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
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
            createNotificationChannel();
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Syncing Data")
                .setContentText("Processing location updates...")
                .setSmallIcon(android.R.drawable.ic_menu_mypursuits)
                .setPriority(NotificationCompat.PRIORITY_MIN) 
                .setOngoing(true)
                .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to satisfy foreground contract in onCreate", e);
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