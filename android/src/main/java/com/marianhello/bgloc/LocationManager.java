package com.marianhello.bgloc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.jparkie.promise.Promise;
import com.github.jparkie.promise.Promises;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.intentfilter.androidpermissions.PermissionManager;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class LocationManager {
    private static final String TAG = "LocationManager";
    private static volatile LocationManager sInstance;

    private final Context mContext;
    private final FusedLocationProviderClient mFusedClient;
    private final ExecutorService mWorkerExecutor;

    public static final String[] PERMISSIONS = new String[]{
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    public class PermissionDeniedException extends Exception {
        public PermissionDeniedException() {
            super("Required location access configurations were rejected by the user.");
        }
    }

    private LocationManager(@NonNull Context context) {
        this.mContext = context.getApplicationContext();
        this.mFusedClient = LocationServices.getFusedLocationProviderClient(mContext);
        this.mWorkerExecutor = Executors.newSingleThreadExecutor();
    }

    public static LocationManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (LocationManager.class) {
                if (sInstance == null) {
                    sInstance = new LocationManager(context);
                }
            }
        }
        return sInstance;
    }

    public Promise<Location> getCurrentLocation(final int timeout, final long maximumAge, final boolean enableHighAccuracy) {
        final Promise<Location> promise = Promises.promise();

        PermissionManager permissionManager = PermissionManager.getInstance(mContext);
        permissionManager.checkPermissions(Arrays.asList(PERMISSIONS), new PermissionManager.PermissionRequestListener() {
            @Override
            public void onPermissionGranted() {
                mWorkerExecutor.execute(() -> {
                    try {
                        Location currentLocation = getCurrentLocationNoCheck(timeout, maximumAge, enableHighAccuracy);
                        if (currentLocation != null) {
                            promise.set(currentLocation);
                        } else {
                            promise.setError(new Exception("Fused hardware returned an empty coordinate set."));
                        }
                    } catch (TimeoutException e) {
                        Log.w(TAG, "Hardware request window timed out: " + e.getMessage());
                        promise.setError(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        promise.setError(e);
                    }
                });
            }

            @Override
            public void onPermissionDenied() {
                promise.setError(new PermissionDeniedException());
            }
        });

        return promise;
    }

    public void getCurrentLocation(final int timeoutMs, final long maximumAgeMs, final boolean enableHighAccuracy, @NonNull final com.facebook.react.bridge.Promise bridgePromise) {
        if (!hasRequiredPermissions()) {
            bridgePromise.reject("PERMISSION_DENIED", "Required location deployment permissions are absent.");
            return;
        }

        mWorkerExecutor.execute(() -> {
            try {
                Location location = getCurrentLocationNoCheck(timeoutMs, maximumAgeMs, enableHighAccuracy);
                if (location != null) {
                    bridgePromise.resolve(location);
                } else {
                    bridgePromise.reject("LOCATION_NULL", "Hardware returned an empty coordinate set.");
                }
            } catch (TimeoutException e) {
                bridgePromise.reject("TIMEOUT", "Hardware tracking lock duration window timed out.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                bridgePromise.reject("INTERRUPTED", "Execution thread sequence was interrupted.");
            } catch (Exception e) {
                bridgePromise.reject("ERROR", e.getMessage());
            }
        });
    }

    @Nullable
    @SuppressLint("MissingPermission")
    public Location getCurrentLocationNoCheck(int timeoutMs, long maximumAgeMs, boolean enableHighAccuracy) throws InterruptedException, TimeoutException {
        final long minLocationTimeThreshold = System.currentTimeMillis() - maximumAgeMs;
        final Location[] locationWrapper = new Location[1];

        // 1. Synchronously look up cached positions across background thread pools
        try {
            Task<Location> lastLocationTask = mFusedClient.getLastLocation();
            Location cachedLoc = Tasks.await(lastLocationTask, 500, TimeUnit.MILLISECONDS);
            if (cachedLoc != null && cachedLoc.getTime() >= minLocationTimeThreshold) {
                Log.d(TAG, "Acquired clean coordinates using Google's local location cache.");
                return cachedLoc;
            }
        } catch (Exception e) {
            Log.d(TAG, "Cached database validation skipped, polling live system sensors.");
        }

        // 2. Setup live hardware tracking loop using Looper.getMainLooper()
        final CountDownLatch liveLatch = new CountDownLatch(1);

        LocationRequest locationRequest = new LocationRequest.Builder(
                enableHighAccuracy ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000)
                .setMaxUpdates(1)
                .setDurationMillis(timeoutMs)
                .build();

        LocationCallback callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location lastLocation = locationResult.getLastLocation();
                if (lastLocation != null) {
                    locationWrapper[0] = lastLocation;
                    Log.d(TAG, "Live hardware sensor tracking location locked: " + lastLocation);
                }
                liveLatch.countDown();
            }
        };

        // CRITICAL REFACTOR: Route through Looper.getMainLooper() instead of the worker thread pool
        // This ensures compatibility with Android Emulators and mock location injection layers
        mFusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper());

        boolean reachedTarget = liveLatch.await(timeoutMs, TimeUnit.MILLISECONDS);

        // Remove updates from the main thread loop
        mFusedClient.removeLocationUpdates(callback);

        if (!reachedTarget || locationWrapper[0] == null) {
            throw new TimeoutException("Fused hardware location lock failed within assigned deadline bounds.");
        }

        return locationWrapper[0];
    }

    public boolean hasRequiredPermissions() {
        int finePermission = ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarsePermission = ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION);

        boolean baseGranted = finePermission == PackageManager.PERMISSION_GRANTED ||
                coarsePermission == PackageManager.PERMISSION_GRANTED;

        if (baseGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int backgroundPermission = ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            Log.d(TAG, "Evaluating modern background permission allocation profile status: " + backgroundPermission);
        }

        return baseGranted;
    }
}