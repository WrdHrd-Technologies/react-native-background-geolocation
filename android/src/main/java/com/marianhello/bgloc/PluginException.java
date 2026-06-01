package com.marianhello.bgloc;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONObject;

public final class PluginException extends Exception {
    private static final long serialVersionUID = 42L;

    public static final int PERMISSION_DENIED_ERROR = 1000;
    public static final int SETTINGS_ERROR = 1001;
    public static final int CONFIGURE_ERROR = 1002;
    public static final int SERVICE_ERROR = 1003;
    public static final int JSON_ERROR = 1004;

    private final int code; 

    public PluginException(@Nullable String message, @Nullable Throwable cause, int code) {
        super(message, cause);
        this.code = code;
    }

    public PluginException(@Nullable String message, int code) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }

    @NonNull
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt("code", this.code);
        bundle.putString("message", this.getLocalizedMessage());
        return bundle;
    }

  
    @NonNull
    public String toJsonString() {
        String msg = getLocalizedMessage();
        if (msg == null) {
            msg = "";
        }

        StringBuilder sb = new StringBuilder(msg.length() + 64);
        sb.append("{");
        
        sb.append("\"code\":").append(this.code).append(",");
        
        sb.append("\"message\":\"").append(JSONObject.quote(msg)).append("\"");
        
        sb.append("}");
        return sb.toString();
    }

    @NonNull
    @Override
    public String toString() {
        return "PluginException[Code=" + code + ", Message=" + getLocalizedMessage() + "]";
    }
}