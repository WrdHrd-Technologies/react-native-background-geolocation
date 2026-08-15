package com.marianhello.bgloc.react.headless;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;
import com.marianhello.bgloc.sync.NotificationHelper;

public class HeadlessService extends HeadlessJsTaskService {
    private static final String TAG = "HeadlessService";
    public static final String TASK_KEY = "com.marianhello.bgloc.react.headless.Task";
    private static final long TASK_TIMEOUT_MS = 60000;

    private static final int NOTIFICATION_ID = 9921;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationHelper.registerSyncChannel(this);

            NotificationHelper.NotificationFactory factory = new NotificationHelper.NotificationFactory(this);
            Notification notification = factory.getSyncNotification("Syncing Data", "Processing location updates...");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e instanceof ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Background execution restriction prevented foreground state transition. Continuing headless task execution in background pool.");
            } else {
                Log.e(TAG, "Failed to satisfy foreground contract in onCreate.", e);
            }
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
}