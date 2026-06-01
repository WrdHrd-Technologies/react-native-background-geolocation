package com.tenforwardconsulting.bgloc;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.provider.AbstractLocationProvider;
import com.marianhello.utils.ProviderSelector;
import com.marianhello.utils.ToneGenerator.Tone;

import java.util.List;

import static java.lang.Math.abs;
import static java.lang.Math.pow;
import static java.lang.Math.round;

public class DistanceFilterLocationProvider extends AbstractLocationProvider implements LocationListener {

    private static final String TAG = DistanceFilterLocationProvider.class.getSimpleName();
    private static final String P_NAME = "com.tenforwardconsulting.cordova.bgloc";

    private static final String STATIONARY_REGION_ACTION        = P_NAME + ".STATIONARY_REGION_ACTION";
    private static final String STATIONARY_ALARM_ACTION         = P_NAME + ".STATIONARY_ALARM_ACTION";
    private static final String SINGLE_LOCATION_UPDATE_ACTION   = P_NAME + ".SINGLE_LOCATION_UPDATE_ACTION";
    private static final String STATIONARY_LOCATION_MONITOR_ACTION = P_NAME + ".STATIONARY_LOCATION_MONITOR_ACTION";

    private static final long STATIONARY_TIMEOUT                                = 5 * 1000 * 60;    // 5 minutes.
    private static final long STATIONARY_LOCATION_POLLING_INTERVAL_LAZY         = 3 * 1000 * 60;    // 3 minutes.
    private static final long STATIONARY_LOCATION_POLLING_INTERVAL_AGGRESSIVE   = 1 * 1000 * 60;    // 1 minute.
    private static final int MAX_STATIONARY_ACQUISITION_ATTEMPTS = 5;
    private static final int MAX_SPEED_ACQUISITION_ATTEMPTS = 3;

    private Boolean isMoving = false;
    private Boolean isAcquiringStationaryLocation = false;
    private Boolean isAcquiringSpeed = false;
    private Integer locationAcquisitionAttempts = 0;

    private Location lastLocation;
    private long lastLocationTime = 0;
    private Location stationaryLocation;
    private float stationaryRadius;
    private PendingIntent stationaryAlarmPI;
    private PendingIntent stationaryLocationPollingPI;
    private long stationaryLocationPollingInterval;
    private PendingIntent stationaryRegionPI;
    private PendingIntent singleUpdatePI;
    private Integer scaledDistanceFilter;

    private Criteria criteria;

    private LocationManager locationManager;
    private AlarmManager alarmManager;

    private boolean isStarted = false;
    private boolean mReceiversRegistered = false;

    public DistanceFilterLocationProvider(Context context) {
        super(context, Config.DISTANCE_FILTER_PROVIDER);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        alarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        int immutableFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            immutableFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        int mutableFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mutableFlags |= PendingIntent.FLAG_MUTABLE;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mutableFlags |= PendingIntent.FLAG_MUTABLE;
        }

        // Stop-detection PI
        Intent stationaryAlarmActionIntent = new Intent(STATIONARY_ALARM_ACTION);
        stationaryAlarmActionIntent.setPackage(mContext.getPackageName());
        stationaryAlarmPI = PendingIntent.getBroadcast(mContext, 0, stationaryAlarmActionIntent, immutableFlags);

        // Stationary region PI
        Intent stationaryRegionActionIntent = new Intent(STATIONARY_REGION_ACTION);
        stationaryRegionActionIntent.setPackage(mContext.getPackageName());
        stationaryRegionPI = PendingIntent.getBroadcast(mContext, 0, stationaryRegionActionIntent, mutableFlags);

        // Stationary location monitor PI
        Intent stationaryLocationMonitorActionIntent = new Intent(STATIONARY_LOCATION_MONITOR_ACTION);
        stationaryLocationMonitorActionIntent.setPackage(mContext.getPackageName());
        stationaryLocationPollingPI = PendingIntent.getBroadcast(mContext, 0, stationaryLocationMonitorActionIntent, immutableFlags);

        Intent singleLocationUpdateActionIntent = new Intent(SINGLE_LOCATION_UPDATE_ACTION);
        singleLocationUpdateActionIntent.setPackage(mContext.getPackageName());
        singleUpdatePI = PendingIntent.getBroadcast(mContext, 0, singleLocationUpdateActionIntent, mutableFlags);

        registerTrackingReceivers();

        // Location criteria
        criteria = new Criteria();
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(true);
        criteria.setCostAllowed(true);
    }

    private void registerTrackingReceivers() {
        if (mReceiversRegistered) return;

        int receiverFlags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            receiverFlags = Context.RECEIVER_NOT_EXPORTED;
        }

        mContext.registerReceiver(stationaryAlarmReceiver, new IntentFilter(STATIONARY_ALARM_ACTION), receiverFlags);
        mContext.registerReceiver(stationaryRegionReceiver, new IntentFilter(STATIONARY_REGION_ACTION), receiverFlags);
        mContext.registerReceiver(stationaryLocationMonitorReceiver, new IntentFilter(STATIONARY_LOCATION_MONITOR_ACTION), receiverFlags);
        mContext.registerReceiver(singleUpdateReceiver, new IntentFilter(SINGLE_LOCATION_UPDATE_ACTION), receiverFlags);

        mReceiversRegistered = true;
    }

    @Override
    public void onStart() {
        if (isStarted) {
            return;
        }

        logger.info("Start recording");
        super.onStart();
        scaledDistanceFilter = mConfig.getDistanceFilter();
        isStarted = true;
        setPace(false);
    }

    @Override
    public void onStop() {
        if (!isStarted) {
            return;
        }

        try {
            super.onStop();
            locationManager.removeUpdates(this);
            locationManager.removeProximityAlert(stationaryRegionPI);
        } catch (SecurityException e) {
            logger.warn("Security restrictions encountered during provider de-allocation: {}", e.getMessage());
        } finally {
            isStarted = false;
        }
    }

    @Override
    public void onCommand(int commandId, int arg1) {
        switch(commandId) {
            case CMD_SWITCH_MODE:
                setPace(arg1 == BACKGROUND_MODE ? false : true);
                return;
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

    private void setPace(Boolean value) {
        if (!isStarted) {
            return;
        }

        logger.info("Setting pace: {}", value);

        Boolean wasMoving   = isMoving;
        isMoving            = value;
        isAcquiringStationaryLocation = false;
        isAcquiringSpeed    = false;
        stationaryLocation  = null;

        try {
            locationManager.removeUpdates(this);
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            criteria.setHorizontalAccuracy(translateDesiredAccuracy(mConfig.getDesiredAccuracy()));
            criteria.setPowerRequirement(Criteria.POWER_HIGH);

            if (isMoving) {
                if (!wasMoving) {
                    isAcquiringSpeed = true;
                }
            } else {
                if (lastLocation != null) {
                    stationaryLocation = new Location(lastLocation);
                }
                isAcquiringStationaryLocation = true;
            }

            if (isAcquiringSpeed || isAcquiringStationaryLocation) {
                locationAcquisitionAttempts = 0;
                List<String> matchingProviders = locationManager.getAllProviders();
                for (String provider: matchingProviders) {
                    if (provider != LocationManager.PASSIVE_PROVIDER) {
                        try {
                            locationManager.requestLocationUpdates(provider, 0, 0, this);
                        } catch (Exception ex) {
                            logger.warn("Platform skipped restricted provider assignment call [{}]: {}", provider, ex.getMessage());
                        }
                    }
                }
            } else {
                String provider = ProviderSelector.getBestLegacyProvider(locationManager, mConfig);
                locationManager.requestLocationUpdates(provider, mConfig.getInterval(), scaledDistanceFilter, this);
            }
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }
    }

    private Integer translateDesiredAccuracy(Integer accuracy) {
        if (accuracy >= 1000) {
            return Criteria.ACCURACY_LOW;
        }
        if (accuracy >= 100) {
            return Criteria.ACCURACY_MEDIUM;
        }
        if (accuracy >= 10) {
            return Criteria.ACCURACY_HIGH;
        }
        if (accuracy >= 0) {
            return Criteria.ACCURACY_HIGH;
        }

        return Criteria.ACCURACY_MEDIUM;
    }

    public Location getLastBestLocation() {
        Location bestResult = null;
        float bestAccuracy = Float.MAX_VALUE;
        long minTime = System.currentTimeMillis() - mConfig.getInterval();

        logger.info("Fetching last best location: radius={} minTime={}", mConfig.getStationaryRadius(), minTime);

        try {
            List<String> matchingProviders = locationManager.getAllProviders();
            for (String provider: matchingProviders) {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null) {
                    logger.debug("Test provider={} lat={} lon={} acy={} v={}m/s time={}", provider, location.getLatitude(), location.getLongitude(), location.getAccuracy(), location.getSpeed(), location.getTime());
                    float accuracy = location.getAccuracy();
                    long time = location.getTime();
                    if ((time > minTime && accuracy < bestAccuracy)) {
                        bestResult = location;
                        bestAccuracy = accuracy;
                    }
                }
            }
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }

        return bestResult;
    }

    public void onLocationChanged(Location location) {
        logger.debug("Location change: {} isMoving={}", location.toString(), isMoving);

        if (!isMoving && !isAcquiringStationaryLocation && stationaryLocation == null) {
            setPace(false);
        }

        showDebugToast("mv:" + isMoving + ",acy:" + location.getAccuracy() + ",v:" + location.getSpeed() + ",df:" + scaledDistanceFilter);

        if (isAcquiringStationaryLocation) {
            if (stationaryLocation == null || stationaryLocation.getAccuracy() > location.getAccuracy()) {
                stationaryLocation = location;
            }
            if (++locationAcquisitionAttempts == MAX_STATIONARY_ACQUISITION_ATTEMPTS) {
                isAcquiringStationaryLocation = false;
                startMonitoringStationaryRegion(stationaryLocation);
                handleStationary(stationaryLocation, stationaryRadius);
                return;
            } else {
                playDebugTone(Tone.BEEP);
                return;
            }
        } else if (isAcquiringSpeed) {
            if (++locationAcquisitionAttempts == MAX_SPEED_ACQUISITION_ATTEMPTS) {
                playDebugTone(Tone.DOODLY_DOO);
                isAcquiringSpeed = false;
                scaledDistanceFilter = calculateDistanceFilter(location.getSpeed());
                setPace(true);
            } else {
                playDebugTone(Tone.BEEP);
                return;
            }
        } else if (isMoving) {
            playDebugTone(Tone.BEEP);

            if ((location.getSpeed() >= 1) && (location.getAccuracy() <= mConfig.getStationaryRadius())) {
                resetStationaryAlarm();
            }
            
            Integer newDistanceFilter = calculateDistanceFilter(location.getSpeed());
            if (newDistanceFilter != scaledDistanceFilter.intValue()) {
                logger.info("Updating distanceFilter: new={} old={}", newDistanceFilter, scaledDistanceFilter);
                scaledDistanceFilter = newDistanceFilter;
                setPace(true);
            }
            if (lastLocation != null && location.distanceTo(lastLocation) < mConfig.getDistanceFilter()) {
                return;
            }
        } else if (stationaryLocation != null) {
            return;
        }
        
        lastLocation = location;
        lastLocationTime = System.currentTimeMillis();
        handleLocation(location);
    }

    public void resetStationaryAlarm() {
        alarmManager.cancel(stationaryAlarmPI);
        long triggerAt = SystemClock.elapsedRealtime() + STATIONARY_TIMEOUT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, stationaryAlarmPI);
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, stationaryAlarmPI);
        }
    }

    private Integer calculateDistanceFilter(Float speed) {
        Double newDistanceFilter = (double) mConfig.getDistanceFilter();
        if (speed < 100) {
            float roundedDistanceFilter = (round(speed / 5) * 5);
            newDistanceFilter = pow(roundedDistanceFilter, 2) + (double) mConfig.getDistanceFilter();
        }
        return (newDistanceFilter.intValue() < 1000) ? newDistanceFilter.intValue() : 1000;
    }

    private void startMonitoringStationaryRegion(Location location) {
        try {
            locationManager.removeUpdates(this);

            float stationaryRadius = mConfig.getStationaryRadius();
            float proximityRadius = (location.getAccuracy() < stationaryRadius) ? stationaryRadius : location.getAccuracy();
            stationaryLocation = location;

            logger.info("startMonitoringStationaryRegion: lat={} lon={} acy={}", location.getLatitude(), location.getLongitude(), proximityRadius);

            locationManager.addProximityAlert(
                    location.getLatitude(),
                    location.getLongitude(),
                    proximityRadius,
                    (long)-1,
                    stationaryRegionPI
            );

            this.stationaryRadius = proximityRadius;

            startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_LAZY);
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }
    }

    public void onExitStationaryRegion(Location location) {
        playDebugTone(Tone.BEEP_BEEP_BEEP);

        logger.info("Exited stationary: lat={} long={} acy={}}'",
                location.getLatitude(), location.getLongitude(), location.getAccuracy());

        try {
            alarmManager.cancel(stationaryLocationPollingPI);
            locationManager.removeProximityAlert(stationaryRegionPI);
            this.setPace(true);
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }
    }

    public void startPollingStationaryLocation(long interval) {
        stationaryLocationPollingInterval = interval;
        alarmManager.cancel(stationaryLocationPollingPI);
        long triggerAt = SystemClock.elapsedRealtime() + (60 * 1000);
        alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, interval, stationaryLocationPollingPI);
    }

    public void onPollStationaryLocation(Location location) {
        float stationaryRadius = mConfig.getStationaryRadius();
        long heartBeatInterval = mConfig.getHeartbeatInterval();

        if (isMoving) {
            return;
        }
        playDebugTone(Tone.BEEP);

        float distance = 0.0f;
        if (stationaryLocation != null) {
            distance = abs(location.distanceTo(stationaryLocation) - stationaryLocation.getAccuracy() - location.getAccuracy());
        }

        showDebugToast("Stationary exit in " + (stationaryRadius-distance) + "m");

        logger.info("Distance from stationary location: {}", distance);
        logger.info("Distance interval: {}", heartBeatInterval);
        if (distance > stationaryRadius) {
            onExitStationaryRegion(location);
        } else {
            long timeDiff = 0;

            if (heartBeatInterval > 0) {
                if (lastLocationTime == 0 && stationaryLocation != null) {
                   lastLocationTime = stationaryLocation.getTime();
                }
                 
                if (lastLocationTime != 0) {
                    timeDiff = System.currentTimeMillis() - lastLocationTime;
                }
                
                logger.debug("Stationary change Time Change: {}", timeDiff);
                if (heartBeatInterval < timeDiff) {
                    Location clonedLocation;
                    if (stationaryLocation != null) {
                        clonedLocation = new Location(stationaryLocation);
                        clonedLocation.setTime(System.currentTimeMillis());
                        clonedLocation.setProvider("heartbeat_ping");
                    } else {
                        clonedLocation = new Location(location);
                    }

                    lastLocation = clonedLocation;
                    lastLocationTime = clonedLocation.getTime();
                    handleStationary(clonedLocation);
                }
            }
            
            if (distance > 0) {
                startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_AGGRESSIVE);
            } else if (stationaryLocationPollingInterval != STATIONARY_LOCATION_POLLING_INTERVAL_LAZY) {
                startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_LAZY);
            }
        } 
    }

    private final BroadcastReceiver singleUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = LocationManager.KEY_LOCATION_CHANGED;
            Location location = (Location) intent.getExtras().get(key);
            if (location != null) {
                logger.debug("Single location update: " + location.toString());
                onPollStationaryLocation(location);
            }
        }
    };

    private final BroadcastReceiver stationaryAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logger.info("stationaryAlarm fired");
            setPace(false);
        }
    };

    private final BroadcastReceiver stationaryLocationMonitorReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            logger.info("Stationary location monitor fired");
            playDebugTone(Tone.DIALTONE);

            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
            criteria.setPowerRequirement(Criteria.POWER_HIGH);

            try {
                locationManager.requestSingleUpdate(criteria, singleUpdatePI);
            } catch (Exception e) {
                logger.error("Platform background execution constraints blocked single update trace request: {}", e.getMessage());
            }
        }
    };

    private final BroadcastReceiver stationaryRegionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = LocationManager.KEY_PROXIMITY_ENTERING;
            Boolean entering = intent.getBooleanExtra(key, false);

            if (entering) {
                logger.debug("Entering stationary region");
                if (isMoving) {
                    setPace(false);
                }
            } else {
                logger.debug("Exiting stationary region");
                Location location = getLastBestLocation();
                if (location != null) {
                    onExitStationaryRegion(location);
                }
            }
        }
    };

    public void onProviderDisabled(String provider) { logger.debug("Provider {} was disabled", provider); }
    public void onProviderEnabled(String provider) { logger.debug("Provider {} was enabled", provider); }
    public void onStatusChanged(String provider, int status, Bundle extras) { logger.debug("Provider {} status changed: {}", provider, status); }

    @Override
    public void onDestroy() {
        logger.info("Destroying DistanceFilterLocationProvider");

        this.onStop();
        alarmManager.cancel(stationaryAlarmPI);
        alarmManager.cancel(stationaryLocationPollingPI);

        if (mReceiversRegistered) {
            mContext.unregisterReceiver(stationaryAlarmReceiver);
            mContext.unregisterReceiver(singleUpdateReceiver);
            mContext.unregisterReceiver(stationaryRegionReceiver);
            mContext.unregisterReceiver(stationaryLocationMonitorReceiver);
            mReceiversRegistered = false;
        }

        super.onDestroy();
    }
}