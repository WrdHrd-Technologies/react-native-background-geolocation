package com.marianhello.bgloc.provider;

import android.content.Context;
import android.location.Location;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.marianhello.bgloc.Config;

public class FusedLocationProvider extends AbstractLocationProvider {

    private FusedLocationProviderClient client;
    public LocationRequest locationRequest;
    private boolean isStarted = false;

    public FusedLocationProvider(Context context) {
        super(context, Config.FUSED_PROVIDER);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        client = LocationServices.getFusedLocationProviderClient(this.mContext);
    }

    private final LocationCallback mLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            for (Location location : locationResult.getLocations()) {
                if (location == null) continue;
                
                logger.debug("Fused location packet received: {}", location.toString());
                showDebugToast("acy:" + location.getAccuracy() + ",v:" + location.getSpeed() + ",Fused Provider");
                
                handleLocation(location);
            }
        }
    };

    @Override
    public void onStart() {
        if (isStarted) {
            return;
        }
        try {
            super.onStart();
            logger.info("Starting Location Update with: Interval: {} : Distance Filter {}", 
                    mConfig.getInterval(), mConfig.getDistanceFilter());

            locationRequest = new LocationRequest.Builder(translateDesiredAccuracy(mConfig.getDesiredAccuracy()), mConfig.getInterval())
                    .setWaitForAccurateLocation(true)
                    .setMinUpdateDistanceMeters(mConfig.getDistanceFilter())
                    .setMinUpdateIntervalMillis(mConfig.getFastestInterval())
                    .build();

            client.requestLocationUpdates(locationRequest, mLocationCallback, Looper.getMainLooper());
            isStarted = true;
        } catch (SecurityException e) {
            logger.error("Hardware tracking authorization exception encountered: {}", e.getMessage());
            this.handleSecurityException(e);
        } catch (Exception ex) {
            logger.error("Unexpected failure initialization window crash", ex);
        }
    }

    @Override
    public void onStop() {
        if (!isStarted) {
            return;
        }
        try {
            super.onStop();
            logger.info("Stopping location updates execution loops.");
            
            client.removeLocationUpdates(mLocationCallback);
        } catch (SecurityException e) {
            logger.error("Security authorization exception on stop hook execution: {}", e.getMessage());
            this.handleSecurityException(e);
        } finally {
            isStarted = false;
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

    private int translateDesiredAccuracy(Integer accuracy) {
        if (accuracy == null) {
            return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        }
        if (accuracy >= 10000) {
            return Priority.PRIORITY_PASSIVE;
        }
        if (accuracy >= 1000) {
            return Priority.PRIORITY_LOW_POWER;
        }
        if (accuracy >= 100) {
            return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        }
        return Priority.PRIORITY_HIGH_ACCURACY;
    }

    @Override
    public void onDestroy() {
        logger.debug("Destroying Raw FusedLocationProvider instance stack.");
        this.onStop();
        super.onDestroy();
    }
}