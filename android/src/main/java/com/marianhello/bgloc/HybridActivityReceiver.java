package com.wrdhrd.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.SettingDAO;
import com.marianhello.logging.LoggerManager;
import com.wrdhrd.bgloc.provider.FusedDistanceFilterLocationProvider;

import org.slf4j.Logger;

public final class HybridActivityReceiver extends BroadcastReceiver {

    private static final Logger logger = LoggerManager.getLogger(HybridActivityReceiver.class);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ActivityTransitionResult.hasResult(intent)) return;

        FusedDistanceFilterLocationProvider activeProvider = FusedDistanceFilterLocationProvider.getActiveInstance();
        if (activeProvider != null && !activeProvider.isStarted()) {
            logger.info("Activity transition dropped: In-memory provider is STOPPED.");
            return;
        }

        try {
            SettingDAO settingDAO = DAOFactory.createSettingDAO(context.getApplicationContext());
            Setting setting = settingDAO.retrieveSetting();
            if (setting == null || !setting.isStarted()) {
                logger.info("Activity transition dropped: SQLite setting indicates tracking is OFF.");
                return;
            }
        } catch (Exception e) {
            logger.error("Failed verifying tracking state from SQLite in ActivityReceiver", e);
            return;
        }

        ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
        if (result == null) return;

        for (ActivityTransitionEvent event : result.getTransitionEvents()) {
            if (event.getTransitionType() != ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                continue;
            }

            int type = event.getActivityType();
            String detectedState;

            if (type == DetectedActivity.IN_VEHICLE) {
                detectedState = FusedDistanceFilterLocationProvider.STATE_DRIVING;
            } else if (type == DetectedActivity.WALKING || type == DetectedActivity.RUNNING) {
                detectedState = FusedDistanceFilterLocationProvider.STATE_WALKING;
            } else {
                continue;
            }

            logger.info("Activity Recognition triggered [{}].", detectedState);

            if (activeProvider != null) {
                activeProvider.postToWorker(() -> {
                    if (activeProvider.isStarted() && !activeProvider.isMoving()) {
                        activeProvider.setKineticState(detectedState);
                        activeProvider.setPace(true);
                    }
                });
            }
            break;
        }
    }
}