package com.marianhello.bgloc.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.marianhello.bgloc.HeartbeatReceiver;
import com.marianhello.logging.LoggerManager;


public final class HeartbeatManager {
    private static final String TAG = "HeartbeatManager";
    
    private static final long MIN_DOZE_INTERVAL_MILLIS = 9 * 60 * 1000; 

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final org.slf4j.Logger logger;
    
    private PendingIntent mHeartbeatIntent;
    private long mIntervalMillis = MIN_DOZE_INTERVAL_MILLIS;
    private boolean mIsRunning = false;

    public HeartbeatManager(@NonNull Context context) {
        this.mContext = context.getApplicationContext();
        this.mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        this.logger = LoggerManager.getLogger(HeartbeatManager.class);
    }

    public void setInterval(long intervalMillis) {
        long floorMillis = Math.max(intervalMillis, MIN_DOZE_INTERVAL_MILLIS);

        if (this.mIntervalMillis != floorMillis) {
            this.mIntervalMillis = floorMillis;
            logger.info("Heartbeat tracking window successfully re-calibrated to: {}ms", this.mIntervalMillis);
            
            if (mIsRunning) {
                scheduleNextBeat();
            }
        }
    }

    public void start() {
        if (mIsRunning) return;
        mIsRunning = true;
        logger.info("Initiating stationary hardware checkpoint loop. Pulse Interval: {}ms", mIntervalMillis);
        scheduleNextBeat();
    }

 
    public void stop() {
        if (!mIsRunning) return;
        mIsRunning = false;
        logger.info("Halting stationary execution checkpoint engine.");
        cancelActiveAlarm();
    }

    public void destroy() {
        stop();
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    private void cancelActiveAlarm() {
        if (mHeartbeatIntent != null) {
            try {
                mAlarmManager.cancel(mHeartbeatIntent);
                mHeartbeatIntent.cancel();
            } catch (Exception e) {
                Log.w(TAG, "Exception encountered during pending intent token cleanup.", e);
            }
            mHeartbeatIntent = null;
        }
    }

    private void scheduleNextBeat() {
        if (!mIsRunning) return;

        if (mHeartbeatIntent == null) {
            Intent intent = new Intent(mContext, HeartbeatReceiver.class);
            
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            mHeartbeatIntent = PendingIntent.getBroadcast(mContext, 0, intent, flags);
        }

        long triggerAtMillis = System.currentTimeMillis() + mIntervalMillis;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mAlarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, mHeartbeatIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, mHeartbeatIntent);
            } else {
                mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, mHeartbeatIntent);
            }
            logger.debug("Subsequent tracking execution pulse registered cleanly. Delay target: {}ms", mIntervalMillis);
        } catch (Exception e) {
            logger.error("Platform rejected background alarm registration contract request.", e);
        }
    }
}