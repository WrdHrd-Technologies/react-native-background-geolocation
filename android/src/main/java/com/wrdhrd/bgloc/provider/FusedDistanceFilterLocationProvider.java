package com.wrdhrd.bgloc.provider;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.provider.AbstractLocationProvider;
import com.marianhello.logging.LoggerManager;

import com.wrdhrd.bgloc.HybridActivityReceiver;
import com.wrdhrd.bgloc.HybridGeofenceReceiver;

import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public final class FusedDistanceFilterLocationProvider extends AbstractLocationProvider {

    private static final Logger logger = LoggerManager.getLogger(FusedDistanceFilterLocationProvider.class);

    private static volatile WeakReference<FusedDistanceFilterLocationProvider> sActiveInstance =
            new WeakReference<>(null);

    public static final String ACTION_HYBRID_GEOFENCE = "com.wrdhrd.bgloc.ACTION_HYBRID_GEOFENCE";
    public static final String ACTION_HYBRID_ACTIVITY = "com.wrdhrd.bgloc.ACTION_HYBRID_ACTIVITY";

    public static final int ACCURACY_HIGH = 0;
    public static final int ACCURACY_MEDIUM = 100;
    public static final int ACCURACY_LOW = 1000;
    public static final int ACCURACY_PASSIVE = 10000;

    private static final long DEFAULT_INTERVAL = 10_000L;
    private static final long DEFAULT_FASTEST_INTERVAL = 5_000L;
    private static final float DEFAULT_DISTANCE_FILTER = 20.0f;
    private static final float DEFAULT_STATIONARY_RADIUS = 100.0f;

    private static final float GOOD_ACCURACY_METERS = 50.0f;
    private static final float MAX_TRACKING_ACCURACY_METERS = 500.0f;
    private static final float STATIONARY_MAX_ACCURACY_METERS = 65.0f;

    private static final float WALKING_SPEED_KMH = 15.0f;
    private static final float STATIONARY_SPEED_MPS = 0.8f;
    private static final float MAX_PHYSICAL_TRANSIT_SPEED_KMH = 350.0f;
    private static final float SUSPICIOUS_SPEED_KMH = 160.0f;

    private static final int STATIONARY_CONFIRMATION_COUNT = 3;
    public static final long SENTRY_START_DEBOUNCE_MS = 15_000L;

    public static final String STATE_STILL = "STILL";
    public static final String STATE_WALKING = "WALKING";
    public static final String STATE_DRIVING = "DRIVING";
    private static final String EXTRA_KINETIC_STATE = "com.wrdhrd.bgloc.KINETIC_STATE";

    private final FusedLocationProviderClient mFusedLocationClient;
    private final GeofencingClient mGeofencingClient;
    private LocationCallback mFusedLocationCallback;

    private final Object mLock = new Object();
    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;

    private volatile boolean isStarted = false;
    private boolean isMoving = false;
    private String currentKineticState = STATE_STILL;
    private int stationaryCount = 0;
    private boolean isActivityTransitionsArmed = false;

    private Location lastGoodLocation;
    private Location lastLocation;
    private Location stationaryAnchorLocation;
    private float stationaryRadius;
    private long sentryEngagedTime = 0L;
    private long lifecycleGeneration = 0L;

    private final Runnable mSentryHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (!isStarted || isMoving || mWorkerHandler == null) return;

                emitSentryHeartbeatPing();

                long interval = getHeartbeatIntervalMs();
                if (interval > 0) {
                    mWorkerHandler.postDelayed(this, interval);
                }
            }
        }
    };

    public FusedDistanceFilterLocationProvider(@NonNull Context context) {
        super(context, Config.FUSED_DISTANCE_FILTER_PROVIDER);
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(mContext);
        mGeofencingClient = LocationServices.getGeofencingClient(mContext);
    }

    public static FusedDistanceFilterLocationProvider getActiveInstance() {
        return sActiveInstance.get();
    }

    public Config getConfig() {
        return mConfig;
    }

    public long getSentryEngagedTime() {
        return sentryEngagedTime;
    }

    public boolean isMoving() {
        return isMoving;
    }

    public void setKineticState(String state) {
        this.currentKineticState = state;
    }

    public void postToWorker(Runnable r) {
        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.post(r);
            }
        }
    }

    private void setReceiversEnabled(boolean enabled) {
        try {
            PackageManager pm = mContext.getPackageManager();
            int newState = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

            ComponentName geofenceComponent = new ComponentName(mContext, HybridGeofenceReceiver.class);
            ComponentName activityComponent = new ComponentName(mContext, HybridActivityReceiver.class);

            pm.setComponentEnabledSetting(geofenceComponent, newState, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(activityComponent, newState, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            logger.error("Failed toggling hybrid receivers state", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (mLock) {
            mWorkerThread = new HandlerThread("FusedDistanceWorker", Process.THREAD_PRIORITY_BACKGROUND);
            mWorkerThread.start();
            mWorkerHandler = new Handler(mWorkerThread.getLooper());

            mFusedLocationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(@NonNull LocationResult result) {
                    if (!isStarted) return;
                    processIncomingLocations(result.getLocations());
                }
            };
        }
        sActiveInstance = new WeakReference<>(this);
    }

    private void processIncomingLocations(List<Location> locations) {
        if (!isStarted || locations == null || locations.isEmpty()) return;
        for (Location location : locations) {
            processLocation(location);
        }
    }

    private void processLocation(Location location) {
        if (!isStarted || location == null) return;

        float accuracy = location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;

        // Reject extremely noisy fixes immediately
        if (accuracy > MAX_TRACKING_ACCURACY_METERS) {
            logger.debug("Rejected low-accuracy fix: {}m", accuracy);
            return;
        }

        float reportedSpeed = location.hasSpeed() ? Math.max(location.getSpeed(), 0.0f) : 0.0f;
        float distanceFromLastGood = 0.0f;
        long elapsedMillis = 0L;

        if (lastGoodLocation != null) {
            distanceFromLastGood = location.distanceTo(lastGoodLocation);
            elapsedMillis = getElapsedMillis(lastGoodLocation, location);
        }

        float calculatedSpeed = (elapsedMillis > 0 && distanceFromLastGood >= 0.0f)
                ? distanceFromLastGood / (elapsedMillis / 1000.0f)
                : 0.0f;

        float speedMps = reportedSpeed > 0.0f ? reportedSpeed : calculatedSpeed;

        // Reject teleportation / multipath spikes:
        // If distance moved is less than the current accuracy radius, it is GPS noise, not movement.
        if (lastGoodLocation != null && distanceFromLastGood < accuracy && speedMps < 1.5f) {
            logger.debug("Suppressed multipath jitter: dist={}m within acc={}m", distanceFromLastGood, accuracy);

            // Count toward entering Sentry mode so the engine can lock down
            stationaryCount++;
            if (stationaryCount >= STATIONARY_CONFIRMATION_COUNT) {
                executeSentryTransition(lastGoodLocation);
            }
            return;
        }

        // High speed sanity check
        if (lastGoodLocation != null && elapsedMillis > 0) {
            float instantaneousSpeedKmh = calculatedSpeed * 3.6f;
            if (instantaneousSpeedKmh > MAX_PHYSICAL_TRANSIT_SPEED_KMH) return;
            if (instantaneousSpeedKmh > SUSPICIOUS_SPEED_KMH && accuracy > GOOD_ACCURACY_METERS) return;
        }

        updateKineticState(speedMps);
        manageSensorHubState(speedMps * 3.6f);

        // Breakout condition: require significant displacement and speed confirmation
        if (!isMoving) {
            float breakoutThreshold = Math.max(getStationaryRadius(), accuracy);
            boolean displaced = lastGoodLocation != null && distanceFromLastGood >= breakoutThreshold;
            boolean highVelocity = speedMps >= (WALKING_SPEED_KMH / 3.6f) && accuracy <= 65.0f;

            if (displaced || highVelocity) {
                logger.info("Kinetic breakout confirmed (dist={}m > threshold={}m). Engaging GPS.", distanceFromLastGood, breakoutThreshold);
                setPace(true);
            } else {
                return;
            }
        }

        // Standard Stationary confirmation
        if (accuracy <= STATIONARY_MAX_ACCURACY_METERS && speedMps <= STATIONARY_SPEED_MPS) {
            stationaryCount++;
            if (stationaryCount >= STATIONARY_CONFIRMATION_COUNT) {
                logger.info("Stationary confirmed. Entering zero-power Sentry mode.");
                executeSentryTransition(location);
                return;
            }
        } else {
            stationaryCount = 0;
        }

        Location output = new Location(location);
        Bundle extras = output.getExtras();
        if (extras == null) extras = new Bundle();
        extras.putString(EXTRA_KINETIC_STATE, currentKineticState);
        output.setExtras(extras);

        lastGoodLocation = new Location(location);
        lastLocation = new Location(output);

        handleLocation(output);
    }

    private void executeSentryTransition(Location location) {
        stationaryCount = 0;

        Location stationaryPoint = (lastGoodLocation != null && lastGoodLocation.getAccuracy() <= STATIONARY_MAX_ACCURACY_METERS)
                ? new Location(lastGoodLocation)
                : new Location(location);

        stationaryPoint.setProvider("heartbeat_ping");
        stationaryRadius = getStationaryRadius();

        stationaryAnchorLocation = new Location(stationaryPoint);
        lastGoodLocation = new Location(stationaryPoint);
        lastLocation = new Location(stationaryPoint);

        // Dispatches to AbstractLocationProvider
        handleStationary(stationaryPoint, stationaryRadius);

        sentryEngagedTime = System.currentTimeMillis();
        setPace(false);
    }

    private void emitSentryHeartbeatPing() {
        Location anchor = (stationaryAnchorLocation != null) ? stationaryAnchorLocation : lastGoodLocation;
        if (anchor == null) return;

        Location heartbeatFix = new Location(anchor);
        heartbeatFix.setProvider("heartbeat_ping");
        heartbeatFix.setTime(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            heartbeatFix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }

        Bundle extras = heartbeatFix.getExtras();
        if (extras == null) extras = new Bundle();
        extras.putString(EXTRA_KINETIC_STATE, STATE_STILL);
        heartbeatFix.setExtras(extras);

        logger.info("Emitting periodic zero-power heartbeat ping while stationary.");
        handleStationary(heartbeatFix, stationaryRadius);
    }

    @SuppressLint("MissingPermission")
    public void setPace(boolean moving) {
        if (!isStarted) return;
        if (!hasRequiredPermissions()) return;

        if (isMoving == moving) return;

        isMoving = moving;
        stationaryCount = 0;

        try {
            mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
            teardownSentryTripwires();

            synchronized (mLock) {
                if (mWorkerHandler != null) {
                    mWorkerHandler.removeCallbacks(mSentryHeartbeatRunnable);
                }
            }

            if (moving) {
                logger.info("Kinetic tracking ACTIVE. Requesting Hardware-Batched Fused Updates.");
                currentKineticState = currentKineticState.equals(STATE_STILL) ? STATE_WALKING : currentKineticState;

                long interval = getConfiguredInterval();
                long fastestInterval = getConfiguredFastestInterval();
                float distanceFilter = getConfiguredDistanceFilter();

                // Hardware Fix Batching: Batches fixes on sensor hub to reduce AP CPU wakeups
                LocationRequest request = new LocationRequest.Builder(resolveLocationPriority(), interval)
                        .setMinUpdateIntervalMillis(fastestInterval)
                        .setMinUpdateDistanceMeters(distanceFilter)
                        .setMaxUpdateDelayMillis(interval * 3L)
                        .setWaitForAccurateLocation(false)
                        .build();

                synchronized (mLock) {
                    if (mWorkerHandler != null) {
                        mFusedLocationClient.requestLocationUpdates(
                                request,
                                mFusedLocationCallback,
                                mWorkerHandler.getLooper()
                        );
                    }
                }
            } else {
                logger.info("Sentry mode ENGAGED. Total GPS shutdown; passive tripwires and heartbeat armed.");
                currentKineticState = STATE_STILL;
                deployHybridTripwires();

                long heartbeatInterval = getHeartbeatIntervalMs();
                if (heartbeatInterval > 0) {
                    synchronized (mLock) {
                        if (mWorkerHandler != null) {
                            mWorkerHandler.postDelayed(mSentryHeartbeatRunnable, heartbeatInterval);
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            handleSecurityException(e);
        }
    }

    public void forceHighGear() {
        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.post(() -> {
                    if (isStarted && !isMoving) {
                        setPace(true);
                    }
                });
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void deployHybridTripwires() {
        if (!isStarted || isMoving) return;
        if (!hasRequiredPermissions()) return;

        Location origin = (stationaryAnchorLocation != null) ? stationaryAnchorLocation : lastGoodLocation;
        if (origin == null) {
            final long gen = lifecycleGeneration;
            mFusedLocationClient.getLastLocation().addOnSuccessListener(loc -> {
                synchronized (mLock) {
                    if (mWorkerHandler == null || !isStarted || isMoving || gen != lifecycleGeneration || loc == null) return;
                    mWorkerHandler.post(() -> {
                        stationaryAnchorLocation = new Location(loc);
                        deployHybridTripwires();
                    });
                }
            });
            return;
        }

        armActivityRecognitionTransitions();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            return;
        }

        float anchorAccuracy = origin.hasAccuracy() ? origin.getAccuracy() : 25.0f;
        stationaryRadius = Math.max(getStationaryRadius(), anchorAccuracy * 1.5f);
        stationaryRadius = Math.max(stationaryRadius, 100.0f);

        Geofence boundaryFence = new Geofence.Builder()
                .setRequestId("HYBRID_SENTRY_FENCE")
                .setCircularRegion(origin.getLatitude(), origin.getLongitude(), stationaryRadius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setNotificationResponsiveness(0)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build();

        GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofence(boundaryFence)
                .build();

        try {
            mGeofencingClient.addGeofences(request, getGeofencePendingIntent());
            logger.info("Hybrid sentry geofence deployed with adaptive radius: {}m", stationaryRadius);
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void armActivityRecognitionTransitions() {
        if (!hasActivityPermission() || isActivityTransitionsArmed) return;

        List<ActivityTransition> transitions = new ArrayList<>();
        int[] activities = {DetectedActivity.IN_VEHICLE, DetectedActivity.WALKING, DetectedActivity.RUNNING};
        for (int act : activities) {
            transitions.add(new ActivityTransition.Builder()
                    .setActivityType(act)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build());
        }

        ActivityTransitionRequest request = new ActivityTransitionRequest(transitions);
        try {
            ActivityRecognition.getClient(mContext)
                    .requestActivityTransitionUpdates(request, getActivityPendingIntent());
            isActivityTransitionsArmed = true;
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void unarmActivityRecognitionTransitions() {
        if (!hasActivityPermission() || !isActivityTransitionsArmed) return;
        try {
            ActivityRecognition.getClient(mContext).removeActivityTransitionUpdates(getActivityPendingIntent());
            isActivityTransitionsArmed = false;
        } catch (Exception ignored) {}
    }

    private void manageSensorHubState(float speedKmh) {
        if (speedKmh > 30.0f && isActivityTransitionsArmed) {
            unarmActivityRecognitionTransitions();
        } else if (speedKmh < 5.0f && !isActivityTransitionsArmed) {
            armActivityRecognitionTransitions();
        }
    }

    @SuppressLint("MissingPermission")
    private void teardownSentryTripwires() {
        try {
            unarmActivityRecognitionTransitions();
            mGeofencingClient.removeGeofences(getGeofencePendingIntent());
        } catch (Exception ignored) {}
    }

    private int getPendingIntentFlags(boolean mutable) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= (mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE);
        }
        return flags;
    }

    private PendingIntent getGeofencePendingIntent() {
        Intent intent = new Intent(mContext, HybridGeofenceReceiver.class);
        intent.setAction(ACTION_HYBRID_GEOFENCE);
        intent.setPackage(mContext.getPackageName());
        return PendingIntent.getBroadcast(mContext, 1001, intent, getPendingIntentFlags(true));
    }

    private PendingIntent getActivityPendingIntent() {
        Intent intent = new Intent(mContext, HybridActivityReceiver.class);
        intent.setAction(ACTION_HYBRID_ACTIVITY);
        intent.setPackage(mContext.getPackageName());
        return PendingIntent.getBroadcast(mContext, 1002, intent, getPendingIntentFlags(true));
    }

    private void updateKineticState(float speedMps) {
        float speedKmh = speedMps * 3.6f;
        if (speedKmh > WALKING_SPEED_KMH) {
            currentKineticState = STATE_DRIVING;
        } else if (speedKmh > 2.0f) {
            currentKineticState = STATE_WALKING;
        } else {
            currentKineticState = STATE_STILL;
        }
    }

    private long getElapsedMillis(Location previous, Location current) {
        if (previous == null || current == null) return 0L;
        long previousNanos = previous.getElapsedRealtimeNanos();
        long currentNanos = current.getElapsedRealtimeNanos();
        if (previousNanos > 0 && currentNanos > previousNanos) {
            return (currentNanos - previousNanos) / 1_000_000L;
        }
        return Math.max(current.getTime() - previous.getTime(), 0L);
    }

    private int resolveLocationPriority() {
        if (mConfig == null) return Priority.PRIORITY_BALANCED_POWER_ACCURACY;

        // Dynamic speed override takes precedence if driving fast (> 20 km/h)
        if (isMoving && lastLocation != null && (lastLocation.getSpeed() * 3.6f) > 20.0f) {
            return Priority.PRIORITY_HIGH_ACCURACY;
        }

        Integer desiredAccuracy = mConfig.getDesiredAccuracy();
        if (desiredAccuracy == null) {
            return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        }

        // Resolves 0 | 100 | 1000 | 10000 | number
        if (desiredAccuracy <= ACCURACY_HIGH || desiredAccuracy < 50) {
            return Priority.PRIORITY_HIGH_ACCURACY;
        } else if (desiredAccuracy <= ACCURACY_MEDIUM) {
            return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        } else if (desiredAccuracy <= ACCURACY_LOW) {
            return Priority.PRIORITY_LOW_POWER;
        } else {
            return Priority.PRIORITY_PASSIVE;
        }
    }

    private long getConfiguredInterval() {
        if (mConfig == null) return DEFAULT_INTERVAL;
        Integer val = mConfig.getInterval();
        if (val == null || val <= 0) return DEFAULT_INTERVAL;
        return val < 1000 ? val.longValue() * 1000L : val.longValue();
    }

    private long getConfiguredFastestInterval() {
        if (mConfig == null) return DEFAULT_FASTEST_INTERVAL;
        Integer val = mConfig.getFastestInterval();
        if (val == null || val <= 0) return Math.max(getConfiguredInterval() / 2L, 1000L);
        return val < 1000 ? val.longValue() * 1000L : val.longValue();
    }

    private float getConfiguredDistanceFilter() {
        if (mConfig == null) return DEFAULT_DISTANCE_FILTER;
        Integer val = mConfig.getDistanceFilter();
        return (val != null && val > 0) ? val.floatValue() : DEFAULT_DISTANCE_FILTER;
    }

    private float getStationaryRadius() {
        if (mConfig == null) return DEFAULT_STATIONARY_RADIUS;
        Float val = mConfig.getStationaryRadius();
        return (val != null && val > 0) ? val : DEFAULT_STATIONARY_RADIUS;
    }

    private long getHeartbeatIntervalMs() {
        if (mConfig == null) return 0L;
        Integer raw = mConfig.getHeartbeatInterval();
        if (raw == null || raw <= 0) return 0L;
        return raw < 1000 ? raw.longValue() * 1000L : raw.longValue();
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasActivityPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onStart() {
        if (isStarted) return;
        if (!hasRequiredPermissions()) return;

        super.onStart();
        isStarted = true;
        lifecycleGeneration++;
        stationaryCount = 0;

        setReceiversEnabled(true);
        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.post(() -> {
                    if (isStarted) setPace(true);
                });
            }
        }
    }

    @Override
    public void onStop() {
        if (!isStarted) return;
        isStarted = false;
        lifecycleGeneration++;

        setReceiversEnabled(false);
        super.onStop();

        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.removeCallbacks(mSentryHeartbeatRunnable);
                mWorkerHandler.post(() -> {
                    try {
                        mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
                        teardownSentryTripwires();
                        isMoving = false;
                        lastGoodLocation = null;
                        lastLocation = null;
                        stationaryAnchorLocation = null;
                    } catch (Exception ignored) {}
                });
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isStarted) return;
        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.post(() -> {
                    if (!isStarted) return;
                    if (!isMoving) setPace(true);
                });
            }
        }
    }

    @Override
    public void onConfigure(Config config) {
        super.onConfigure(config);
        if (!isStarted) return;
        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.post(() -> {
                    if (!isStarted) return;
                    if (isMoving) {
                        // Re-evaluate active GPS request parameters dynamically
                        try {
                            mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
                            long interval = getConfiguredInterval();
                            long fastestInterval = getConfiguredFastestInterval();
                            float distanceFilter = getConfiguredDistanceFilter();

                            LocationRequest request = new LocationRequest.Builder(resolveLocationPriority(), interval)
                                    .setMinUpdateIntervalMillis(fastestInterval)
                                    .setMinUpdateDistanceMeters(distanceFilter)
                                    .setMaxUpdateDelayMillis(interval * 3L)
                                    .setWaitForAccurateLocation(false)
                                    .build();

                            mFusedLocationClient.requestLocationUpdates(
                                    request,
                                    mFusedLocationCallback,
                                    mWorkerHandler.getLooper()
                            );
                        } catch (SecurityException e) {
                            handleSecurityException(e);
                        }
                    } else {
                        // Re-arm heartbeat loop with updated configuration
                        mWorkerHandler.removeCallbacks(mSentryHeartbeatRunnable);
                        long heartbeatInterval = getHeartbeatIntervalMs();
                        if (heartbeatInterval > 0) {
                            mWorkerHandler.postDelayed(mSentryHeartbeatRunnable, heartbeatInterval);
                        }
                    }
                });
            }
        }
    }

    @Override
    public void onDestroy() {
        isStarted = false;
        lifecycleGeneration++;
        setReceiversEnabled(false);

        try {
            mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
            teardownSentryTripwires();
        } catch (Exception ignored) {}

        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.removeCallbacks(mSentryHeartbeatRunnable);
                mWorkerHandler.removeCallbacksAndMessages(null);
            }
            if (mWorkerThread != null) {
                mWorkerThread.quitSafely();
                mWorkerThread = null;
                mWorkerHandler = null;
            }
        }

        sActiveInstance.clear();
        super.onDestroy();
    }

    @Override
    public boolean isStarted() {
        return isStarted;
    }
}