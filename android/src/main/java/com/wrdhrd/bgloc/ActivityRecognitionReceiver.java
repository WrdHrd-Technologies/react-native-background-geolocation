package com.wrdhrd.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.marianhello.logging.LoggerManager;
import com.marianhello.bgloc.service.LocationServiceImpl;

public class ActivityRecognitionReceiver extends BroadcastReceiver {
    private static final org.slf4j.Logger logger = LoggerManager.getLogger(ActivityRecognitionReceiver.class);

    public static final String ACTION_ACTIVITY_TRANSITION = "com.wrdhrd.bgloc.ACTIVITY_TRANSITION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
            
            for (ActivityTransitionEvent event : result.getTransitionEvents()) {
                int activity = event.getActivityType();
                
                if (event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER &&
                   (activity == DetectedActivity.IN_VEHICLE || activity == DetectedActivity.WALKING)) {
                    
                    logger.warn("Activity TRIPWIRE SNAPPED! Accelerometer detected movement.");
                    
                    Intent serviceIntent = new Intent(context, LocationServiceImpl.class);
                    serviceIntent.setAction(ACTION_ACTIVITY_TRANSITION);
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    return; 
                }
            }
        }
    }
}