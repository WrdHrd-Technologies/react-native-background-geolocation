package com.marianhello.bgloc.headless;

import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;

import com.marianhello.bgloc.data.BackgroundLocation;
import org.json.JSONException;


public abstract class LocationTask extends Task {
    private static final String TAG = "LocationTask";
    private final BackgroundLocation mLocation;

    public LocationTask(@NonNull BackgroundLocation location) {
        this.mLocation = location;
    }

    @Override
    public String getName() {
        return "location";
    }

    @Override
    public Bundle getBundle() {
        Bundle bundle = new Bundle();
        Bundle params = new Bundle();

        params.putString("provider", mLocation.getProvider());
        params.putInt("locationProvider", mLocation.getLocationProvider());
        params.putLong("time", mLocation.getTime());
        params.putDouble("latitude", mLocation.getLatitude());
        params.putDouble("longitude", mLocation.getLongitude());
        
        if (mLocation.hasAccuracy()) params.putFloat("accuracy", mLocation.getAccuracy());
        if (mLocation.hasSpeed()) params.putFloat("speed", mLocation.getSpeed());
        if (mLocation.hasAltitude()) params.putDouble("altitude", mLocation.getAltitude());
        if (mLocation.hasBearing()) params.putFloat("bearing", mLocation.getBearing());
        if (mLocation.hasRadius()) params.putFloat("radius", mLocation.getRadius());
        if (mLocation.hasIsFromMockProvider()) params.putBoolean("isFromMockProvider", mLocation.isFromMockProvider());
        if (mLocation.hasMockLocationsEnabled()) params.putBoolean("mockLocationsEnabled", mLocation.areMockLocationsEnabled());

        bundle.putString("name", getName());
        bundle.putBundle("params", params);

        return bundle;
    }

    @NonNull
    @Override
    public String toString() {
        try {
            if (mLocation.toJSONObject() != null) {
                return mLocation.toJSONObject().toString();
            }
        } catch (JSONException e) {
            onError("Fatal tracking point serialization failure: " + e.getMessage());
            Log.e(TAG, "Failed to stringify current location instance to JSON representation.", e);
        }

        return "{\"name\":\"" + getName() + "\",\"error\":\"Serialization failed\",\"lat\":" 
                + mLocation.getLatitude() + ",\"lng\":" + mLocation.getLongitude() + ",\"time\":" 
                + mLocation.getTime() + "}";
    }
}