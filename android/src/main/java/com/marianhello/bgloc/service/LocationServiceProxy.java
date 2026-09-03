package com.marianhello.bgloc.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.Setting;

public class LocationServiceProxy implements LocationService, LocationServiceInfo {
    private static final String TAG = "LocationServiceProxy";
    private final Context mContext;

    public LocationServiceProxy(Context context) {
        mContext = context.getApplicationContext();
    }

    private LocationServiceIntentBuilder getFreshBuilder() {
        return LocationServiceIntentBuilder.getInstance(mContext);
    }

    @Override
    public void configure(Config config) {
        if (!isStarted()) { return; }

        Intent intent = getFreshBuilder()
                .setCommand(CommandId.CONFIGURE, config)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void sync() {
        if (!isStarted()) { return; }

        Intent intent = getFreshBuilder()
                .setCommand(CommandId.SYNC)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void registerHeadlessTask(String taskRunnerClass) {
        Intent intent = getFreshBuilder()
                .setCommand(CommandId.REGISTER_HEADLESS_TASK, taskRunnerClass)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void startHeadlessTask() {
        if (!isStarted()) { return; }

        Intent intent = getFreshBuilder()
                .setCommand(CommandId.START_HEADLESS_TASK)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void stopHeadlessTask() {
        if (!isStarted()) { return; }

        Intent intent = getFreshBuilder()
                .setCommand(CommandId.STOP_HEADLESS_TASK)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void executeProviderCommand(int command, int arg) {
        if (!isStarted()) { return; }

        Intent intent = getFreshBuilder()
                .setCommand(command, String.valueOf(arg))
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void start() {
        Intent intent = getFreshBuilder()
                .setCommand(CommandId.START)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void startForegroundService() {
        Intent intent = getFreshBuilder()
                .setCommand(CommandId.START_FOREGROUND_SERVICE)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mContext.startForegroundService(intent);
            } else {
                mContext.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal restriction: OS rejected immediate foreground initialization sequence.", e);
        }
    }

    @Override
    public void stop() {
        if (!isStarted()) { return; }

        Intent intent = getFreshBuilder()
                .setCommand(CommandId.STOP)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void stopForeground() {
        if (!isStarted()) { return; }

        Intent intent = getFreshBuilder()
                .setCommand(CommandId.STOP_FOREGROUND)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void setting(Setting setting) {
        Intent intent = getFreshBuilder()
                .setCommand(CommandId.CONFIGURE)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public void startForeground() {
        if (!isStarted()) { return; }

        Intent intent = getFreshBuilder()
                .setCommand(CommandId.START_FOREGROUND)
                .build();
        executeIntentCommand(intent);
    }

    @Override
    public boolean isStarted() {
        LocationServiceInfo serviceInfo = new LocationServiceInfoImpl(mContext);
        return serviceInfo.isStarted();
    }

    public boolean isRunning() {
        return isStarted() && LocationServiceImpl.isRunning();
    }

    @Override
    public boolean isBound() {
        LocationServiceInfo serviceInfo = new LocationServiceInfoImpl(mContext);
        return serviceInfo.isBound();
    }

    private void executeIntentCommand(Intent intent) {
        try {
            mContext.startService(intent);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Command dispatch dropped. App is backgrounded and cannot start services. Command ID: "
                    + LocationServiceIntentBuilder.getCommand(intent).getId(), e);
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected crash error during command pipeline dispatch matrix execution.", ex);
        }
    }
}