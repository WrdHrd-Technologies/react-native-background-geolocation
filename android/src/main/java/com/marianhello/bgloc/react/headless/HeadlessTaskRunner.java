package com.marianhello.bgloc.react.headless;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.marianhello.bgloc.headless.Task;
import com.marianhello.bgloc.headless.AbstractTaskRunner;


public class HeadlessTaskRunner extends AbstractTaskRunner {
    private static final String TAG = "HeadlessTaskRunner";

    @Override
    public void runTask(Task task) {
        Context activeContext = getContext();
        if (activeContext == null) {
            Log.w(TAG, "Headless task dropped: Underlying execution context has been safely garbage collected.");
            return;
        }

        Intent serviceIntent = new Intent(activeContext, HeadlessService.class);
        serviceIntent.putExtras(task.getBundle());

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeContext.startForegroundService(serviceIntent);
            } else {
                activeContext.startService(serviceIntent);
            }
            Log.d(TAG, "Successfully dispatched task wrapper target down the HeadlessService pipeline.");
        } catch (IllegalStateException e) {
            Log.w(TAG, "OS restricted background service initialization window. " +
                    "Fallback handling required if background execution quotas are completely depleted.", e);
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected crash error encountered while starting background headless runner instance.", ex);
        }
    }
}