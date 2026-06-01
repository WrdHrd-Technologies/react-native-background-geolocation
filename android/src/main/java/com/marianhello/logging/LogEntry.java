package com.marianhello.logging;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public final class LogEntry {
    private int id;
    private int context;
    private String level;
    private String message;
    private long timestamp;
    private String loggerName;
    
    private Collection<String> stackTrace = new ArrayList<>();

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getContext() {
        return context;
    }

    public void setContext(int context) {
        this.context = context;
    }

    @NonNull
    public String getLevel() {
        return level != null ? level : "INFO";
    }

    public void setLevel(@Nullable String level) {
        this.level = level;
    }

    @NonNull
    public String getMessage() {
        return message != null ? message : "";
    }

    public void setMessage(@Nullable String message) {
        this.message = message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @NonNull
    public String getLoggerName() {
        return loggerName != null ? loggerName : "";
    }

    public void setLoggerName(@Nullable String loggerName) {
        this.loggerName = loggerName;
    }

    public boolean hasStackTrace() {
        return stackTrace != null && !stackTrace.isEmpty();
    }

    @NonNull
    public Collection<String> getStackTrace() {
        return stackTrace != null ? stackTrace : Collections.emptyList();
    }

    public void setStackTrace(@Nullable Collection<String> stackTrace) {
        this.stackTrace = (stackTrace != null) ? stackTrace : new ArrayList<>();
    }

    @NonNull
    public String getStackTraceAsFormattedString() {
        if (!hasStackTrace()) return "";

        StringBuilder sb = new StringBuilder(this.stackTrace.size() * 128);
        for (String traceLine : this.stackTrace) {
            if (traceLine != null) {
                sb.append(traceLine).append('\n'); // Single quotes ensure character primitive optimization
            }
        }
        return sb.toString();
    }

    @NonNull
    public JSONObject toJSONObject() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", this.id);
        json.put("context", this.context);
        json.put("level", getLevel());
        json.put("message", getMessage());
        json.put("timestamp", this.timestamp);
        json.put("logger", getLoggerName());
        
        if (hasStackTrace()) {
            JSONArray jsonTraceArray = new JSONArray();
            for (String traceLine : this.stackTrace) {
                if (traceLine != null) {
                    jsonTraceArray.put(traceLine);
                }
            }
            json.put("stackTrace", jsonTraceArray);
        }

        return json;
    }
}