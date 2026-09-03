package com.marianhello.bgloc;

import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.logging.LoggerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class PostLocationTask {
    private final LocationDAO mLocationDAO;
    private final PostLocationTaskListener mTaskListener;
    private final ConnectivityListener mConnectivityListener;

    private final ExecutorService mExecutor;

    private volatile boolean mHasConnectivity = true;
    private volatile Config mConfig;

    private org.slf4j.Logger logger;

    public interface PostLocationTaskListener {
        void onSyncRequested();
        void onRequestedAbortUpdates();
        void onHttpAuthorizationUpdates();
    }

    public PostLocationTask(LocationDAO dao, PostLocationTaskListener taskListener,
                            ConnectivityListener connectivityListener) {
        logger = LoggerManager.getLogger(PostLocationTask.class);
        logger.info("Creating PostLocationTask");

        mLocationDAO = dao;
        mTaskListener = taskListener;
        mConnectivityListener = connectivityListener;

        mExecutor = Executors.newSingleThreadExecutor();
    }

    public void setConfig(Config config) {
        mConfig = config;
    }

    public void setHasConnectivity(boolean hasConnectivity) {
        mHasConnectivity = hasConnectivity;
    }

    public void clearQueue() {
        try {
            mExecutor.execute(() -> {
                try {
                    mLocationDAO.deleteAllLocations();
                    logger.debug("Local SQLite pending location database records purged successfully.");
                } catch (Exception e) {
                    logger.error("Failed executing unposted locations data cleanup.", e);
                }
            });
        } catch (RejectedExecutionException ex) {
            logger.error("Executor rejected clearQueue execution.", ex);
        }
    }

    public void add(final BackgroundLocation location) {
        if (mConfig == null) {
            logger.warn("PostLocationTask has no config. Skipping location.");
            return;
        }

        try {
            mExecutor.execute(() -> {
                long locationId = mLocationDAO.persistLocation(location);
                location.setLocationId(locationId);
                post(location);
            });
        } catch (RejectedExecutionException ex) {
            logger.error("Executor rejected location persistence task.", ex);
        }
    }

    public void shutdown() {
        shutdown(60);
    }

    public void shutdown(int waitSeconds) {
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
        }
    }

    private void post(final BackgroundLocation location) {
        long locationId = location.getLocationId();
        boolean hasImmediateUrl = mConfig.getUrl() != null && !mConfig.getUrl().isEmpty();

        if (hasImmediateUrl) {
            if (mHasConnectivity && mConfig.hasValidUrl()) {
                if (postLocation(location)) {
                    mLocationDAO.deleteLocationById(locationId);
                    return;
                }
            }

            mLocationDAO.updateLocationForSync(locationId);
            return;
        }

        mLocationDAO.updateLocationForSync(locationId);
    }

    private boolean postLocation(BackgroundLocation location) {
        JSONArray jsonLocations = new JSONArray();

        try {
            jsonLocations.put(mConfig.getTemplate().locationToJson(location));
        } catch (JSONException e) {
            logger.warn("Location to JSON transformation failed: {}", location);
            return false;
        }

        String url = mConfig.getUrl();
        Map<String, String> safeHeaders = mConfig.getHttpHeaders();
        int responseCode;

        try {
            responseCode = HttpPostService.postJSON(url, jsonLocations, safeHeaders);
        } catch (Exception e) {
            mHasConnectivity = mConnectivityListener.hasConnectivity();
            logger.warn("Error posting single location: {}", e.getMessage());
            return false;
        }

        if (responseCode == 285 && mTaskListener != null) {
            mTaskListener.onRequestedAbortUpdates();
        }

        if (responseCode == 401 && mTaskListener != null) {
            mTaskListener.onHttpAuthorizationUpdates();
        }

        return responseCode >= 200 && responseCode < 300;
    }

    public void add(final PluginException error) {
        if (mConfig == null) {
            logger.warn("PostErrorTask has no config. Skipping Error.");
            return;
        }

        final String errorUrl = mConfig.hasValidUrl() ? mConfig.getUrl() : mConfig.getSyncUrl();

        if (mHasConnectivity && errorUrl != null && !errorUrl.isEmpty()) {
            try {
                mExecutor.execute(() -> postError(error, errorUrl));
            } catch (RejectedExecutionException ex) {
                logger.error("Error dispatch rejected by executor: {}", ex.getMessage());
            }
        }
    }

    private void postError(PluginException error, String targetUrl) {
        try {
            JSONObject jsonError = new JSONObject(error.toJsonString());
            String finalUrl = targetUrl.endsWith("/error") ? targetUrl : targetUrl + "/error";
            Map<String, String> safeHeaders = mConfig.getHttpHeaders();

            int responseCode = HttpPostService.postJSON(finalUrl, jsonError, safeHeaders);
            if (responseCode >= 200 && responseCode < 300) {
                logger.debug("Error successfully dispatched to server.");
            }
        } catch (Exception e) {
            logger.warn("Offline: Could not dispatch error report: {}", e.getMessage());
        }
    }
}