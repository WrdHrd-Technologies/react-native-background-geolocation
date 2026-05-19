package com.wrdhrd.bgloc.provider;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Looper;
import androidx.core.app.ActivityCompat;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.provider.AbstractLocationProvider;
import com.marianhello.logging.LoggerManager;
import com.marianhello.utils.ToneGenerator;
import com.wrdhrd.bgloc.ActivityRecognitionReceiver;

import java.util.ArrayList;
import java.util.List;

public class FusedDistanceFilterLocationProvider extends AbstractLocationProvider {
    private static final org.slf4j.Logger logger = LoggerManager.getLogger(FusedDistanceFilterLocationProvider.class);

    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;

    private boolean isStarted = false;
    private boolean isMoving = false;
    private int stationaryCount = 0;
    private Location lastLocation;

    private long mActiveInterval = -1;
    private long mActiveFastestInterval = -1;
    private long engineWakeTime = 0;

    private static final float SPEED_STILL_MAX = 0.5f;
    private static final float SPEED_WALKING_MAX = 5.0f;

    public static final String ACTIVITY_STILL = "STILL";
    public static final String ACTIVITY_WALKING = "WALKING";
    public static final String ACTIVITY_DRIVING = "DRIVING";

    private String currentActivityState = ACTIVITY_STILL;
    private String pendingActivityState = ACTIVITY_STILL;
    private int stateConfidenceCount = 0;

    private static final String ACTION_CONTINUOUS_ACTIVITY = "com.wrdhrd.bgloc.INTERNAL_ACTIVITY_UPDATE";
    private long lastHardwareActivityTime = 0;
    private long sentryEngagedTime = 0;
    private PendingIntent mContinuousActivityPendingIntent;
    private PendingIntent mTripwirePendingIntent;

    public FusedDistanceFilterLocationProvider(Context context) {
        super(context, Config.FUSED_DISTANCE_FILTER_PROVIDER);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(mContext);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext.registerReceiver(mContinuousActivityReceiver, new IntentFilter(ACTION_CONTINUOUS_ACTIVITY), Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(mContinuousActivityReceiver, new IntentFilter(ACTION_CONTINUOUS_ACTIVITY));
        }

        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;

                for (Location location : locationResult.getLocations()) {

                    float speed = location.hasSpeed() ? location.getSpeed() : 0.0f;
                    if (speed == 0.0f && lastLocation != null) {
                        float distance = location.distanceTo(lastLocation);
                        long timeDeltaMillis = location.getTime() - lastLocation.getTime();
                        if (timeDeltaMillis > 0) {
                            speed = distance / (timeDeltaMillis / 1000.0f);
                            location.setSpeed(speed);
                        }
                    }

                    applyDynamicStrategy(speed);

                    float accuracy = location.hasAccuracy() ? location.getAccuracy() : 999.0f;
                    boolean isHallucination = false;
                    boolean isWarmingUp = (System.currentTimeMillis() - engineWakeTime) < 60000;

                    if (isWarmingUp) {
                        if (accuracy > 150.0f) {
                            isHallucination = true;
                        }
                    } else {
                        if (accuracy > 60.0f) {
                            isHallucination = true;
                        } else if (speed < 2.0f && accuracy > 25.0f) {
                            isHallucination = true;
                        } else if (speed >= 5.0f && accuracy > 35.0f) {
                            isHallucination = true;
                        }
                    }

                    if (!isMoving) {

                        if (System.currentTimeMillis() - sentryEngagedTime < 5000) {
                            logger.debug("SENTRY DEBOUNCER: Dropping instantaneous cached echo.");
                            continue;
                        }

                        if (isHallucination) {
                            logger.warn("SENTRY BLOCKED: Hallucination. Accuracy {}m is too blurry.", accuracy);
                            continue;
                        }

                        if (lastLocation != null) {
                            float breakoutDistance = location.distanceTo(lastLocation);
                            if (breakoutDistance < mConfig.getStationaryRadius()) {
                                logger.info("Sentry Heartbeat: User still parked. Cloning original location.");
                                Location clonedLocation = new Location(lastLocation);
                                long freshTime = System.currentTimeMillis();
                                clonedLocation.setTime(freshTime);
                                clonedLocation.setProvider("heartbeat_ping");
                                handleStationary(clonedLocation, mConfig.getStationaryRadius());
                                continue;
                            }
                        }
                        logger.info("Hardware Displacement Shield broken! Waking up engine.");
                        setPace(true);

                        continue;
                    }

                    if (isHallucination) {
                        logger.warn("MOVING BLOCKED: Accuracy {}m is too blurry for velocity {}m/s.", accuracy, speed);
                        continue;
                    }

                    long timeSinceHardwareUpdate = System.currentTimeMillis() - lastHardwareActivityTime;
                    boolean hardwareIsAlive = timeSinceHardwareUpdate < 30000;

                    if (!hardwareIsAlive) {
                        logger.debug("Hardware Activity Sensor silent. Falling back to Kinetic Math.");
                        String votedState;
                        if (speed <= SPEED_STILL_MAX) {
                            votedState = ACTIVITY_STILL;
                        } else if (speed <= SPEED_WALKING_MAX) {
                            votedState = ACTIVITY_WALKING;
                        } else {
                            votedState = ACTIVITY_DRIVING;
                        }

                        if (votedState.equals(currentActivityState)) {
                            stateConfidenceCount = 0;
                        } else if (votedState.equals(pendingActivityState)) {
                            stateConfidenceCount++;
                        } else {
                            pendingActivityState = votedState;
                            stateConfidenceCount = 1;
                        }

                        if (stateConfidenceCount >= 3) {
                            currentActivityState = pendingActivityState;
                            stateConfidenceCount = 0;
                        }
                    }

                    String rawProvider = location.getProvider();
                    if (rawProvider != null) {
                        String baseProvider = rawProvider.split("\\|")[0];
                        location.setProvider(baseProvider + "|" + currentActivityState);
                    } else {
                        location.setProvider("unknown|" + currentActivityState);
                    }

                    if (currentActivityState.equals(ACTIVITY_STILL)) {
                        stationaryCount++;
                        int PARKED_THRESHOLD = 30;

                        if (stationaryCount >= PARKED_THRESHOLD) {
                            stationaryCount = 0;
                            logger.info("User profoundly stationary. Engaging Heartbeat Sentry.");

                            Location clonedLocation;
                            if (lastLocation != null) {
                                clonedLocation = new Location(lastLocation);
                                clonedLocation.setProvider("heartbeat_ping");
                                long freshTime = System.currentTimeMillis();
                                clonedLocation.setTime(freshTime);
                            } else {
                                clonedLocation = new Location(location);
                                clonedLocation.setTime(location.getTime());
                            }

                            handleStationary(clonedLocation, mConfig.getStationaryRadius());
                            if (mConfig.isDebugging()) playDebugTone(ToneGenerator.Tone.LONG_BEEP);

                            sentryEngagedTime = System.currentTimeMillis();
                            setPace(false);
                            continue;
                        } else {
                            logger.debug("User STILL. Count: {}/{}. Keeping GPS hot.", stationaryCount, PARKED_THRESHOLD);
                        }
                    } else {
                        stationaryCount = 0;
                    }

                    if (lastLocation != null) {
                        float distance = location.distanceTo(lastLocation);
                        int dynamicFilter = calculateDynamicDistanceFilter(speed);
                        if (distance < dynamicFilter) {
                            logger.debug("Elastic Filter: Ignored {}m movement. Dynamic limit is {}m.", distance, dynamicFilter);
                            continue;
                        }
                    }

                    if (mConfig.isDebugging()) playDebugTone(ToneGenerator.Tone.BEEP);
                    logger.debug("Valid movement detected. Saving location.");
                    lastLocation = location;
                    handleLocation(location);
                }
            }
        };
    }

    private void applyDynamicStrategy(float speedMetersPerSecond) {
        float speedKmH = speedMetersPerSecond * 3.6f;

        long baseInterval = mConfig.getInterval();
        long baseFastest = mConfig.getFastestInterval();

        long targetInterval;
        long targetFastest;

        if (speedKmH <= 20) {
            targetInterval = baseInterval;
            targetFastest = baseFastest;
        } else if (speedKmH <= 60) {
            targetInterval = baseInterval * 2;
            targetFastest = baseFastest * 2;
        } else {
            targetInterval = baseInterval * 4;
            targetFastest = baseFastest * 4;
        }

        targetInterval = Math.max(5000, Math.min(targetInterval, 300000));
        targetFastest = Math.max(2000, Math.min(targetFastest, targetInterval));

        if (targetInterval != mActiveInterval && isMoving) {
            logger.info("DYNAMIC SHIFT: Speed {} km/h. Scaling Interval to {}s", (int)speedKmH, targetInterval / 1000);

            mActiveInterval = targetInterval;
            mActiveFastestInterval = targetFastest;
            updateHardwareRequest(mActiveInterval, mActiveFastestInterval);
        }
    }

    @SuppressLint("MissingPermission")
    private void updateHardwareRequest(long interval, long fastestInterval) {
        if (!isStarted || !isMoving) return;

        int priority = translateDesiredAccuracy(mConfig.getDesiredAccuracy());

        LocationRequest activeRequest = new LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(fastestInterval)
                .setWaitForAccurateLocation(false)
                .build();

        mFusedLocationClient.requestLocationUpdates(activeRequest, mLocationCallback, Looper.getMainLooper());
    }

    private BroadcastReceiver mContinuousActivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ActivityRecognitionResult.hasResult(intent)) {
                ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
                DetectedActivity probableActivity = result.getMostProbableActivity();

                int type = probableActivity.getType();
                if (type == DetectedActivity.IN_VEHICLE || type == DetectedActivity.ON_BICYCLE) {
                    currentActivityState = ACTIVITY_DRIVING;
                } else if (type == DetectedActivity.WALKING || type == DetectedActivity.RUNNING || type == DetectedActivity.ON_FOOT) {
                    currentActivityState = ACTIVITY_WALKING;
                } else if (type == DetectedActivity.STILL) {
                    currentActivityState = ACTIVITY_STILL;
                }

                lastHardwareActivityTime = System.currentTimeMillis();
            }
        }
    };

    private PendingIntent getContinuousActivityPendingIntent() {
        if (mContinuousActivityPendingIntent != null) return mContinuousActivityPendingIntent;
        Intent intent = new Intent(ACTION_CONTINUOUS_ACTIVITY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.DONUT) {
            intent.setPackage(mContext.getPackageName());
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        mContinuousActivityPendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, flags);
        return mContinuousActivityPendingIntent;
    }

    private PendingIntent getTripwirePendingIntent() {
        if (mTripwirePendingIntent != null) return mTripwirePendingIntent;
        Intent intent = new Intent(mContext, ActivityRecognitionReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        mTripwirePendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, flags);
        return mTripwirePendingIntent;
    }

    @SuppressLint("MissingPermission")
    public void forceHighGear() {
        if (!isMoving) {
            logger.warn("Emergency Override: Hardware Tripwire snapped. Breaking out of Sentry Mode.");
            setPace(true);
        }
    }

    @SuppressLint("MissingPermission")
    private void setPace(boolean moving) {
        if (!isStarted) return;

        boolean wakingFromSleep = (!isMoving && moving);

        isMoving = moving;
        stationaryCount = 0;

        try {
            mFusedLocationClient.removeLocationUpdates(mLocationCallback);

            if (isMoving) {
                if (wakingFromSleep) {
                    engineWakeTime = System.currentTimeMillis(); 
                }

                logger.info("Engaging Kinetic Tracking.");

                if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    ActivityRecognition.getClient(mContext).removeActivityTransitionUpdates(getTripwirePendingIntent());
                    ActivityRecognition.getClient(mContext).requestActivityUpdates(10000, getContinuousActivityPendingIntent());
                }

                mActiveInterval = mConfig.getInterval();
                mActiveFastestInterval = mConfig.getFastestInterval();
                updateHardwareRequest(mActiveInterval, mActiveFastestInterval);

            } else {
                logger.info("GPS suspended. Deploying Sentry Heartbeat Mode.");
                mActiveInterval = -1; // Reset scaler

                if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    ActivityRecognition.getClient(mContext).removeActivityUpdates(getContinuousActivityPendingIntent());

                    List<ActivityTransition> transitions = new ArrayList<>();
                    transitions.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
                    transitions.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());

                    ActivityTransitionRequest request = new ActivityTransitionRequest(transitions);
                    ActivityRecognition.getClient(mContext).requestActivityTransitionUpdates(request, getTripwirePendingIntent());
                }

                long sentryInterval = mConfig.getHeartbeatInterval();
                if (sentryInterval < 5 * 60 * 1000) {
                    sentryInterval = 5 * 60 * 1000;
                }
                
                long fastestSentryInterval = sentryInterval / 3;

                LocationRequest sentryRequest = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, sentryInterval)
                        .setMinUpdateIntervalMillis(fastestSentryInterval)
                        .build();

                mFusedLocationClient.requestLocationUpdates(sentryRequest, mLocationCallback, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            handleSecurityException(e);
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void onResume() {
        super.onResume();
        setPace(true);
    }

    @Override
    @SuppressLint("MissingPermission")
    public void onStart() {
        if (isStarted) return;
        super.onStart();
        isStarted = true;
        setPace(true);
    }

    @Override
    public void onStop() {
        if (!isStarted) return;
        super.onStop();
        isStarted = false;
        try {
            mFusedLocationClient.removeLocationUpdates(mLocationCallback);
            if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                ActivityRecognition.getClient(mContext).removeActivityUpdates(getContinuousActivityPendingIntent());
                ActivityRecognition.getClient(mContext).removeActivityTransitionUpdates(getTripwirePendingIntent());
            }
        } catch (SecurityException e) {
            handleSecurityException(e);
        }
    }

    private int translateDesiredAccuracy(Integer accuracy) {
        if (accuracy == null) return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        if (accuracy >= 1000) return Priority.PRIORITY_LOW_POWER;
        if (accuracy >= 100) return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        if (accuracy >= 0) return Priority.PRIORITY_HIGH_ACCURACY;
        return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
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
    public void onDestroy() {
        onStop();
        try {
            mContext.unregisterReceiver(mContinuousActivityReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public boolean isStarted() {
        return isStarted;
    }

    private int calculateDynamicDistanceFilter(float speed) {
        int baseFilter = mConfig.getDistanceFilter(); // Currently 10m
        float speedKmH = speed * 3.6f;
        
        if (speedKmH > 20) {
            return baseFilter * 2;
        }
        
        if (speedKmH > 5.0f) {
            int dynamicFilter = Math.round(speed * 10.0f);
            int lowerBound = Math.max(dynamicFilter, baseFilter);
            return Math.min(lowerBound, 300);
        }
        
        return Math.max(baseFilter, 30); 
    }
}