package com.marianhello.bgloc.headless;

import android.os.Bundle;
import androidx.annotation.NonNull;

import com.marianhello.bgloc.data.BackgroundLocation;

public abstract class StationaryTask extends LocationTask {

    public StationaryTask(@NonNull BackgroundLocation location) {
        super(location);
    }

    @Override
    public String getName() {
        return "stationary";
    }

    @Override
    public Bundle getBundle() {
        Bundle bundle = super.getBundle();
        if (bundle == null) {
            bundle = new Bundle();
        }

        Bundle params = bundle.getBundle("params");
        if (params == null) {
            params = new Bundle();
        }

        params.putBoolean("is_stationary_heartbeat", true);
        params.putInt("device_movement_state", 0); 

        bundle.putBundle("params", params);

        return bundle;
    }
}