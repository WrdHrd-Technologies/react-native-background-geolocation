
package com.marianhello.bgloc.data;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.DetectedActivity;

import org.json.JSONException;
import org.json.JSONObject;

public class BackgroundActivity implements Parcelable {
    private int confidence;
    private int type;
    private int locationProvider; 

    public BackgroundActivity(@Nullable Integer locationProvider, @NonNull DetectedActivity activity) {
        this.confidence = activity.getConfidence();
        this.type = activity.getType();
        this.locationProvider = locationProvider != null ? locationProvider : -1;
    }


    public BackgroundActivity(int confidence, int type, int locationProvider) {
        this.confidence = confidence;
        this.type = type;
        this.locationProvider = locationProvider;
    }

    private BackgroundActivity(Parcel in) {
        confidence = in.readInt();
        type = in.readInt();
        locationProvider = in.readInt();
    }


    public JSONObject toJSONObject() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("confidence", confidence);
        json.put("type", getActivityString(type));
        json.put("locationProvider", locationProvider);
        return json;
    }

    @NonNull
    public static String getActivityString(int detectedActivityType) {
        switch (detectedActivityType) {
            case DetectedActivity.IN_VEHICLE:
                return "IN_VEHICLE";
            case DetectedActivity.ON_BICYCLE:
                return "ON_BICYCLE";
            case DetectedActivity.ON_FOOT:
                return "ON_FOOT";
            case DetectedActivity.RUNNING:
                return "RUNNING";
            case DetectedActivity.STILL:
                return "STILL";
            case DetectedActivity.TILTING:
                return "TILTING";
            case DetectedActivity.UNKNOWN:
                return "UNKNOWN";
            case DetectedActivity.WALKING:
                return "WALKING";
            default:
                return "UNKNOWN";
        }
    }

    public int getConfidence() {
        return confidence;
    }

    public void setConfidence(int confidence) {
        this.confidence = confidence;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getLocationProvider() {
        return locationProvider;
    }

    public void setLocationProvider(int locationProvider) {
        this.locationProvider = locationProvider;
    }

    public static final Parcelable.Creator<BackgroundActivity> CREATOR
            = new Parcelable.Creator<BackgroundActivity>() {
        @Override
        public BackgroundActivity createFromParcel(Parcel in) {
            return new BackgroundActivity(in);
        }
        @Override
        public BackgroundActivity[] newArray(int size) {
            return new BackgroundActivity[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(confidence);
        dest.writeInt(type);
        dest.writeInt(locationProvider); 
    }

    @NonNull
    @Override
    public String toString() {
        return "BackgroundActivity[confidence=" + confidence +
                " type=" + getActivityString(type) +
                " locationProvider=" + locationProvider +
                "]";
    }
}