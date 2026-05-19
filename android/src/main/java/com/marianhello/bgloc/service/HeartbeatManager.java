package com.marianhello.bgloc.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.marianhello.bgloc.HeartbeatReceiver;
import com.marianhello.logging.LoggerManager;

class HeartbeatManager {
    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private PendingIntent mHeartbeatIntent;
    private final org.slf4j.Logger logger;
    private long mIntervalMillis = 5 * 60 * 1000;
    private boolean mIsRunning = false;
    private final LocationServiceIntentBuilder mIntentBuilder;

    public HeartbeatManager(Context context) {
        mContext = context;
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        logger = LoggerManager.getLogger(HeartbeatManager.class);
        mIntentBuilder = new LocationServiceIntentBuilder(context);
    }

    public void setInterval(long intervalMillis) {
        long floorMillis = Math.max(intervalMillis, 3 * 60 * 1000);

        if (this.mIntervalMillis != floorMillis) {
            this.mIntervalMillis = floorMillis;
            logger.info("Heartbeat interval updated to: {}ms", this.mIntervalMillis);
            
            if (mIsRunning) {
                stop();
                start();
            }
        }
    }

    public void start() {
        mIsRunning = true;
        logger.info("Starting stationary heartbeat. Interval: {}ms", mIntervalMillis);
        scheduleNextBeat();
    }

    public void stop() {
        mIsRunning = false;
        logger.info("Stopping stationary heartbeat.");
        if (mHeartbeatIntent != null) {
            mAlarmManager.cancel(mHeartbeatIntent);
            mHeartbeatIntent = null;
        }
    }

    public void destroy() {
        stop();
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    private void scheduleNextBeat() {
        Intent intent = new Intent(mContext, HeartbeatReceiver.class);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE; 
        }

        mHeartbeatIntent = PendingIntent.getBroadcast(mContext, 0, intent, flags);

        long triggerAtMillis = System.currentTimeMillis() + mIntervalMillis;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mAlarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, mHeartbeatIntent);
            } else {
                mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, mHeartbeatIntent);
            }
            logger.debug("Heartbeat scheduled. OS will wake us in approx {}ms", mIntervalMillis);
        } catch (Exception e) {
            logger.error("Failed to schedule heartbeat alarm.", e);
        }
    }
}