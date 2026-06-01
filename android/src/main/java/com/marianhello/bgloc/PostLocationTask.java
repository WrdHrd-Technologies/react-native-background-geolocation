package com.marianhello.bgloc;

import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.logging.LoggerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
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

    public interface PostLocationTaskListener
    {
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
        logger.info("Scheduling non-posted local location queue data clearance.");
        try {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        mLocationDAO.deleteAllLocations();
                        logger.debug("Local SQLite pending location database records purged successfully.");
                    } catch (Exception e) {
                        logger.error("Failed executing unposted locations data cleanup inside background thread.", e);
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            logger.error("Executor rejected clearQueue command track execution.", ex);
        }
    }

    public void add(final BackgroundLocation location) {
        if (mConfig == null) {
            logger.warn("PostLocationTask has no config. Skipping location.");
            return;
        }

        try {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    long locationId = mLocationDAO.persistLocation(location);
                    location.setLocationId(locationId);

                    post(location);
                }
            });
        } catch (RejectedExecutionException ex) {
            logger.error("Executor rejected location, cannot persist.", ex);
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

        if (mHasConnectivity && mConfig.hasValidUrl()) {
            if (postLocation(location)) {
                mLocationDAO.deleteLocationById(locationId);

                return;
            } else {
                mLocationDAO.updateLocationForSync(locationId);
            }
        } else {
            mLocationDAO.updateLocationForSync(locationId);
        }

        if (mConfig.hasValidSyncUrl()) {
            long syncLocationsCount = mLocationDAO.getLocationsForSyncCount(System.currentTimeMillis());
            if (syncLocationsCount >= mConfig.getSyncThreshold()) {
                logger.debug("Attempt to sync locations: {} threshold: {}", syncLocationsCount, mConfig.getSyncThreshold());
                mTaskListener.onSyncRequested();
            }
        }
    }

    private boolean postLocation(BackgroundLocation location) {
        logger.debug("Executing PostLocationTask#postLocation");
        JSONArray jsonLocations = new JSONArray();

        try {
            jsonLocations.put(mConfig.getTemplate().locationToJson(location));
        } catch (JSONException e) {
            logger.warn("Location to json failed: {}", location.toString());
            return false;
        }

        String url = mConfig.getUrl();

        Map<String, String> safeHeaders = mConfig.getHttpHeaders();
        logger.debug("Posting json to url: {} headers: {}", url, mConfig.getHttpHeaders());
        int responseCode;

        try {
            responseCode = HttpPostService.postJSON(url, jsonLocations, safeHeaders);
        } catch (Exception e) {
            mHasConnectivity = mConnectivityListener.hasConnectivity();
            logger.warn("Error while posting locations: {}", e.getMessage());
            return false;
        }

        if (responseCode == 285) {
            logger.debug("Location was sent to the server, and received an \"HTTP 285 Updates Not Required\"");

            if (mTaskListener != null)
                mTaskListener.onRequestedAbortUpdates();
        }

        if (responseCode == 401) {
            if (mTaskListener != null)
                mTaskListener.onHttpAuthorizationUpdates();
        }

        // All 2xx statuses are okay
        boolean isStatusOkay = responseCode >= 200 && responseCode < 300;

        if (!isStatusOkay) {
            logger.warn("Server error while posting locations responseCode: {}", responseCode);
            return false;
        }

        return true;
    }

    private void postError(PluginException error,String targetUrl) {
        try {
            JSONObject jsonError = new JSONObject(error.toJsonString());
            String finalUrl = targetUrl.endsWith("/error") ? targetUrl : targetUrl + "/error";
            Map<String, String> safeHeaders = mConfig.getHttpHeaders();

            int responseCode = HttpPostService.postJSON(finalUrl, jsonError, safeHeaders);
            if (responseCode >= 200 && responseCode < 300) {
                logger.debug("Critical Error successfully dispatched to server.");
            }
        } catch (Exception e) {
            logger.warn("Offline: Could not dispatch critical error: {}", e.getMessage());
        }
    }

    public void add(final PluginException error) {
        if (mConfig == null) {
            logger.warn("PostErrorTask has no config. Did you called setConfig? Skipping Error.");
            return;
        }

        final String errorUrl = mConfig.hasValidUrl() ? mConfig.getUrl() : mConfig.getSyncUrl();

        if (mHasConnectivity && errorUrl != null && !errorUrl.isEmpty()) {
            try {
                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        postError(error,errorUrl);
                    }
                });
            } catch (RejectedExecutionException ex) {
                logger.error("Error when Posting Error: {}", ex.getMessage());
            }
        }
    }
}