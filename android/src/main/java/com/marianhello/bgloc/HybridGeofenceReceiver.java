package com.wrdhrd.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.SettingDAO;
import com.marianhello.logging.LoggerManager;
import com.wrdhrd.bgloc.provider.FusedDistanceFilterLocationProvider;

import org.slf4j.Logger;

public final class HybridGeofenceReceiver extends BroadcastReceiver {

    private static final Logger logger = LoggerManager.getLogger(HybridGeofenceReceiver.class);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        // 1. Fast In-Memory Check
        FusedDistanceFilterLocationProvider activeProvider = FusedDistanceFilterLocationProvider.getActiveInstance();
        if (activeProvider != null && !activeProvider.isStarted()) {
            logger.info("Geofence event dropped: In-memory provider is STOPPED.");
            return;
        }

        // 2. Persistent SQLite Check (Handles process recreation/ghost intents)
        try {
            SettingDAO settingDAO = DAOFactory.createSettingDAO(context.getApplicationContext());
            Setting setting = settingDAO.retrieveSetting();
            if (setting == null || !setting.isStarted()) {
                logger.info("Geofence event dropped: SQLite setting indicates tracking is OFF.");
                return;
            }
        } catch (Exception e) {
            logger.error("Failed verifying tracking state from SQLite in GeofenceReceiver", e);
            return;
        }

        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError()) {
            if (event != null) {
                logger.warn("GeofencingEvent error code: {}", event.getErrorCode());
            }
            return;
        }

        if (event.getGeofenceTransition() != Geofence.GEOFENCE_TRANSITION_EXIT) {
            return;
        }

        if (activeProvider != null) {
            long elapsedSinceSentry = System.currentTimeMillis() - activeProvider.getSentryEngagedTime();
            if (elapsedSinceSentry < FusedDistanceFilterLocationProvider.SENTRY_START_DEBOUNCE_MS) {
                logger.debug("Geofence exit suppressed: within debounce window ({}ms)", elapsedSinceSentry);
                return;
            }

            logger.info("Geofence EXIT validated. Switching tracking engine to high gear.");
            activeProvider.postToWorker(() -> activeProvider.setPace(true));
        }
    }
}