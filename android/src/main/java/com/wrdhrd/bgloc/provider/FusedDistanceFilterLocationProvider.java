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
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.provider.AbstractLocationProvider;
import com.marianhello.logging.LoggerManager;
import com.marianhello.utils.ToneGenerator;

import com.wrdhrd.bgloc.HybridActivityReceiver;
import com.wrdhrd.bgloc.HybridGeofenceReceiver;

import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public final class FusedDistanceFilterLocationProvider extends AbstractLocationProvider {

    private static final Logger logger =
            LoggerManager.getLogger(FusedDistanceFilterLocationProvider.class);

    private static volatile WeakReference<FusedDistanceFilterLocationProvider> sActiveInstance =
            new WeakReference<>(null);

    public static final String ACTION_HYBRID_GEOFENCE = "com.wrdhrd.bgloc.ACTION_HYBRID_GEOFENCE";
    public static final String ACTION_HYBRID_ACTIVITY = "com.wrdhrd.bgloc.ACTION_HYBRID_ACTIVITY";

    // Defaults
    private static final long DEFAULT_INTERVAL = 10_000L;
    private static final long DEFAULT_FASTEST_INTERVAL = 5_000L;
    private static final float DEFAULT_DISTANCE_FILTER = 20.0f;
    private static final float DEFAULT_STATIONARY_RADIUS = 100.0f;
    private static final long DEFAULT_HEARTBEAT_INTERVAL = 10 * 60_000L;

    // Accuracy limits
    private static final float GOOD_ACCURACY_METERS = 50.0f;
    private static final float MAX_TRACKING_ACCURACY_METERS = 1_000.0f;
    private static final float STATIONARY_MAX_ACCURACY_METERS = 25.0f;

    // Speeds
    private static final float WALKING_SPEED_KMH = 15.0f;
    private static final float HIGH_SPEED_KMH = 60.0f;
    private static final float STATIONARY_SPEED_MPS = 0.5f;
    private static final float MAX_PHYSICAL_TRANSIT_SPEED_KMH = 350.0f;
    private static final float SUSPICIOUS_SPEED_KMH = 160.0f;

    // Multipliers
    private static final float WALKING_DISTANCE_MULTIPLIER = 1.0f;
    private static final float DRIVING_DISTANCE_MULTIPLIER = 2.0f;
    private static final float HIGH_SPEED_DISTANCE_MULTIPLIER = 3.0f;
    private static final float MAX_DYNAMIC_DISTANCE_FILTER = 100.0f;

    // Sentry / Reconfiguration
    private static final int STATIONARY_CONFIRMATION_COUNT = 6;
    private static final float MIN_SENTRY_GEOFENCE_RADIUS = 100.0f;
    public static final long SENTRY_START_DEBOUNCE_MS = 15_000L;
    private static final long REQUEST_RECONFIGURE_COOLDOWN_MS = 30_000L;

    // Kinetic states
    public static final String STATE_STILL = "STILL";
    public static final String STATE_WALKING = "WALKING";
    public static final String STATE_DRIVING = "DRIVING";
    private static final String EXTRA_KINETIC_STATE = "com.wrdhrd.bgloc.KINETIC_STATE";

    // Play Services
    private final FusedLocationProviderClient mFusedLocationClient;
    private final GeofencingClient mGeofencingClient;
    private LocationCallback mFusedLocationCallback;

    // Threading
    private final Object mLock = new Object();
    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;

    // State Tracking
    private volatile boolean isStarted = false;
    private boolean isMoving = false;
    private String currentKineticState = STATE_STILL;
    private int stationaryCount = 0;

    private Location lastGoodLocation;
    private Location lastLocation;
    private Location stationaryAnchorLocation;
    private float stationaryRadius;
    private long sentryEngagedTime = 0L;
    private long lifecycleGeneration = 0L;

    // Throttling Cache
    private long mLastRequestedInterval = -1L;
    private long mLastRequestedFastestInterval = -1L;
    private float mLastRequestedDistanceFilter = -1.0f;
    private int mLastRequestedPriority = -1;
    private long mLastHardwareRequestChangeTime = 0L;

    private long mPendingInterval = -1L;
    private long mPendingFastestInterval = -1L;
    private float mPendingDistanceFilter = -1.0f;
    private int mPendingPriority = -1;

    private final Runnable mReconfigureRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (mWorkerHandler == null || !isStarted || !isMoving) {
                    return;
                }

                if (mPendingInterval <= 0 || mPendingFastestInterval <= 0
                        || mPendingDistanceFilter <= 0 || mPendingPriority < 0) {
                    return;
                }

                long now = System.currentTimeMillis();
                long elapsed = now - mLastHardwareRequestChangeTime;

                if (mLastHardwareRequestChangeTime > 0 && elapsed < REQUEST_RECONFIGURE_COOLDOWN_MS) {
                    long remaining = REQUEST_RECONFIGURE_COOLDOWN_MS - elapsed;
                    mWorkerHandler.removeCallbacks(this);
                    mWorkerHandler.postDelayed(this, Math.max(remaining, 1000L));
                    return;
                }

                reconfigureHardwareRequest(
                        mPendingInterval,
                        mPendingFastestInterval,
                        mPendingDistanceFilter,
                        mPendingPriority
                );
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

            pm.setComponentEnabledSetting(
                    geofenceComponent,
                    newState,
                    PackageManager.DONT_KILL_APP
            );

            pm.setComponentEnabledSetting(
                    activityComponent,
                    newState,
                    PackageManager.DONT_KILL_APP
            );

            logger.info("Hybrid broadcast receivers dynamically toggled to: {}", enabled ? "ENABLED" : "DISABLED");
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
        if (accuracy > MAX_TRACKING_ACCURACY_METERS) {
            logger.debug("Discarding unusable location fix: accuracy={}m", accuracy);
            return;
        }

        float reportedSpeed = location.hasSpeed() ? Math.max(location.getSpeed(), 0.0f) : 0.0f;
        float distanceFromLastGood = 0.0f;
        long elapsedMillis = 0L;

        if (lastGoodLocation != null) {
            distanceFromLastGood = location.distanceTo(lastGoodLocation);
            elapsedMillis = getElapsedMillis(lastGoodLocation, location);
        }

        float calculatedSpeed = 0.0f;
        if (elapsedMillis > 0 && distanceFromLastGood >= 0.0f) {
            calculatedSpeed = distanceFromLastGood / (elapsedMillis / 1000.0f);
        }

        float speedMps = reportedSpeed > 0.0f ? reportedSpeed : calculatedSpeed;

        if (lastGoodLocation != null && elapsedMillis > 0) {
            float instantaneousSpeedKmh = calculatedSpeed * 3.6f;

            if (instantaneousSpeedKmh > MAX_PHYSICAL_TRANSIT_SPEED_KMH) {
                logger.warn("Discarded physical jump: speed={}km/h dist={}m", instantaneousSpeedKmh, distanceFromLastGood);
                return;
            }

            if (instantaneousSpeedKmh > SUSPICIOUS_SPEED_KMH && accuracy > GOOD_ACCURACY_METERS) {
                logger.warn("Discarded suspicious GPS hop: speed={}km/h acc={}m", instantaneousSpeedKmh, accuracy);
                return;
            }
        }

        updateKineticState(speedMps);

        if (!isMoving) {
            boolean displaced = lastGoodLocation != null && distanceFromLastGood >= getStationaryRadius();
            boolean highVelocity = speedMps >= (WALKING_SPEED_KMH / 3.6f);

            if (displaced || highVelocity) {
                logger.info("Kinetic breakout detected: dist={}m speed={}km/h", distanceFromLastGood, speedMps * 3.6f);
                setPace(true);
            } else {
                if (lastGoodLocation != null) {
                    emitHeartbeat();
                }
                return;
            }
        }

        if (accuracy <= STATIONARY_MAX_ACCURACY_METERS && speedMps <= STATIONARY_SPEED_MPS) {
            stationaryCount++;
            if (stationaryCount >= STATIONARY_CONFIRMATION_COUNT) {
                logger.info("Stationary threshold reached. Transitioning to sentry.");
                executeSentryTransition(location);
                return;
            }
        } else {
            stationaryCount = 0;
        }

        if (isMoving) {
            applyConfiguredActiveRequest(speedMps);
        }

        if (shouldDiscardBySoftwareDistanceFilter(location)) {
            return;
        }

        Location output = new Location(location);
        Bundle extras = output.getExtras();
        if (extras == null) extras = new Bundle();
        extras.putString(EXTRA_KINETIC_STATE, currentKineticState);
        output.setExtras(extras);

        lastGoodLocation = new Location(location);
        lastLocation = new Location(output);

        if (mConfig.isDebugging()) {
            playDebugTone(ToneGenerator.Tone.BEEP);
        }

        handleLocation(output);
    }

    private boolean shouldDiscardBySoftwareDistanceFilter(Location location) {
        if (lastLocation == null) return false;

        float distance = location.distanceTo(lastLocation);
        float speedMps = location.hasSpeed() ? Math.max(location.getSpeed(), 0.0f) : 0.0f;
        float dynamicDistance = calculateElasticDistanceFilter(speedMps);

        if (distance < dynamicDistance) {
            logger.debug("Suppressed by elastic filter: dist={}m dynamicMin={}m", distance, dynamicDistance);
            return true;
        }
        return false;
    }

    private void executeSentryTransition(Location location) {
        stationaryCount = 0;

        Location stationaryPoint = (lastGoodLocation != null && lastGoodLocation.getAccuracy() <= STATIONARY_MAX_ACCURACY_METERS)
                ? new Location(lastGoodLocation)
                : new Location(location);

        stationaryPoint.setProvider("heartbeat_ping");
        stationaryPoint.setTime(System.currentTimeMillis());
        stationaryRadius = getStationaryRadius();

        stationaryAnchorLocation = new Location(stationaryPoint);
        lastGoodLocation = new Location(stationaryPoint);
        lastLocation = new Location(stationaryPoint);

        handleStationary(stationaryPoint, stationaryRadius);

        if (mConfig.isDebugging()) {
            playDebugTone(ToneGenerator.Tone.LONG_BEEP);
        }

        sentryEngagedTime = System.currentTimeMillis();
        setPace(false);
    }


    @SuppressLint("MissingPermission")
    public void setPace(boolean moving) {
        if (!isStarted) return;

        if (!hasRequiredPermissions()) {
            handleSecurityException(new SecurityException("Cannot set pace: ACCESS_FINE_LOCATION missing"));
            return;
        }

        if (isMoving == moving) {
            if (moving && mLastRequestedInterval < 0) {
                applyConfiguredActiveRequest(0.0f);
            }
            return;
        }

        isMoving = moving;
        stationaryCount = 0;

        try {
            mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
            teardownSentryTripwires();
            resetRequestCache();

            if (moving) {
                logger.info("Kinetic tracking ACTIVE.");
                currentKineticState = currentKineticState.equals(STATE_STILL) ? STATE_WALKING : currentKineticState;
                applyConfiguredActiveRequest(0.0f);
            } else {
                logger.info("Sentry mode ENGAGED.");
                currentKineticState = STATE_STILL;

                long heartbeatInterval = getHeartbeatInterval();
                LocationRequest sentryRequest = new LocationRequest.Builder(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        heartbeatInterval
                )
                        .setMinUpdateIntervalMillis(Math.max(heartbeatInterval / 2L, 60_000L))
                        .setMinUpdateDistanceMeters(getConfiguredDistanceFilter())
                        .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                        .setWaitForAccurateLocation(false)
                        .build();

                synchronized (mLock) {
                    if (mWorkerHandler != null) {
                        mFusedLocationClient.requestLocationUpdates(
                                sentryRequest,
                                mFusedLocationCallback,
                                mWorkerHandler.getLooper()
                        );
                    }
                }
                deployHybridTripwires();
            }
        } catch (SecurityException e) {
            handleSecurityException(e);
        } catch (Exception e) {
            logger.error("Failed to alter pace state", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void applyConfiguredActiveRequest(float speedMps) {
        if (!isStarted || !isMoving) return;

        if (!hasRequiredPermissions()) {
            handleSecurityException(new SecurityException("ACCESS_FINE_LOCATION revoked during tracking"));
            return;
        }

        long interval = getConfiguredInterval();
        long fastestInterval = getConfiguredFastestInterval();
        float distanceFilter = calculateElasticDistanceFilter(speedMps);
        int priority = resolveLocationPriority();

        if (interval == mLastRequestedInterval
                && fastestInterval == mLastRequestedFastestInterval
                && Math.abs(distanceFilter - mLastRequestedDistanceFilter) < 0.5f
                && priority == mLastRequestedPriority) {
            return;
        }

        mPendingInterval = interval;
        mPendingFastestInterval = fastestInterval;
        mPendingDistanceFilter = distanceFilter;
        mPendingPriority = priority;

        long now = System.currentTimeMillis();
        long elapsed = now - mLastHardwareRequestChangeTime;

        if (mLastHardwareRequestChangeTime > 0 && elapsed < REQUEST_RECONFIGURE_COOLDOWN_MS) {
            long remaining = REQUEST_RECONFIGURE_COOLDOWN_MS - elapsed;
            synchronized (mLock) {
                if (mWorkerHandler != null) {
                    mWorkerHandler.removeCallbacks(mReconfigureRunnable);
                    mWorkerHandler.postDelayed(mReconfigureRunnable, Math.max(remaining, 1000L));
                }
            }
            return;
        }

        reconfigureHardwareRequest(interval, fastestInterval, distanceFilter, priority);
    }

    @SuppressLint("MissingPermission")
    private void reconfigureHardwareRequest(long interval, long fastestInterval, float distanceFilter, int priority) {
        if (!isStarted || !isMoving) return;

        if (!hasRequiredPermissions()) {
            handleSecurityException(new SecurityException("ACCESS_FINE_LOCATION revoked before request configuration"));
            return;
        }

        LocationRequest request = new LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(fastestInterval)
                .setMinUpdateDistanceMeters(distanceFilter)
                .setWaitForAccurateLocation(false)
                .build();

        try {
            mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
            synchronized (mLock) {
                if (mWorkerHandler != null) {
                    mFusedLocationClient.requestLocationUpdates(
                            request,
                            mFusedLocationCallback,
                            mWorkerHandler.getLooper()
                    );
                }
            }

            mLastRequestedInterval = interval;
            mLastRequestedFastestInterval = fastestInterval;
            mLastRequestedDistanceFilter = distanceFilter;
            mLastRequestedPriority = priority;
            mLastHardwareRequestChangeTime = System.currentTimeMillis();

            logger.info("Active request reconfiguration applied: interval={}ms dist={}m", interval, distanceFilter);
        } catch (SecurityException e) {
            handleSecurityException(e);
        } catch (Exception e) {
            logger.error("Failed to reconfigure location request", e);
        }
    }


    @SuppressLint("MissingPermission")
    private void deployHybridTripwires() {
        if (!isStarted || isMoving) return;

        if (!hasRequiredPermissions()) {
            handleSecurityException(new SecurityException("Cannot deploy tripwires: ACCESS_FINE_LOCATION missing"));
            return;
        }

        Location origin = (stationaryAnchorLocation != null)
                ? stationaryAnchorLocation
                : lastGoodLocation;

        if (origin == null) {
            final long gen = lifecycleGeneration;
            mFusedLocationClient.getLastLocation()
                    .addOnSuccessListener(loc -> {
                        synchronized (mLock) {
                            if (mWorkerHandler == null || !isStarted || isMoving || gen != lifecycleGeneration || loc == null) {
                                return;
                            }
                            mWorkerHandler.post(() -> {
                                stationaryAnchorLocation = new Location(loc);
                                deployHybridTripwires();
                            });
                        }
                    })
                    .addOnFailureListener(error -> {
                        if (error instanceof SecurityException) {
                            handleSecurityException((SecurityException) error);
                        } else {
                            logger.warn("Unable to obtain last known location: {}", error.getMessage());
                        }
                    });
            return;
        }

        if (hasActivityPermission()) {
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
                        .requestActivityTransitionUpdates(request, getActivityPendingIntent())
                        .addOnFailureListener(e -> {
                            if (e instanceof SecurityException) {
                                handleSecurityException((SecurityException) e);
                            } else {
                                logger.warn("Activity transitions registration failed: {}", e.getMessage());
                            }
                        });
            } catch (SecurityException e) {
                handleSecurityException(e);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            logger.warn("ACTIVITY_RECOGNITION missing on Android 10+");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            logger.warn("ACCESS_BACKGROUND_LOCATION missing. Geofencing disabled.");
            return;
        }

        stationaryRadius = getStationaryRadius();
        Geofence boundaryFence = new Geofence.Builder()
                .setRequestId("HYBRID_SENTRY_FENCE")
                .setCircularRegion(origin.getLatitude(), origin.getLongitude(), stationaryRadius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build();

        GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofence(boundaryFence)
                .build();

        try {
            mGeofencingClient.addGeofences(request, getGeofencePendingIntent())
                    .addOnFailureListener(e -> {
                        if (e instanceof SecurityException) {
                            handleSecurityException((SecurityException) e);
                        } else {
                            logger.warn("Geofence registration failed: {}", e.getMessage());
                        }
                    });
        } catch (SecurityException e) {
            handleSecurityException(e);
        }
    }

    @SuppressLint("MissingPermission")
    private void teardownSentryTripwires() {
        try {
            if (hasActivityPermission()) {
                ActivityRecognition.getClient(mContext)
                        .removeActivityTransitionUpdates(getActivityPendingIntent());
            }
            mGeofencingClient.removeGeofences(getGeofencePendingIntent());
        } catch (Exception e) {
            logger.debug("Tripwire cleanup completed: {}", e.getMessage());
        }
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

    private float calculateElasticDistanceFilter(float speedMps) {
        float baseFilter = getConfiguredDistanceFilter();
        float speedKmh = speedMps * 3.6f;

        if (speedKmh <= WALKING_SPEED_KMH) {
            return Math.min(baseFilter * WALKING_DISTANCE_MULTIPLIER, MAX_DYNAMIC_DISTANCE_FILTER);
        }
        if (speedKmh <= HIGH_SPEED_KMH) {
            return Math.min(baseFilter * DRIVING_DISTANCE_MULTIPLIER, MAX_DYNAMIC_DISTANCE_FILTER);
        }
        return Math.min(baseFilter * HIGH_SPEED_DISTANCE_MULTIPLIER, MAX_DYNAMIC_DISTANCE_FILTER);
    }

    private void emitHeartbeat() {
        if (lastGoodLocation == null) return;
        Location heartbeat = new Location(lastGoodLocation);
        heartbeat.setTime(System.currentTimeMillis());
        heartbeat.setProvider("heartbeat_ping");
        handleStationary(heartbeat, getStationaryRadius());
    }

    private long getElapsedMillis(Location previous, Location current) {
        if (previous == null || current == null) return 0L;
        long previousNanos = previous.getElapsedRealtimeNanos();
        long currentNanos = current.getElapsedRealtimeNanos();
        if (previousNanos > 0 && currentNanos > previousNanos) {
            return (currentNanos - previousNanos) / 1_000_000L;
        }
        long diff = current.getTime() - previous.getTime();
        return Math.max(diff, 0L);
    }

    private int resolveLocationPriority() {
        Integer desiredAccuracy = mConfig.getDesiredAccuracy();
        if (desiredAccuracy != null && desiredAccuracy <= 10) {
            return Priority.PRIORITY_HIGH_ACCURACY;
        }
        return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
    }

    private long getConfiguredInterval() {
        if (mConfig == null) return DEFAULT_INTERVAL;
        Integer val = mConfig.getInterval();
        return (val != null && val > 0) ? val.longValue() : DEFAULT_INTERVAL;
    }

    private long getConfiguredFastestInterval() {
        if (mConfig == null) return DEFAULT_FASTEST_INTERVAL;
        Integer val = mConfig.getFastestInterval();
        if (val != null && val > 0) {
            return val.longValue();
        }
        return Math.max(getConfiguredInterval() / 2L, 1000L);
    }

    private float getConfiguredDistanceFilter() {
        Integer val = mConfig.getDistanceFilter();
        return (val != null && val > 0) ? val.floatValue() : DEFAULT_DISTANCE_FILTER;
    }

    private float getStationaryRadius() {
        Float val = mConfig.getStationaryRadius();
        return Math.max((val != null && val > 0) ? val : DEFAULT_STATIONARY_RADIUS, MIN_SENTRY_GEOFENCE_RADIUS);
    }

    private long getHeartbeatInterval() {
        if (mConfig == null) return DEFAULT_HEARTBEAT_INTERVAL;
        Integer val = mConfig.getHeartbeatInterval();
        if (val != null && val > 0) {
            return val < 1000 ? (val.longValue() * 1000L) : val.longValue();
        }
        return DEFAULT_HEARTBEAT_INTERVAL;
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

    private void resetRequestCache() {
        mLastRequestedInterval = -1L;
        mLastRequestedFastestInterval = -1L;
        mLastRequestedDistanceFilter = -1.0f;
        mLastRequestedPriority = -1;
        mLastHardwareRequestChangeTime = 0L;
        mPendingInterval = -1L;
        mPendingFastestInterval = -1L;
        mPendingDistanceFilter = -1.0f;
        mPendingPriority = -1;

        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.removeCallbacks(mReconfigureRunnable);
            }
        }
    }


    @Override
    public void onStart() {
        if (isStarted) return;

        if (!hasRequiredPermissions()) {
            logger.error("Missing ACCESS_FINE_LOCATION permission on provider start.");
            handleSecurityException(new SecurityException("Missing ACCESS_FINE_LOCATION permission on provider start"));
            return;
        }

        super.onStart();
        isStarted = true;
        lifecycleGeneration++;
        stationaryCount = 0;

        // Dynamically enable receivers at the OS package level
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
                mWorkerHandler.post(() -> {
                    try {
                        mFusedLocationClient.removeLocationUpdates(mFusedLocationCallback);
                        teardownSentryTripwires();
                        resetRequestCache();
                        isMoving = false;
                        lastGoodLocation = null;
                        lastLocation = null;
                        stationaryAnchorLocation = null;
                    } catch (Exception e) {
                        logger.warn("Teardown error during onStop: {}", e.getMessage());
                    }
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
                    if (!isMoving) {
                        setPace(true);
                    } else {
                        resetRequestCache();
                        applyConfiguredActiveRequest(0.0f);
                    }
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
                    resetRequestCache();
                    if (isMoving) {
                        applyConfiguredActiveRequest(0.0f);
                    } else {
                        setPace(false);
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

    public void forceHighGear() {
        synchronized (mLock) {
            if (mWorkerHandler != null) {
                mWorkerHandler.post(() -> {
                    if (isStarted && !isMoving) {
                        logger.warn("High Gear explicitly forced. Engaging kinetic tracking.");
                        setPace(true);
                    }
                });
            }
        }
    }
}