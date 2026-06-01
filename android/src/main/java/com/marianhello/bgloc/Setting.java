package com.marianhello.bgloc;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serializable;

public final class Setting implements Parcelable, Serializable {
    public static final String BUNDLE_KEY = "setting";
    private static final long serialVersionUID = 88L;

    private Boolean start;
    
    private Long updatedAt;

    public Setting() {
    }

    public Setting(@NonNull Setting setting) {
        this.start = setting.start;
        this.updatedAt = setting.updatedAt;
    }

    private Setting(Parcel in) {
        if (in.readByte() == 1) {
            this.start = in.readByte() == 1;
        } else {
            this.start = null;
        }

        if (in.readByte() == 1) {
            this.updatedAt = in.readLong();
        } else {
            this.updatedAt = null;
        }
    }

    @NonNull
    public static Setting getDefault() {
        Setting setting = new Setting();
        setting.start = false;
        setting.updatedAt = 0L;
        return setting;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        if (start == null) {
            out.writeByte((byte) 0);
        } else {
            out.writeByte((byte) 1);
            out.writeByte((byte) (start ? 1 : 0));
        }

        if (updatedAt == null) {
            out.writeByte((byte) 0);
        } else {
            out.writeByte((byte) 1);
            out.writeLong(updatedAt);
        }
    }

    public static final Parcelable.Creator<Setting> CREATOR = new Parcelable.Creator<Setting>() {
        @Override
        public Setting createFromParcel(Parcel in) {
            return new Setting(in);
        }

        @Override
        public Setting[] newArray(int size) {
            return new Setting[size];
        }
    };

    public boolean hasUpdatedAt() {
        return updatedAt != null;
    }

    @Nullable
    public Long getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(@Nullable Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean hasStart() {
        return this.start != null;
    }

    @NonNull
    public Boolean isStarted() {
        return this.start != null && this.start;
    }

    public void setStarted(@Nullable Boolean start) {
        this.start = start;
    }

    @NonNull
    @Override
    public String toString() {
        return "Setting[start=" + isStarted() + " updatedAt=" + updatedAt + "]";
    }

    @NonNull
    public Parcel toParcel() {
        Parcel parcel = Parcel.obtain();
        this.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        return parcel;
    }

    @NonNull
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(BUNDLE_KEY, this);
        return bundle;
    }

    @NonNull
    public static Setting merge(@NonNull Setting setting1, @NonNull Setting setting2) {
        Setting merger = new Setting(setting1);

        if (setting2.hasStart()) {
            merger.setStarted(setting2.isStarted());
        }
        if (setting2.hasUpdatedAt()) {
            merger.setUpdatedAt(setting2.getUpdatedAt());
        }
        return merger;
    }

    @NonNull
    public static Setting fromByteArray(@NonNull byte[] byteArray) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(byteArray, 0, byteArray.length);
            parcel.setDataPosition(0);
            return Setting.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle(); 
        }
    }
}