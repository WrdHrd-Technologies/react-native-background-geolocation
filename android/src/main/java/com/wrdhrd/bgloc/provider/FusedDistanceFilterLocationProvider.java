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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingEvent;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.provider.AbstractLocationProvider;
import com.marianhello.logging.LoggerManager;
import com.marianhello.utils.ToneGenerator;

import java.util.ArrayList;
import java.util.List;


public final class FusedDistanceFilterLocationProvider extends AbstractLocationProvider {
    private static final String TAG = "HybridProvider";
    private static final org.slf4j.Logger logger = LoggerManager.getLogger(FusedDistanceFilterLocationProvider.class);

    private static final String ACTION_HYBRID_GEOFENCE = "com.wrdhrd.bgloc.ACTION_HYBRID_GEOFENCE";
    private static final String ACTION_HYBRID_ACTIVITY = "com.wrdhrd.bgloc.ACTION_HYBRID_ACTIVITY";

    private final FusedLocationProviderClient mFusedLocationClient;
    private final GeofencingClient mGeofencingClient;
    private LocationCallback mFusedLocationCallback;

    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;

    private boolean isStarted = false;
    private boolean isMoving = false;
    private int stationaryCount = 0;
    private Location lastLocation;
    private Location stationaryLocation;
    private float stationaryRadius;

    private long mActiveInterval = -1;
    private long mActiveFastestInterval = -1;
    private long engineWakeTime = 0;
    private long sentryEngagedTime = 0;

    public static final String STATE_STILL = "STILL";
    public static final String STATE_WALKING = "WALKING";
    public static final String STATE_DRIVING = "DRIVING";
    private String currentKineticState = STATE_STILL;

    private PendingIntent mGeofencePendingIntent;
    private PendingIntent mActivityPendingIntent;

    public FusedDistanceFilterLocationProvider(@NonNull Context context) {
        super(context, Config.FUSED_DISTANCE_FILTER_PROVIDER);
        this.mFusedLocationClient = LocationServices.getFusedLocationProviderClient(mContext);
        this.mGeofencingClient = LocationServices.getGeofencingClient(mContext);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mWorkerThread = new HandlerThread("HybridLocationWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());

        int receiverFlags = ContextCompat.RECEIVER_NOT_EXPORTED;
        mContext.registerReceiver(mHybridGeofenceReceiver, new IntentFilter(ACTION_HYBRID_GEOFENCE), receiverFlags);
        mContext.registerReceiver(mHybridActivityReceiver, new IntentFilter(ACTION_HYBRID_ACTIVITY), receiverFlags);

        mFusedLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                processIncomingLocations(locationResult.getLocations());
            }
        };
    }

    private void processIncomingLocations(List<Location> locations) {
        for (Location location : locations) {
            if (location == null) continue;

            float speed = location.hasSpeed() ? location.getSpeed() : 0.0f;
            if (speed == 0.0f && lastLocation != null) {
                float distance = location.distanceTo(lastLocation);
                long timeDelta = location.getTime() - lastLocation.getTime();
                if (timeDelta > 0) {
                    speed = distance / (timeDelta / 1000.0f);
                    location.setSpeed(speed);
                }
            }

            applyDynamicPaceScaling(speed);

            float accuracy = location.hasAccuracy() ? location.getAccuracy() : 999.0f;
            boolean isHallucination = false;
            boolean isWarmingUp = (System.currentTimeMillis() - engineWakeTime) < 60000;

            if (isWarmingUp) {
                if (accuracy > 150.0f) isHallucination = true;
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
                if (System.currentTimeMillis() - sentryEngagedTime < 5000) continue;
                if (isHallucination && speed < 4.5f) continue;

                if (lastLocation != null) {
                    float displacement = location.distanceTo(lastLocation);
                    if (displacement < mConfig.getStationaryRadius() && speed < 4.5f) {
                        Location heartbeat = new Location(lastLocation);
                        heartbeat.setTime(System.currentTimeMillis());
                        heartbeat.setProvider("heartbeat_ping");
                        handleStationary(heartbeat, mConfig.getStationaryRadius());
                        continue;
                    }
                }

                logger.info("Spatial barrier breached inside tracking loop! Engaging high gear.");
                setPace(true);
                continue;
            }

            if (isHallucination) {
                logger.warn("Discarding inaccurate update ({}m) during movement.", accuracy);
                continue;
            }

            String rawProvider = location.getProvider();
            String baseProvider = (rawProvider != null) ? rawProvider.split("\\|")[0] : "fused";
            location.setProvider(baseProvider + "|" + currentKineticState);

            if (location.hasSpeed() && speed == 0.0f && accuracy <= 15.0f) {
                stationaryCount++;
                if (stationaryCount >= 5) {
                    logger.info("GPS Accrued Displacement triggered. Stillness verified. Fast-tracking sentry mode transition.");
                    executeSentryTransition(location);
                    continue;
                }
            } else if (STATE_STILL.equals(currentKineticState) || speed < 0.3f) {
                stationaryCount++;
                if (stationaryCount >= 25) { 
                    logger.info("Kinetic stillness confirmed via baseline sliding window. Triggering dual-sentry deployment.");
                    executeSentryTransition(location);
                    continue;
                }
            } else {
                stationaryCount = 0;
            }

            if (lastLocation != null) {
                float distanceMoved = location.distanceTo(lastLocation);
                int dynamicDistanceFilter = calculateElasticDistanceFilter(speed);
                if (distanceMoved < dynamicDistanceFilter) {
                    logger.debug("Elastic filter suppressed update. Moved {}m, requirement is {}m", distanceMoved, dynamicDistanceFilter);
                    continue;
                }
            }

            if (mConfig.isDebugging()) playDebugTone(ToneGenerator.Tone.BEEP);
            lastLocation = location;
            handleLocation(location);
        }
    }

    private void executeSentryTransition(Location location) {
        stationaryCount = 0;
        Location clonedLoc = (lastLocation != null) ? new Location(lastLocation) : new Location(location);
        clonedLoc.setProvider("heartbeat_ping");
        clonedLoc.setTime(System.currentTimeMillis());

        handleStationary(clonedLoc, mConfig.getStationaryRadius());
        if (mConfig.isDebugging()) playDebugTone(ToneGenerator.Tone.LONG_BEEP);

        sentryEngagedTime = System.currentTimeMillis();
        setPace(false);
    }

    private void applyDynamicPaceScaling(float speedMps) {
        float speedKmh = speedMps * 3.6f;
        long baseInterval = mConfig.getInterval();
        long baseFastest = mConfig.getFastestInterval();
        
        long targetInterval;
        long targetFastest;

        if (speedKmh <= 15.0f) {
            targetInterval = baseInterval;
            targetFastest = baseFastest;
        } else if (speedKmh <= 55.0f) {
            targetInterval = baseInterval * 2;
            targetFastest = baseFastest * 2;
        } else {
            targetInterval = baseInterval * 4;
            targetFastest = baseFastest * 4;
        }

        targetInterval = Math.max(5000, Math.min(targetInterval, 300000));
        targetFastest = Math.max(2000, Math.min(targetFastest, targetInterval));

        if (targetInterval != mActiveInterval && isMoving) {
            mActiveInterval = targetInterval;
            mActiveFastestInterval = targetFastest;
            reconfigureHardwareRequest(mActiveInterval, mActiveFastestInterval);
        }
    }

    @SuppressLint("MissingPermission")
    private void reconfigureHardwareRequest(long interval, long fastestInterval) {
        if (!isStarted || !isMoving) return;

        int priority = (mConfig.getDesiredAccuracy() <= 10) ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        LocationRequest activeRequest = new LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(fastestInterval)
                .setWaitForAccurateLocation(false)
                .build();

        mFusedLocationClient.requestLocationUpdates(activeRequest, mFusedLocationCallback ,mWorkerHandler.getLooper());
    }

    @SuppressLint("MissingPermission")
    public void forceHighGear() {
        if (!isMoving) {
            logger.warn("Emergency Override: Hardware Activity Tripwire snapped. Breaking out of Sentry Mode.");
            setPace(true);
        }
    }

    @SuppressLint("MissingPermission")
    private void setPace(boolean moving) {
        if (!isStarted) return;

        boolean breakoutTriggered = (!isMoving && moving);
        this.isMoving = moving;
        this.stationaryCount = 0;

        try {
            mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
            teardownSentryTripwires();

            if (isMoving) {
                if (breakoutTriggered) engineWakeTime = System.currentTimeMillis();
                logger.info("Kinetic Tracking active. Deploying high-frequency sampling loops.");

                mActiveInterval = mConfig.getInterval();
                mActiveFastestInterval = mConfig.getFastestInterval();
                reconfigureHardwareRequest(mActiveInterval, mActiveFastestInterval);
            } else {
                logger.info("Deploying Hybrid Sentry Grid [Geofence + Kinetic Activity Recognition].");
                mActiveInterval = -1;
                currentKineticState = STATE_STILL;

                LocationRequest passiveSentryRequest = new LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 300000)
                        .setMaxUpdateDelayMillis(600000)
                        .build();
                mFusedLocationClient.requestLocationUpdates(passiveSentryRequest,mFusedLocationCallback, mWorkerHandler.getLooper());

                deployHybridTripwires();
            }
        } catch (SecurityException e) {
            handleSecurityException(e);
        }
    }

    @SuppressLint("MissingPermission")
    private void deployHybridTripwires() {
        if (!hasRequiredPermissions()) return;

        Location origin = (lastLocation != null) ? lastLocation : stationaryLocation;
        if (origin == null) {
            mFusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener(mContext.getMainExecutor(), loc -> {
                        if (loc != null) {
                            stationaryLocation = loc;
                            deployHybridTripwires();
                        }
                    });
            return;
        }

        List<ActivityTransition> transitions = new ArrayList<>();
        transitions.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
        transitions.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
        transitions.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.RUNNING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());

        ActivityTransitionRequest activityRequest = new ActivityTransitionRequest(transitions);
        ActivityRecognition.getClient(mContext).requestActivityTransitionUpdates(activityRequest, getActivityPendingIntent());

        float configRadius = mConfig.getStationaryRadius();
        this.stationaryRadius = (origin.getAccuracy() < configRadius) ? configRadius : origin.getAccuracy();
        this.stationaryLocation = origin;

        Geofence boundaryFence = new Geofence.Builder()
                .setRequestId("HYBRID_SENTRY_FENCE")
                .setCircularRegion(origin.getLatitude(), origin.getLongitude(), this.stationaryRadius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build();

        GeofencingRequest geofencingRequest = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofence(boundaryFence)
                .build();

        mGeofencingClient.addGeofences(geofencingRequest, getGeofencePendingIntent());
        logger.info("Hybrid Tripwires locked. Spatial Boundary: {}m, Kinetic Monitor: ACTIVE.", this.stationaryRadius);
    }

    @SuppressLint("MissingPermission")
    private void teardownSentryTripwires() {
        try {
            if (hasActivityPermission()) {
                ActivityRecognition.getClient(mContext).removeActivityTransitionUpdates(getActivityPendingIntent());
            }
            mGeofencingClient.removeGeofences(getGeofencePendingIntent());
        } catch (Exception e) {
            logger.warn("Graceful tripwire teardown bypassed: {}", e.getMessage());
        }
    }

    private PendingIntent getGeofencePendingIntent() {
        if (mGeofencePendingIntent != null) return mGeofencePendingIntent;
        Intent intent = new Intent(ACTION_HYBRID_GEOFENCE).setPackage(mContext.getPackageName());
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE; 
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= 0x02000000;
        }
        mGeofencePendingIntent = PendingIntent.getBroadcast(mContext, 1001, intent, flags);
        return mGeofencePendingIntent;
    }

    private PendingIntent getActivityPendingIntent() {
        if (mActivityPendingIntent != null) return mActivityPendingIntent;
        Intent intent = new Intent(ACTION_HYBRID_ACTIVITY).setPackage(mContext.getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        mActivityPendingIntent = PendingIntent.getBroadcast(mContext, 1002, intent, flags);
        return mActivityPendingIntent;
    }

    private final BroadcastReceiver mHybridGeofenceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            GeofencingEvent event = GeofencingEvent.fromIntent(intent);
            if (event == null || event.hasError()) return;

            if (event.getGeofenceTransition() == Geofence.GEOFENCE_TRANSITION_EXIT) {
                long timeElapsedSinceSentry = System.currentTimeMillis() - sentryEngagedTime;
                if (timeElapsedSinceSentry < 45000) {
                    logger.warn("Geofence exit suppressed to prevent edge-jitter ping-pong loop ({}ms).", timeElapsedSinceSentry);
                    return;
                }

                logger.warn("Tripwire Tripped: SPATIAL GEOFENCE EXIT VALIDATED. Waking up engine.");
                playDebugTone(ToneGenerator.Tone.BEEP_BEEP_BEEP);
                
                mWorkerHandler.post(() -> {
                    if (!isMoving) setPace(true);
                });
            }
        }
    };

    private final BroadcastReceiver mHybridActivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ActivityTransitionResult.hasResult(intent)) {
                ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
                if (result == null) return;

                for (ActivityTransitionEvent event : result.getTransitionEvents()) {
                    int type = event.getActivityType();
                    if (type == DetectedActivity.IN_VEHICLE) {
                        currentKineticState = STATE_DRIVING;
                    } else if (type == DetectedActivity.WALKING || type == DetectedActivity.RUNNING) {
                        currentKineticState = STATE_WALKING;
                    }

                    logger.warn("Tripwire Tripped: KINETIC ACCELERATION SENSOR SNAPPED [{}]. Waking up engine.", currentKineticState);
                    playDebugTone(ToneGenerator.Tone.DOODLY_DOO);

                    mWorkerHandler.post(() -> {
                        if (!isMoving) setPace(true);
                    });
                    break;
                }
            }
        }
    };

    private boolean hasRequiredPermissions() {
        int fineLoc = ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION);
        boolean hasLoc = (fineLoc == PackageManager.PERMISSION_GRANTED);
        return hasLoc && hasActivityPermission();
    }

    private boolean hasActivityPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private int calculateElasticDistanceFilter(float speedMps) {
        int baseFilter = mConfig.getDistanceFilter();
        float speedKmh = speedMps * 3.6f;
        
        if (speedKmh > 22.0f) {
            return baseFilter * 2;
        }
        if (speedKmh > 6.0f) {
            int computedFilter = Math.round(speedMps * 10.0f);
            return Math.min(Math.max(computedFilter, baseFilter), 250);
        }
        return baseFilter;
    }

    @Override
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
        mWorkerHandler.post(() -> {
            mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
            teardownSentryTripwires();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        mWorkerHandler.post(() -> { if (!isMoving) setPace(true); });
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
            mContext.unregisterReceiver(mHybridGeofenceReceiver);
            mContext.unregisterReceiver(mHybridActivityReceiver);
        } catch (Exception ignored) {}
        
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public boolean isStarted() { return isStarted; }
}