package com.marianhello.bgloc.react.headless;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;

public class HeadlessService extends HeadlessJsTaskService {
    private static final String TAG = "HeadlessService";
    public static final String TASK_KEY = "com.marianhello.bgloc.react.headless.Task";
    private static final long TASK_TIMEOUT_MS = 60000; 

    @Override
    protected @Nullable HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        if (intent == null) {
            Log.w(TAG, "Headless task configuration aborted: Incoming intent data reference is null.");
            return null;
        }

        Bundle extras = intent.getExtras();
        if (extras != null) {
            try {
                WritableMap bridgePayload = Arguments.fromBundle(extras);
                if (bridgePayload == null) {
                    bridgePayload = Arguments.createMap();
                }

                bridgePayload.putDouble("native_execution_timestamp", System.currentTimeMillis());

                Log.d(TAG, "Dispatching unique tracking payload event across the native React Native bridge.");
                return new HeadlessJsTaskConfig(
                        TASK_KEY,
                        bridgePayload,
                        TASK_TIMEOUT_MS,
                        true 
                );
            } catch (Exception e) {
                Log.e(TAG, "Fatal conversion exception encountered during React Native bridge payload serialization.", e);
            }
        }
        return null;
    }
}