/*
According to apache license

This is fork of christocracy cordova-plugin-background-geolocation plugin
https://github.com/christocracy/cordova-plugin-background-geolocation

This is a new class
*/

package com.marianhello.bgloc.provider;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.data.BackgroundActivity;

import java.util.List;

public class ActivityRecognitionLocationProvider extends AbstractLocationProvider {

    private static final String TAG = "ActivityRecProvider";
    private static final String P_NAME = "com.marianhello.bgloc";
    private static final String DETECTED_ACTIVITY_UPDATE = P_NAME + ".DETECTED_ACTIVITY_UPDATE";

    private FusedLocationProviderClient mFusedLocationClient;
    private ActivityRecognitionClient mActivityRecognitionClient;
    private PendingIntent mDetectedActivitiesPI;

    private boolean isStarted = false;
    private boolean isTracking = false;
    private boolean isWatchingActivity = false;
    private DetectedActivity lastActivity = new DetectedActivity(DetectedActivity.UNKNOWN, 100);

    public ActivityRecognitionLocationProvider(Context context) {
        super(context, Config.ACTIVITY_PROVIDER);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(mContext);
        mActivityRecognitionClient = ActivityRecognition.getClient(mContext);

        Intent detectedActivitiesIntent = new Intent(DETECTED_ACTIVITY_UPDATE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        mDetectedActivitiesPI = PendingIntent.getBroadcast(mContext, 9002, detectedActivitiesIntent, flags);
    }

    @Override
    public void onStart() {
        logger.info("Start recording updates pipeline path.");
        this.isStarted = true;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext.registerReceiver(detectedActivitiesReceiver, new IntentFilter(DETECTED_ACTIVITY_UPDATE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(detectedActivitiesReceiver, new IntentFilter(DETECTED_ACTIVITY_UPDATE));
        }
        
        attachRecorder();
    }

    @Override
    public void onStop() {
        logger.info("Stopping location tracking and activity engines cleanly.");
        this.isStarted = false;
        
        detachRecorder();
        stopTracking();
        
        try {
            mContext.unregisterReceiver(detectedActivitiesReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Receiver was already unregistered or context scope lost.", e);
        }
    }

    @Override
    public void onConfigure(Config config) {
        super.onConfigure(config);
        if (isStarted) {
            onStop();
            onStart();
        }
    }

    @Override
    public boolean isStarted() {
        return isStarted;
    }

    private final LocationCallback mLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            for (Location location : locationResult.getLocations()) {
                if (location == null) continue;
                logger.debug("Location change verified: {}", location.toString());

                if (lastActivity.getType() == DetectedActivity.STILL) {
                    handleStationary(location);
                    stopTracking();
                    return;
                }

                showDebugToast("acy:" + location.getAccuracy() + ",v:" + location.getSpeed());
                handleLocation(location);
            }
        }
    };

    public void startTracking() {
        if (isTracking) return;

        int priority = translateDesiredAccuracy(mConfig.getDesiredAccuracy());
        
        LocationRequest locationRequest = new LocationRequest.Builder(priority, mConfig.getInterval())
                .setMinUpdateIntervalMillis(mConfig.getFastestInterval())
                .build();

        try {
            mFusedLocationClient.requestLocationUpdates(locationRequest, mLocationCallback, Looper.getMainLooper());
            isTracking = true;
            logger.debug("Start tracking with priority={}", priority);
        } catch (SecurityException e) {
            logger.error("Missing hardware runtime tracking permissions: {}", e.getMessage());
            this.handleSecurityException(e);
        }
    }

    public void stopTracking() {
        if (!isTracking) return;
        mFusedLocationClient.removeLocationUpdates(mLocationCallback);
        isTracking = false;
    }

    private void attachRecorder() {
        if (isWatchingActivity) return;
        
        startTracking();
        if (mConfig.getStopOnStillActivity()) {
            try {
                mActivityRecognitionClient.requestActivityUpdates(mConfig.getActivitiesInterval(), mDetectedActivitiesPI);
                isWatchingActivity = true;
                logger.info("Activity Recognition engine engaged successfully.");
            } catch (SecurityException e) {
                logger.error("Activity Recognition hardware privilege validation failed.", e);
            }
        }
    }

    private void detachRecorder() {
        if (isWatchingActivity) {
            logger.debug("Detaching Activity Recognition monitoring.");
            try {
                mActivityRecognitionClient.removeActivityUpdates(mDetectedActivitiesPI);
            } catch (Exception e) {
                logger.error("Error executing engine detachment", e);
            }
            isWatchingActivity = false;
        }
    }

    private int translateDesiredAccuracy(Integer accuracy) {
        if (accuracy == null) return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        if (accuracy >= 10000) return Priority.PRIORITY_PASSIVE;
        if (accuracy >= 1000) return Priority.PRIORITY_LOW_POWER;
        if (accuracy >= 100) return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        return Priority.PRIORITY_HIGH_ACCURACY;
    }

 
    public static DetectedActivity getProbableActivity(List<DetectedActivity> detectedActivities) {
        int highestConfidence = 0;
        DetectedActivity mostLikelyActivity = new DetectedActivity(DetectedActivity.UNKNOWN, 100);

        if (detectedActivities == null) return mostLikelyActivity;

        for (DetectedActivity da : detectedActivities) {
            int type = da.getType();
            if (type != DetectedActivity.TILTING && type != DetectedActivity.UNKNOWN) {
                if (highestConfidence < da.getConfidence()) {
                    highestConfidence = da.getConfidence();
                    mostLikelyActivity = da;
                }
            }
        }
        return mostLikelyActivity;
    }

    private final BroadcastReceiver detectedActivitiesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            
            if (ActivityRecognitionResult.hasResult(intent)) {
                ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
                if (result == null) return;

                List<DetectedActivity> detectedActivities = result.getProbableActivities();
                lastActivity = getProbableActivity(detectedActivities);

                logger.debug("Detected activity={} confidence={}", BackgroundActivity.getActivityString(lastActivity.getType()), lastActivity.getConfidence());
                handleActivity(lastActivity);

                if (lastActivity.getType() == DetectedActivity.STILL) {
                    showDebugToast("Detected STILL Activity");
                } else {
                    showDebugToast("Detected ACTIVE Activity");
                    startTracking();
                }
            }
        }
    };

    @Override
    public void onDestroy() {
        logger.info("Destroying ActivityRecognitionLocationProvider stack.");
        onStop();
        super.onDestroy();
    }
}