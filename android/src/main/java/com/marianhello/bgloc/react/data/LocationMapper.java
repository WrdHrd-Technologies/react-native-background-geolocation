package com.marianhello.bgloc.react.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.marianhello.bgloc.data.BackgroundLocation;

public final class LocationMapper {
    private LocationMapper() {
        throw new UnsupportedOperationException("Bridge transformation layers cannot be instantiated.");
    }

    @NonNull
    public static WritableMap toWriteableMap(@Nullable BackgroundLocation location) {
        WritableMap out = Arguments.createMap();
        if (location == null) return out;

        out.putString("provider", location.getProvider());
        
        Integer locationProvider = location.getLocationProvider();
        if (locationProvider != null) {
            out.putInt("locationProvider", locationProvider);
        }

        out.putDouble("time", (double) location.getTime());
        out.putDouble("realtime", (double) location.getRealTime());

        out.putDouble("elapsedrealtime", (double) location.getElapsedRealtimeNanos() / 1000000.0);

        out.putDouble("latitude", location.getLatitude());
        out.putDouble("longitude", location.getLongitude());

        if (location.hasAccuracy()) out.putDouble("accuracy", (double) location.getAccuracy());
        if (location.hasSpeed()) out.putDouble("speed", (double) location.getSpeed());
        if (location.hasAltitude()) out.putDouble("altitude", location.getAltitude());
        if (location.hasBearing()) out.putDouble("bearing", (double) location.getBearing());
        if (location.hasRadius()) out.putDouble("radius", (double) location.getRadius());
        
        if (location.hasIsFromMockProvider()) {
            out.putBoolean("isFromMockProvider", location.isFromMockProvider());
        }
        if (location.hasMockLocationsEnabled()) {
            out.putBoolean("mockLocationsEnabled", location.areMockLocationsEnabled());
        }

        return out;
    }

    @NonNull
    public static WritableMap toWriteableMapWithId(@Nullable BackgroundLocation location) {
        WritableMap out = toWriteableMap(location);
        if (location == null) return out;

        Long locationId = location.getLocationId();
        if (locationId != null) {
            out.putDouble("id", locationId.doubleValue());
        }

        return out;
    }
}