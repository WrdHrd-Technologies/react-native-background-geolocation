package com.marianhello.bgloc;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.marianhello.bgloc.data.AbstractLocationTemplate;
import com.marianhello.bgloc.data.LocationTemplate;
import com.marianhello.bgloc.data.LocationTemplateFactory;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;


public final class Config implements Parcelable {
    public static final String BUNDLE_KEY = "config";

    public static final int DISTANCE_FILTER_PROVIDER = 0;
    public static final int ACTIVITY_PROVIDER = 1;
    public static final int RAW_PROVIDER = 2;
    public static final int FUSED_PROVIDER = 3;
    public static final int FUSED_DISTANCE_FILTER_PROVIDER = 4;

    public static final String NullString = new String("");

    private Float stationaryRadius;
    private Integer distanceFilter;
    private Integer desiredAccuracy;
    private Boolean debug;
    private String notificationTitle;
    private String notificationText;
    private String notificationIconLarge;
    private String notificationIconSmall;
    private String notificationIconColor;
    private Integer locationProvider;
    private Integer interval;
    private Integer fastestInterval;
    private Integer activitiesInterval;
    private Integer heartbeatInterval;
    private Boolean stopOnTerminate;
    private Boolean startOnBoot;
    private Boolean startForeground;
    private Boolean notificationsEnabled;
    private Boolean stopOnStillActivity;
    private String url;
    private String syncUrl;
    private Integer syncThreshold;

    private Map<String, String> httpHeaders;
    private Integer maxLocations;
    private LocationTemplate template;

    public Config() {
        this.httpHeaders = null;
    }

    public Config(@NonNull Config config) {
        this.stationaryRadius = config.stationaryRadius;
        this.distanceFilter = config.distanceFilter;
        this.desiredAccuracy = config.desiredAccuracy;
        this.debug = config.debug;
        this.notificationTitle = config.notificationTitle;
        this.notificationText = config.notificationText;
        this.notificationIconLarge = config.notificationIconLarge;
        this.notificationIconSmall = config.notificationIconSmall;
        this.notificationIconColor = config.notificationIconColor;
        this.locationProvider = config.locationProvider;
        this.interval = config.interval;
        this.fastestInterval = config.fastestInterval;
        this.activitiesInterval = config.activitiesInterval;
        this.heartbeatInterval = config.heartbeatInterval;
        this.stopOnTerminate = config.stopOnTerminate;
        this.startOnBoot = config.startOnBoot;
        this.startForeground = config.startForeground;
        this.notificationsEnabled = config.notificationsEnabled;
        this.stopOnStillActivity = config.stopOnStillActivity;
        this.url = config.url;
        this.syncUrl = config.syncUrl;
        this.syncThreshold = config.syncThreshold;
        this.maxLocations = config.maxLocations;

        this.httpHeaders = new HashMap<>();
        if (config.httpHeaders != null) {
            this.httpHeaders.putAll(config.httpHeaders);
        }

        if (config.template instanceof AbstractLocationTemplate) {
            this.template = ((AbstractLocationTemplate) config.template).clone();
        }
        else{
            this.template = config.template;
        }
    }

    private Config(Parcel in) {
        if (in.readByte() == 1) stationaryRadius = in.readFloat();
        if (in.readByte() == 1) distanceFilter = in.readInt();
        if (in.readByte() == 1) desiredAccuracy = in.readInt();
        if (in.readByte() == 1) debug = in.readByte() == 1;

        notificationTitle = in.readString();
        notificationText = in.readString();
        notificationIconLarge = in.readString();
        notificationIconSmall = in.readString();
        notificationIconColor = in.readString();

        if (in.readByte() == 1) stopOnTerminate = in.readByte() == 1;
        if (in.readByte() == 1) startOnBoot = in.readByte() == 1;
        if (in.readByte() == 1) startForeground = in.readByte() == 1;
        if (in.readByte() == 1) notificationsEnabled = in.readByte() == 1;
        if (in.readByte() == 1) locationProvider = in.readInt();
        if (in.readByte() == 1) interval = in.readInt();
        if (in.readByte() == 1) fastestInterval = in.readInt();
        if (in.readByte() == 1) activitiesInterval = in.readInt();
        if (in.readByte() == 1) heartbeatInterval = in.readInt();
        if (in.readByte() == 1) stopOnStillActivity = in.readByte() == 1;

        url = in.readString();
        syncUrl = in.readString();

        if (in.readByte() == 1) syncThreshold = in.readInt();
        if (in.readByte() == 1) maxLocations = in.readInt();

        this.httpHeaders = new HashMap<>();
        Bundle bundle = in.readBundle(Config.class.getClassLoader());
        if (bundle != null) {
            Serializable serializedHeaders = bundle.getSerializable("httpHeaders");
            if (serializedHeaders instanceof Map) {
                Map<?, ?> rawMap = (Map<?, ?>) serializedHeaders;
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    this.httpHeaders.put(
                            String.valueOf(entry.getKey()),
                            String.valueOf(entry.getValue())
                    );
                }
            }

            Serializable serializedTemplate = bundle.getSerializable(AbstractLocationTemplate.BUNDLE_KEY);
            if (serializedTemplate instanceof LocationTemplate) {
                this.template = (LocationTemplate) serializedTemplate;
            }
        }
    }

    @NonNull
    public static Config getDefault() {
        Config config = new Config();
        config.stationaryRadius = 50f;
        config.distanceFilter = 500;
        config.desiredAccuracy = 100;
        config.debug = false;
        config.notificationTitle = "Background tracking";
        config.notificationText = "ENABLED";
        config.notificationIconLarge = "";
        config.notificationIconSmall = "";
        config.notificationIconColor = "";
        config.locationProvider = DISTANCE_FILTER_PROVIDER;
        config.interval = 600000;
        config.fastestInterval = 120000;
        config.activitiesInterval = 10000;
        config.heartbeatInterval = 5 * 60 * 1000;
        config.stopOnTerminate = true;
        config.startOnBoot = false;
        config.startForeground = true;
        config.notificationsEnabled = true;
        config.stopOnStillActivity = true;
        config.url = "";
        config.syncUrl = "";
        config.syncThreshold = 100;
        config.maxLocations = 10000;
        config.template = null;
        return config;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        if (stationaryRadius == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeFloat(stationaryRadius); }
        if (distanceFilter == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(distanceFilter); }
        if (desiredAccuracy == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(desiredAccuracy); }
        if (debug == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeByte((byte) (debug ? 1 : 0)); }

        out.writeString(notificationTitle);
        out.writeString(notificationText);
        out.writeString(notificationIconLarge);
        out.writeString(notificationIconSmall);
        out.writeString(notificationIconColor);

        if (stopOnTerminate == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeByte((byte) (stopOnTerminate ? 1 : 0)); }
        if (startOnBoot == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeByte((byte) (startOnBoot ? 1 : 0)); }
        if (startForeground == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeByte((byte) (startForeground ? 1 : 0)); }
        if (notificationsEnabled == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeByte((byte) (notificationsEnabled ? 1 : 0)); }
        if (locationProvider == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(locationProvider); }
        if (interval == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(interval); }
        if (fastestInterval == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(fastestInterval); }
        if (activitiesInterval == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(activitiesInterval); }
        if (heartbeatInterval == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(heartbeatInterval); }
        if (stopOnStillActivity == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeByte((byte) (stopOnStillActivity ? 1 : 0)); }

        out.writeString(url);
        out.writeString(syncUrl);

        if (syncThreshold == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(syncThreshold); }
        if (maxLocations == null) { out.writeByte((byte) 0); } else { out.writeByte((byte) 1); out.writeInt(maxLocations); }

        Bundle bundle = new Bundle();
        bundle.putSerializable("httpHeaders", (Serializable) httpHeaders);
        if (template instanceof Serializable) {
            bundle.putSerializable(AbstractLocationTemplate.BUNDLE_KEY, (Serializable) template);
        }
        out.writeBundle(bundle);
    }

    public static final Parcelable.Creator<Config> CREATOR = new Parcelable.Creator<Config>() {
        @Override
        public Config createFromParcel(Parcel in) {
            return new Config(in);
        }
        @Override
        public Config[] newArray(int size) {
            return new Config[size];
        }
    };

    public boolean hasStationaryRadius() { return stationaryRadius != null; }
    @Nullable public Float getStationaryRadius() { return stationaryRadius; }
    public void setStationaryRadius(float stationaryRadius) { this.stationaryRadius = stationaryRadius; }
    public void setStationaryRadius(double stationaryRadius) { this.stationaryRadius = (float) stationaryRadius; }

    public boolean hasDesiredAccuracy() { return desiredAccuracy != null; }
    @Nullable public Integer getDesiredAccuracy() { return desiredAccuracy; }
    public void setDesiredAccuracy(@Nullable Integer desiredAccuracy) { this.desiredAccuracy = desiredAccuracy; }

    public boolean hasDistanceFilter() { return distanceFilter != null; }
    @Nullable public Integer getDistanceFilter() { return distanceFilter; }
    public void setDistanceFilter(@Nullable Integer distanceFilter) { this.distanceFilter = distanceFilter; }

    public boolean hasDebug() { return debug != null; }
    @NonNull public Boolean isDebugging() { return debug != null && debug; }
    public void setDebugging(@Nullable Boolean debug) { this.debug = debug; }

    public boolean hasNotificationIconColor() { return notificationIconColor != null && !notificationIconColor.isEmpty(); }
    @Nullable public String getNotificationIconColor() { return notificationIconColor; }
    public void setNotificationIconColor(@Nullable String notificationIconColor) { this.notificationIconColor = notificationIconColor; }

    public boolean hasNotificationTitle() { return notificationTitle != null; }
    @Nullable public String getNotificationTitle() { return notificationTitle; }
    public void setNotificationTitle(@Nullable String notificationTitle) { this.notificationTitle = notificationTitle; }

    public boolean hasNotificationText() { return notificationText != null; }
    @Nullable public String getNotificationText() { return notificationText; }
    public void setNotificationText(@Nullable String notificationText) { this.notificationText = notificationText; }

    public boolean hasLargeNotificationIcon() { return notificationIconLarge != null && !notificationIconLarge.isEmpty(); }
    @Nullable public String getLargeNotificationIcon() { return notificationIconLarge; }
    public void setLargeNotificationIcon(@Nullable String icon) { this.notificationIconLarge = icon; }

    public boolean hasSmallNotificationIcon() { return notificationIconSmall != null && !notificationIconSmall.isEmpty(); }
    @Nullable public String getSmallNotificationIcon() { return notificationIconSmall; }
    public void setSmallNotificationIcon(@Nullable String icon) { this.notificationIconSmall = icon; }

    public boolean hasStopOnTerminate() { return stopOnTerminate != null; }
    @Nullable public Boolean getStopOnTerminate() { return stopOnTerminate; }
    public void setStopOnTerminate(@Nullable Boolean stopOnTerminate) { this.stopOnTerminate = stopOnTerminate; }

    public boolean hasStartOnBoot() { return startOnBoot != null; }
    @Nullable public Boolean getStartOnBoot() { return startOnBoot; }
    public void setStartOnBoot(@Nullable Boolean startOnBoot) { this.startOnBoot = startOnBoot; }

    public boolean hasStartForeground() { return startForeground != null; }
    @Nullable public Boolean getStartForeground() { return startForeground; }
    public void setStartForeground(@Nullable Boolean startForeground) { this.startForeground = startForeground; }

    public boolean hasNotificationsEnabled() { return notificationsEnabled != null; }
    @Nullable public Boolean getNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(@Nullable Boolean notificationsEnabled) { this.notificationsEnabled = notificationsEnabled; }

    public boolean hasLocationProvider() { return locationProvider != null; }
    @Nullable public Integer getLocationProvider() { return locationProvider; }
    public void setLocationProvider(@Nullable Integer locationProvider) { this.locationProvider = locationProvider; }

    public boolean hasInterval() { return interval != null; }
    @Nullable public Integer getInterval() { return interval; }
    public void setInterval(@Nullable Integer interval) { this.interval = interval; }

    public boolean hasFastestInterval() { return fastestInterval != null; }
    @Nullable public Integer getFastestInterval() { return fastestInterval; }
    public void setFastestInterval(@Nullable Integer fastestInterval) { this.fastestInterval = fastestInterval; }

    public boolean hasActivitiesInterval() { return activitiesInterval != null; }
    @Nullable public Integer getActivitiesInterval() { return activitiesInterval; }
    public void setActivitiesInterval(@Nullable Integer activitiesInterval) { this.activitiesInterval = activitiesInterval; }

    public boolean hasHeartbeatInterval() { return heartbeatInterval != null; }
    @Nullable public Integer getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(@Nullable Integer heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }

    public boolean hasStopOnStillActivity() { return stopOnStillActivity != null; }
    @Nullable public Boolean getStopOnStillActivity() { return stopOnStillActivity; }
    public void setStopOnStillActivity(@Nullable Boolean stopOnStillActivity) { this.stopOnStillActivity = stopOnStillActivity; }

    public boolean hasUrl() { return url != null; }
    public boolean hasValidUrl() { return url != null && !url.isEmpty(); }
    @Nullable public String getUrl() { return url; }
    public void setUrl(@Nullable String url) { this.url = url; }

    public boolean hasSyncUrl() { return syncUrl != null; }
    public boolean hasValidSyncUrl() { return syncUrl != null && !syncUrl.isEmpty(); }
    @Nullable public String getSyncUrl() { return syncUrl; }
    public void setSyncUrl(@Nullable String syncUrl) { this.syncUrl = syncUrl; }

    public boolean hasSyncThreshold() { return syncThreshold != null; }
    @Nullable public Integer getSyncThreshold() { return syncThreshold; }
    public void setSyncThreshold(@Nullable Integer syncThreshold) { this.syncThreshold = syncThreshold; }

    public boolean hasHttpHeaders() { return httpHeaders != null; }

    @NonNull
    public Map<String, String> getHttpHeaders() {
        if (httpHeaders == null) {
            httpHeaders = new HashMap<>();
        }
        return httpHeaders;
    }

    public void setHttpHeaders(@Nullable Map<String, String> httpHeaders) {
        this.httpHeaders = httpHeaders != null ? httpHeaders : new HashMap<>();
    }

    public void setHttpHeaders(@Nullable JSONObject httpHeadersJson) throws JSONException {
        this.httpHeaders = new HashMap<>();
        if (httpHeadersJson == null) return;

        Iterator<?> it = httpHeadersJson.keys();
        while (it.hasNext()) {
            String key = (String) it.next();
            this.httpHeaders.put(key, httpHeadersJson.getString(key));
        }
    }

    public boolean hasMaxLocations() { return maxLocations != null; }
    @Nullable public Integer getMaxLocations() { return maxLocations; }
    public void setMaxLocations(@Nullable Integer maxLocations) { this.maxLocations = maxLocations; }

    public boolean hasTemplate() { return template != null; }

    @NonNull
    public LocationTemplate getTemplate() {
        if (template == null) {
            template = LocationTemplateFactory.getDefault();
        }
        return template;
    }

    public void setTemplate(@Nullable LocationTemplate template) { this.template = template; }

    @NonNull
    @Override
    public String toString() {
        return "Config[distanceFilter=" + distanceFilter +
                " provider=" + locationProvider +
                " syncThreshold=" + syncThreshold +
                " maxLocations=" + maxLocations + "]";
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
    public static Config merge(@NonNull Config config1, @NonNull Config config2) {
        Config merger = new Config(config1);

        if (config2.hasStationaryRadius()) merger.setStationaryRadius(config2.getStationaryRadius());
        if (config2.hasDistanceFilter()) merger.setDistanceFilter(config2.getDistanceFilter());
        if (config2.hasDesiredAccuracy()) merger.setDesiredAccuracy(config2.getDesiredAccuracy());
        if (config2.hasDebug()) merger.setDebugging(config2.isDebugging());
        if (config2.hasNotificationTitle()) merger.setNotificationTitle(config2.getNotificationTitle());
        if (config2.hasNotificationText()) merger.setNotificationText(config2.getNotificationText());
        if (config2.hasStopOnTerminate()) merger.setStopOnTerminate(config2.getStopOnTerminate());
        if (config2.hasStartOnBoot()) merger.setStartOnBoot(config2.getStartOnBoot());
        if (config2.hasLocationProvider()) merger.setLocationProvider(config2.getLocationProvider());
        if (config2.hasInterval()) merger.setInterval(config2.getInterval());
        if (config2.hasFastestInterval()) merger.setFastestInterval(config2.getFastestInterval());
        if (config2.hasActivitiesInterval()) merger.setActivitiesInterval(config2.getActivitiesInterval());
        if (config2.hasHeartbeatInterval()) merger.setHeartbeatInterval(config2.getHeartbeatInterval());
        if (config2.hasNotificationIconColor()) merger.setNotificationIconColor(config2.getNotificationIconColor());
        if (config2.hasLargeNotificationIcon()) merger.setLargeNotificationIcon(config2.getLargeNotificationIcon());
        if (config2.hasSmallNotificationIcon()) merger.setSmallNotificationIcon(config2.getSmallNotificationIcon());
        if (config2.hasStartForeground()) merger.setStartForeground(config2.getStartForeground());
        if (config2.hasNotificationsEnabled()) merger.setNotificationsEnabled(config2.getNotificationsEnabled());
        if (config2.hasStopOnStillActivity()) merger.setStopOnStillActivity(config2.getStopOnStillActivity());
        if (config2.hasUrl()) merger.setUrl(config2.getUrl());
        if (config2.hasSyncUrl()) merger.setSyncUrl(config2.getSyncUrl());
        if (config2.hasSyncThreshold()) merger.setSyncThreshold(config2.getSyncThreshold());
        if (config2.hasHttpHeaders()) merger.setHttpHeaders(config2.getHttpHeaders());
        if (config2.hasMaxLocations()) merger.setMaxLocations(config2.getMaxLocations());

        if (config2.hasTemplate()) {
            if (config2.template instanceof AbstractLocationTemplate) {
                merger.setTemplate(
                        ((AbstractLocationTemplate) config2.template).clone()
                );
            } else {
                merger.setTemplate(config2.template);
            }
        }

        return merger;
    }

    @NonNull
    public static Config fromByteArray(@NonNull byte[] byteArray) {
        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(byteArray, 0, byteArray.length);
        parcel.setDataPosition(0);
        Config config = Config.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return config;
    }
}