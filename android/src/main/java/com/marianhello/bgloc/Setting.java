package com.marianhello.bgloc;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.Nullable;

import com.marianhello.bgloc.data.AbstractLocationTemplate;
import com.marianhello.bgloc.data.LocationTemplate;
import com.marianhello.bgloc.data.LocationTemplateFactory;
import com.marianhello.utils.CloneHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Setting class
 */
public class Setting implements Parcelable
{
    public static final String BUNDLE_KEY = "setting";

    private Boolean start;
    private Integer updatedAt; //milliseconds

    public Setting () {
    }

    // Copy constructor
    public Setting(Setting setting) {
        this.start = setting.start;
        this.updatedAt = setting.updatedAt;
    }

    private Setting(Parcel in) {
        setStarted((Boolean) in.readValue(null));
        setUpdatedAt(in.readInt());
    }

    public static Setting getDefault() {
        Setting setting = new Setting();
        setting.start = false;
        setting.updatedAt = 0;
        return setting;
    }

    public int describeContents() {
        return 0;
    }

    // write your object's data to the passed-in Parcel
    public void writeToParcel(Parcel out, int flags) {
        out.writeValue(isStarted());
        out.writeInt(getUpdatedAt());
    }

    public static final Parcelable.Creator<Setting> CREATOR
            = new Parcelable.Creator<Setting>() {
        public Setting createFromParcel(Parcel in) {
            return new Setting(in);
        }

        public Setting[] newArray(int size) {
            return new Setting[size];
        }
    };

    public boolean hasUpdatedAt() {
        return updatedAt != null;
    }

    public Integer getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(Integer updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean hasStart() {
        return this.start != null;
    }

    public Boolean isStarted() {
        return this.start != null && this.start;
    }

    public void setStarted(Boolean start) {
        this.start = start;
    }
    @Override
    public String toString () {
        return new StringBuffer()
                .append("Setting[start=").append(isStarted())
                .append(" updatedAt=").append(getUpdatedAt())
                .append("]")
                .toString();
    }

    public Parcel toParcel () {
        Parcel parcel = Parcel.obtain();
        this.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        return parcel;
    }

    public Bundle toBundle () {
        Bundle bundle = new Bundle();
        bundle.putParcelable(BUNDLE_KEY, this);
        return bundle;
    }

    public static Setting merge(Setting setting1, Setting setting2) {
        Setting merger = new Setting(setting1);

        if (setting2.hasStart()) {
            merger.setStarted(setting2.isStarted());
        }
        if (setting2.hasUpdatedAt()) {
            merger.setUpdatedAt(setting2.getUpdatedAt());
        }
        return merger;
    }

    public static Config fromByteArray (byte[] byteArray) {
        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(byteArray, 0, byteArray.length);
        parcel.setDataPosition(0);
        return Config.CREATOR.createFromParcel(parcel);
    }
}
