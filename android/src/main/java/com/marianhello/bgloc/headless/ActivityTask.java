package com.marianhello.bgloc.headless;

import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;

import com.marianhello.bgloc.data.BackgroundActivity;
import org.json.JSONException;

public abstract class ActivityTask extends Task {
    private static final String TAG = "ActivityTask";
    private final BackgroundActivity mActivity;

    public ActivityTask(@NonNull BackgroundActivity activity) {
        this.mActivity = activity;
    }

    @Override
    public String getName() {
        return "activity";
    }

    @Override
    public Bundle getBundle() {
        Bundle bundle = new Bundle();
        Bundle params = new Bundle();

        params.putInt("confidence", mActivity.getConfidence());
        params.putString("type", BackgroundActivity.getActivityString(mActivity.getType()));

        bundle.putString("name", getName());
        bundle.putBundle("params", params);
        return bundle;
    }

   
    @NonNull
    @Override
    public String toString() {
        try {
            if (mActivity.toJSONObject() != null) {
                return mActivity.toJSONObject().toString();
            }
        } catch (JSONException e) {
            onError("Fatal serialization mismatch inside headless task encoder: " + e.getMessage());
            Log.e(TAG, "Failed to compile Activity record to raw JSON string representation.", e);
        }

        return "{\"name\":\"" + getName() + "\",\"error\":\"Serialization failed\",\"type\":\"" 
                + BackgroundActivity.getActivityString(mActivity.getType()) + "\"}";
    }
}