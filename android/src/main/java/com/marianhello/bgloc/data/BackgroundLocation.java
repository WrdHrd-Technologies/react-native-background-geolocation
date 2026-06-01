package com.marianhello.bgloc.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract.LocationEntry;
import com.wrdhrd.bgloc.provider.FusedDistanceFilterLocationProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class BackgroundLocation implements Parcelable {
    private static final String TAG = "BackgroundLocation";

    public static final int DELETED = 0;
    public static final int POST_PENDING = 1;
    public static final int SYNC_PENDING = 2;

    private Long locationId = null;
    private Integer locationProvider = null;
    private Integer batteryLevel = null;
    private Long batchStartMillis = null;
    
    private String provider;
    private double latitude = 0.0;
    private double longitude = 0.0;
    private long time = 0;
    private long realtime = 0;
    private long elapsedRealtimeNanos = 0;
    private float accuracy = 0.0f;
    private float speed = 0.0f;
    private float bearing = 0.0f;
    private double altitude = 0.0f;
    private float radius = 0.0f;
    
    private boolean hasAccuracy = false;
    private boolean hasAltitude = false;
    private boolean hasSpeed = false;
    private boolean hasBearing = false;
    private boolean hasRadius = false;
    private boolean isCharging = false;
    private int mockFlags = 0x0000;
    private int status = POST_PENDING;
    private Bundle extras = null;

    private static final Map<String, PropertyAccessor> ACCESSOR_MAP = new HashMap<>();

    public BackgroundLocation() {
        this.realtime = System.currentTimeMillis();
    }

    public BackgroundLocation(String provider) {
        this.provider = provider;
        this.realtime = System.currentTimeMillis();
    }


    public BackgroundLocation(@NonNull BackgroundLocation l) {
        this.locationId = l.locationId;
        this.locationProvider = l.locationProvider;
        this.batchStartMillis = l.batchStartMillis;
        this.provider = l.provider;
        this.latitude = l.latitude;
        this.longitude = l.longitude;
        this.time = l.time;
        this.realtime = l.realtime;
        this.elapsedRealtimeNanos = l.elapsedRealtimeNanos;
        this.accuracy = l.accuracy;
        this.speed = l.speed;
        this.bearing = l.bearing;
        this.altitude = l.altitude;
        this.radius = l.radius;
        this.hasAccuracy = l.hasAccuracy;
        this.hasAltitude = l.hasAltitude;
        this.hasSpeed = l.hasSpeed;
        this.hasBearing = l.hasBearing;
        this.hasRadius = l.hasRadius;
        this.mockFlags = l.mockFlags;
        this.status = l.status;
        this.batteryLevel = l.batteryLevel;
        this.isCharging = l.isCharging;
        this.extras = (l.extras == null) ? null : new Bundle(l.extras);
    }

    private static BackgroundLocation fromParcel(Parcel in) {
        BackgroundLocation l = new BackgroundLocation();

        l.locationId = in.readByte() == 0 ? null : in.readLong();
        l.locationProvider = in.readByte() == 0 ? null : in.readInt();
        l.batchStartMillis = in.readByte() == 0 ? null : in.readLong();
        
        l.provider = in.readString();
        l.latitude = in.readDouble();
        l.longitude = in.readDouble();
        l.time = in.readLong();
        l.elapsedRealtimeNanos = in.readLong();
        l.accuracy = in.readFloat();
        l.speed = in.readFloat();
        l.bearing = in.readFloat();
        l.altitude = in.readDouble();
        l.radius = in.readFloat();
        
        l.hasAccuracy = in.readInt() != 0;
        l.hasAltitude = in.readInt() != 0;
        l.hasSpeed = in.readInt() != 0;
        l.hasBearing = in.readInt() != 0;
        l.hasRadius = in.readInt() != 0;
        
        l.mockFlags = in.readInt();
        l.status = in.readInt();
        l.batteryLevel = in.readByte() == 0 ? null : in.readInt();
        l.isCharging = in.readInt() != 0;
        l.realtime = in.readLong();
        l.extras = in.readBundle(BackgroundLocation.class.getClassLoader());

        return l;
    }

    @NonNull
    public static BackgroundLocation fromLocation(@NonNull Location location) {
        BackgroundLocation l = new BackgroundLocation();
        l.provider = location.getProvider();
        l.latitude = location.getLatitude();
        l.longitude = location.getLongitude();
        l.time = location.getTime();
        l.realtime = System.currentTimeMillis();
        l.accuracy = location.getAccuracy();
        l.speed = location.getSpeed();
        l.bearing = location.getBearing();
        l.altitude = location.getAltitude();
        l.hasAccuracy = location.hasAccuracy();
        l.hasAltitude = location.hasAltitude();
        l.hasSpeed = location.hasSpeed();
        l.hasBearing = location.hasBearing();
        l.extras = location.getExtras();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            l.elapsedRealtimeNanos = location.getElapsedRealtimeNanos();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            l.setIsFromMockProvider(location.isFromMockProvider());
        }

        return l;
    }

    @NonNull
    public static BackgroundLocation fromCursor(@NonNull Cursor c) {
        BackgroundLocation l = new BackgroundLocation();

        l.setProvider(c.getString(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_PROVIDER)));
        l.setTime(c.getLong(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_TIME)));
        l.setRealTime(c.getLong(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_REALTIME)));
        l.setElapsedRealtimeNanos(c.getLong(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_ELAPSEDREALTIMENANO)));
        
        if (c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_HAS_ACCURACY)) == 1) {
            l.setAccuracy(c.getFloat(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_ACCURACY)));
        }
        if (c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_HAS_SPEED)) == 1) {
            l.setSpeed(c.getFloat(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_SPEED)));
        }
        if (c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_HAS_BEARING)) == 1) {
            l.setBearing(c.getFloat(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_BEARING)));
        }
        if (c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_HAS_ALTITUDE)) == 1) {
            l.setAltitude(c.getDouble(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_ALTITUDE)));
        }
        if (c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_HAS_RADIUS)) == 1) {
            l.setRadius(c.getFloat(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_RADIUS)));
        }
        
        l.setLatitude(c.getDouble(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_LATITUDE)));
        l.setLongitude(c.getDouble(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_LONGITUDE)));
        l.setLocationProvider(c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_LOCATION_PROVIDER)));
        l.setBatchStartMillis(c.getLong(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_BATCH_START_MILLIS)));
        l.setStatus(c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_STATUS)));
        l.setLocationId(c.getLong(c.getColumnIndexOrThrow(LocationEntry._ID)));
        l.setMockFlags(c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_MOCK_FLAGS)));
        
        int btrIdx = c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_BATTERY_LEVEL);
        l.setBatteryLevel(c.isNull(btrIdx) ? null : c.getInt(btrIdx));
        
        l.setIsCharging(c.getInt(c.getColumnIndexOrThrow(LocationEntry.COLUMN_NAME_CHARGING_FLAG)) == 1);

        return l;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (locationId == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(locationId);
        }

        if (locationProvider == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeInt(locationProvider);
        }

        if (batchStartMillis == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(batchStartMillis);
        }

        dest.writeString(provider);
        dest.writeDouble(latitude);
        dest.writeDouble(longitude);
        dest.writeLong(time);
        dest.writeLong(elapsedRealtimeNanos);
        dest.writeFloat(accuracy);
        dest.writeFloat(speed);
        dest.writeFloat(bearing);
        dest.writeDouble(altitude);
        dest.writeFloat(radius);
        
        dest.writeInt(hasAccuracy ? 1 : 0);
        dest.writeInt(hasAltitude ? 1 : 0);
        dest.writeInt(hasSpeed ? 1 : 0);
        dest.writeInt(hasBearing ? 1 : 0);
        dest.writeInt(hasRadius ? 1 : 0);
        
        dest.writeInt(mockFlags);
        dest.writeInt(status);

        if (batteryLevel == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeInt(batteryLevel);
        }

        dest.writeInt(isCharging ? 1 : 0);
        dest.writeLong(realtime);
        dest.writeBundle(extras);
    }

    public static final Parcelable.Creator<BackgroundLocation> CREATOR
            = new Parcelable.Creator<BackgroundLocation>() {
        @Override
        public BackgroundLocation createFromParcel(Parcel in) {
            return BackgroundLocation.fromParcel(in);
        }
        @Override
        public BackgroundLocation[] newArray(int size) {
            return new BackgroundLocation[size];
        }
    };

    public BackgroundLocation makeClone() {
        return new BackgroundLocation(this);
    }

    public Long getLocationId() { return locationId; }
    public void setLocationId(Long locationId) { this.locationId = locationId; }

    public Integer getLocationProvider() { return locationProvider; }
    public void setLocationProvider(Integer locationProvider) { this.locationProvider = locationProvider; }

    public Long getBatchStartMillis() { return batchStartMillis; }
    public void setBatchStartMillis(Long batchStartMillis) { this.batchStartMillis = batchStartMillis; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }

    public long getRealTime() { return realtime; }
    public void setRealTime(long realtime) { this.realtime = realtime; }

    public long getElapsedRealtimeNanos() { return elapsedRealtimeNanos; }
    public void setElapsedRealtimeNanos(long elapsedRealtimeNanos) { this.elapsedRealtimeNanos = elapsedRealtimeNanos; }

    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float accuracy) { this.accuracy = accuracy; this.hasAccuracy = true; }

    public float getSpeed() { return speed; }
    public void setSpeed(float speed) { this.speed = speed; this.hasSpeed = true; }

    public float getBearing() { return bearing; }
    public void setBearing(float bearing) { this.bearing = bearing; this.hasBearing = true; }

    public double getAltitude() { return altitude; }
    public void setAltitude(double altitude) { this.altitude = altitude; this.hasAltitude = true; }

    public float getRadius() { return radius; }
    public void setRadius(float radius) { this.radius = radius; this.hasRadius = true; }

    public boolean hasAccuracy() { return hasAccuracy; }
    public boolean hasAltitude() { return hasAltitude; }
    public boolean hasSpeed() { return hasSpeed; }
    public boolean hasBearing() { return hasBearing; }
    public boolean hasRadius() { return hasRadius; }

    public int getMockFlags() { return mockFlags; }
    public void setMockFlags(int mockFlags) { this.mockFlags = mockFlags; }

    public boolean hasIsFromMockProvider() { return ((mockFlags & 0x0002) >> 1) == 1; }
    public boolean isFromMockProvider() { return (mockFlags & 0x0001) == 1; }
    public void setIsFromMockProvider(boolean isFromMockProvider) { mockFlags |= isFromMockProvider ? 0x0003 : 0x0002; }

    public boolean hasMockLocationsEnabled() { return ((mockFlags & 0x0008) >> 3) == 1; }
    public boolean areMockLocationsEnabled() { return ((mockFlags & 0x0004) >> 2) == 1; }
    public void setMockLocationsEnabled(Boolean mockLocationsEnabled) { mockFlags |= mockLocationsEnabled ? 0x000C : 0x0008; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public Bundle getExtras() { return extras; }
    public void setExtras(Bundle extras) { this.extras = extras; }

    public Integer getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(Integer batteryLevel) { this.batteryLevel = batteryLevel; }

    public boolean getIsCharging() { return isCharging; }
    public void setIsCharging(boolean isCharging) { this.isCharging = isCharging; }

    @NonNull
    public Location getLocation() {
        Location l = new Location(provider);
        l.setLatitude(latitude);
        l.setLongitude(longitude);
        l.setTime(time);
        if (hasAccuracy) l.setAccuracy(accuracy);
        if (hasAltitude) l.setAltitude(altitude);
        if (hasSpeed) l.setSpeed(speed);
        if (hasBearing) l.setBearing(bearing);
        l.setExtras(extras);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            l.setElapsedRealtimeNanos(elapsedRealtimeNanos);
        }
        return l;
    }

    @NonNull
    @Override
    public String toString() {
        return "BGLocation[" + provider + String.format(" %.6f,%.6f", latitude, longitude) + " id=" + locationId + "]";
    }

    @NonNull
    public JSONObject toJSONObject() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("provider", provider);
        json.put("locationProvider", locationProvider);
        json.put("time", time);
        json.put("latitude", latitude);
        json.put("longitude", longitude);
        if (hasAccuracy) json.put("accuracy", accuracy);
        if (hasSpeed) json.put("speed", speed);
        if (hasAltitude) json.put("altitude", altitude);
        if (hasBearing) json.put("bearing", bearing);
        if (hasRadius) json.put("radius", radius);
        if (hasIsFromMockProvider()) json.put("isFromMockProvider", isFromMockProvider());
        if (hasMockLocationsEnabled()) json.put("mockLocationsEnabled", areMockLocationsEnabled());
        json.put("batteryLevel", batteryLevel);
        json.put("isCharging", isCharging);
        json.put("realtime", realtime);
        return json;
    }

    @NonNull
    public JSONObject toJSONObjectWithId() throws JSONException {
        JSONObject json = toJSONObject();
        json.put("id", locationId);
        return json;
    }

    @NonNull
    public ContentValues toContentValues() {
        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_NAME_TIME, time);
        values.put(LocationEntry.COLUMN_NAME_ACCURACY, accuracy);
        values.put(LocationEntry.COLUMN_NAME_SPEED, speed);
        values.put(LocationEntry.COLUMN_NAME_BEARING, bearing);
        values.put(LocationEntry.COLUMN_NAME_ALTITUDE, altitude);
        values.put(LocationEntry.COLUMN_NAME_LATITUDE, latitude);
        values.put(LocationEntry.COLUMN_NAME_LONGITUDE, longitude);
        values.put(LocationEntry.COLUMN_NAME_RADIUS, radius);
        values.put(LocationEntry.COLUMN_NAME_HAS_ACCURACY, hasAccuracy);
        values.put(LocationEntry.COLUMN_NAME_HAS_SPEED, hasSpeed);
        values.put(LocationEntry.COLUMN_NAME_HAS_BEARING, hasBearing);
        values.put(LocationEntry.COLUMN_NAME_HAS_ALTITUDE, hasAltitude);
        values.put(LocationEntry.COLUMN_NAME_HAS_RADIUS, hasRadius);
        values.put(LocationEntry.COLUMN_NAME_PROVIDER, provider);
        values.put(LocationEntry.COLUMN_NAME_LOCATION_PROVIDER, locationProvider);
        values.put(LocationEntry.COLUMN_NAME_STATUS, status);
        values.put(LocationEntry.COLUMN_NAME_BATCH_START_MILLIS, batchStartMillis);
        values.put(LocationEntry.COLUMN_NAME_MOCK_FLAGS, mockFlags);
        values.put(LocationEntry.COLUMN_NAME_BATTERY_LEVEL, batteryLevel);
        values.put(LocationEntry.COLUMN_NAME_CHARGING_FLAG, isCharging);
        values.put(LocationEntry.COLUMN_NAME_REALTIME, realtime);
        values.put(LocationEntry.COLUMN_NAME_ELAPSEDREALTIMENANO, elapsedRealtimeNanos);
        return values;
    }

    private interface PropertyAccessor {
        Object get(BackgroundLocation loc);
    }

    static {
        ACCESSOR_MAP.put("@id", loc -> loc.locationId);
        ACCESSOR_MAP.put("@provider", loc -> loc.provider);
        ACCESSOR_MAP.put("@locationProvider", loc -> loc.locationProvider);
        ACCESSOR_MAP.put("@time", loc -> loc.time);
        ACCESSOR_MAP.put("@realtime", loc -> loc.realtime);
        ACCESSOR_MAP.put("@elapsedrealtimenano", loc -> loc.elapsedRealtimeNanos);
        ACCESSOR_MAP.put("@latitude", loc -> loc.latitude);
        ACCESSOR_MAP.put("@longitude", loc -> loc.longitude);
        ACCESSOR_MAP.put("@accuracy", loc -> loc.hasAccuracy ? loc.accuracy : JSONObject.NULL);
        ACCESSOR_MAP.put("@speed", loc -> loc.hasSpeed ? loc.speed : JSONObject.NULL);
        ACCESSOR_MAP.put("@altitude", loc -> loc.hasAltitude ? loc.altitude : JSONObject.NULL);
        ACCESSOR_MAP.put("@bearing", loc -> loc.hasBearing ? loc.bearing : JSONObject.NULL);
        ACCESSOR_MAP.put("@radius", loc -> loc.hasRadius ? loc.radius : JSONObject.NULL);
        ACCESSOR_MAP.put("@isFromMockProvider", loc -> loc.hasIsFromMockProvider() ? loc.isFromMockProvider() : JSONObject.NULL);
        ACCESSOR_MAP.put("@mockLocationsEnabled", loc -> loc.hasMockLocationsEnabled() ? loc.areMockLocationsEnabled() : JSONObject.NULL);
        ACCESSOR_MAP.put("@batteryLevel", loc -> loc.batteryLevel);
        ACCESSOR_MAP.put("@isCharging", loc -> loc.isCharging);
    }

    @Nullable
    public Object getValueForKey(@NonNull String key) {
        PropertyAccessor accessor = ACCESSOR_MAP.get(key);
        return accessor != null ? accessor.get(this) : null;
    }
}