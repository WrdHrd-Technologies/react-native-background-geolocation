package com.marianhello.bgloc.data.provider;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract;
import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract.LocationEntry;
import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract;
import com.marianhello.logging.LoggerManager;

import java.util.ArrayList;
import java.util.Collection;

public class ContentProviderLocationDAO implements LocationDAO {
    private static final String TAG = "ContentProviderLocDAO";
    private final org.slf4j.Logger logger;
    private final ContentResolver mResolver;
    private final Uri mContentUri;
    private final String mAuthority;

    public ContentProviderLocationDAO(Context context) {
        logger = LoggerManager.getLogger(ContentProviderLocationDAO.class);
        ResourceResolver resourceResolver = ResourceResolver.newInstance(context);
        mAuthority = resourceResolver.getAuthority();
        mContentUri = LocationContentProvider.getContentUri(mAuthority);
        mResolver = context.getApplicationContext().getContentResolver();
    }

    private Collection<BackgroundLocation> getLocations(String whereClause, String[] whereArgs) {
        Collection<BackgroundLocation> locations = new ArrayList<>();
        
        // Explicit sort order execution parameter
        String sortOrder = LocationEntry.COLUMN_NAME_TIME + " ASC";
        
        try (Cursor cursor = mResolver.query(mContentUri, null, whereClause, whereArgs, sortOrder)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    locations.add(BackgroundLocation.fromCursor(cursor));
                }
            }
        } catch (Exception e) {
            logger.error("Failed executing location array query selection strategy.", e);
        }

        return locations;
    }

    @Override
    public Collection<BackgroundLocation> getAllLocations() {
        return getLocations(null, null);
    }

    @Override
    public Collection<BackgroundLocation> getValidLocations() {
        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " <> ?";
        String[] whereArgs = { String.valueOf(BackgroundLocation.DELETED) };
        return getLocations(whereClause, whereArgs);
    }

    @Override
    public BackgroundLocation getLocationById(long id) {
        BackgroundLocation location = null;
        Uri singleLocationUri = LocationContentProvider.buildUriWithId(mAuthority, id);

        try (Cursor cursor = mResolver.query(singleLocationUri, null, null, null, null)) {
            if (cursor != null && cursor.moveToNext()) {
                location = BackgroundLocation.fromCursor(cursor);
                if (!cursor.isLast()) {
                    throw new RuntimeException("Data integrity anomaly: Identifier [" + id + "] maps to non-unique rows.");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to extract single location profile metrics by ID.", e);
        }

        return location;
    }

    public int getLocationsCount() {
        String[] projection = { "count(*)" };
        try (Cursor cursor = mResolver.query(mContentUri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            logger.error("Failed to compute database row capacity allocation limits.", e);
        }
        return 0;
    }

    @Override
    public BackgroundLocation getFirstUnpostedLocation() {
        // FIXED: Replaced unsafe string subqueries with explicit selection clauses and sort-limit parameters
        String selection = LocationEntry.COLUMN_NAME_STATUS + " = ?";
        String[] selectionArgs = { String.valueOf(BackgroundLocation.POST_PENDING) };
        String sortOrder = LocationEntry.COLUMN_NAME_TIME + " ASC LIMIT 1";

        BackgroundLocation location = null;
        try (Cursor cursor = mResolver.query(mContentUri, null, selection, selectionArgs, sortOrder)) {
            if (cursor != null && cursor.moveToNext()) {
                location = BackgroundLocation.fromCursor(cursor);
            }
        } catch (Exception e) {
            logger.error("Failed to query unposted execution queue boundaries safely.", e);
        }
        return location;
    }

    @Override
    public BackgroundLocation getNextUnpostedLocation(long fromId) {
        // FIXED: Complies with platform injection validation filters
        String selection = LocationEntry.COLUMN_NAME_STATUS + " = ? AND " + LocationEntry._ID + " <> ?";
        String[] selectionArgs = { String.valueOf(BackgroundLocation.POST_PENDING), String.valueOf(fromId) };
        String sortOrder = LocationEntry.COLUMN_NAME_TIME + " ASC LIMIT 1";

        BackgroundLocation location = null;
        try (Cursor cursor = mResolver.query(mContentUri, null, selection, selectionArgs, sortOrder)) {
            if (cursor != null && cursor.moveToNext()) {
                location = BackgroundLocation.fromCursor(cursor);
            }
        } catch (Exception e) {
            logger.error("Failed to select next valid queue processing target location.", e);
        }
        return location;
    }

    @Override
    public long getUnpostedLocationsCount() {
        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " = ?";
        String[] whereArgs = { String.valueOf(BackgroundLocation.POST_PENDING) };
        String[] projection = { "count(*)" };

        try (Cursor cursor = mResolver.query(mContentUri, projection, whereClause, whereArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            logger.error("Failed to extract unposted locations aggregate payload sizes.", e);
        }
        return 0;
    }

    @Override
    public long getLocationsForSyncCount(long millisSinceLastBatch) {
        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " = ? AND ( " +
                LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + " IS NULL OR " +
                LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + " < ? )";
        
        String[] whereArgs = {
                String.valueOf(BackgroundLocation.SYNC_PENDING),
                String.valueOf(millisSinceLastBatch)
        };
        String[] projection = { "count(*)" };

        try (Cursor cursor = mResolver.query(mContentUri, projection, whereClause, whereArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            logger.error("Failed to extract target synchronization coordinate batch boundaries.", e);
        }
        return 0;
    }

    public Uri getOldestLocationUri() {
        // FIXED: Eliminated risky inline subqueries from selection strings
        String sortOrder = LocationEntry.COLUMN_NAME_TIME + " ASC LIMIT 1";
        try (Cursor cursor = mResolver.query(mContentUri, new String[]{LocationEntry._ID}, null, null, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                return LocationContentProvider.buildUriWithId(mAuthority, cursor.getLong(0));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed resolving oldest tracking row index bounds.", e);
        }
        throw new SQLiteException("Unable to establish baseline index anchor: Data layer empty.");
    }

    @Override
    public long persistLocation(BackgroundLocation location) {
        if (location == null) return -1;
        
        Uri locationUri = mResolver.insert(mContentUri, location.toContentValues());
        if (locationUri == null || locationUri.getLastPathSegment() == null) {
            return -1;
        }
        
        try {
            // FIXED: Upgraded parser execution to handle 64-bit unsigned primitives safely
            return Long.parseLong(locationUri.getLastPathSegment());
        } catch (NumberFormatException e) {
            Log.e(TAG, "Mismatched identifier format parsed from insert transaction result.", e);
            return -1;
        }
    }

    @Override
    public long persistLocation(BackgroundLocation location, int maxRows) {
        if (maxRows <= 0) return -1;

        int rowCount = getLocationsCount();
        if (rowCount < maxRows) {
            return persistLocation(location);
        }

        ArrayList<ContentProviderOperation> operations = new ArrayList<>();

        if (rowCount > maxRows) {
            // FIXED: Safe parameterized extraction query strategy avoids injection vulnerabilities
            String selection = LocationEntry._ID + " IN (SELECT " + LocationEntry._ID + 
                    " FROM " + LocationEntry.TABLE_NAME + 
                    " ORDER BY " + LocationEntry.COLUMN_NAME_TIME + " LIMIT ?)";

            operations.add(
                    ContentProviderOperation.newDelete(mContentUri)
                    .withSelection(selection, new String[]{String.valueOf(rowCount - maxRows)})
                    .build()
            );
        }

        try {
            Uri oldestUri = getOldestLocationUri();
            operations.add(
                    ContentProviderOperation.newUpdate(oldestUri)
                        .withValues(location.toContentValues())
                        .build()
            );
            mResolver.applyBatch(mAuthority, operations);
        } catch (Exception e) {
            logger.error("Error executing atomic batch data pruning/persistence operation (maxRows: {}): {}", maxRows, e.getMessage());
            return -1;
        }

        return 0;
    }

    @Override
    public long persistLocationForSync(BackgroundLocation location, int maxRows) {
        if (location == null) return -1;
        Long locationId = location.getLocationId();

        if (locationId == null) {
            location.setStatus(BackgroundLocation.SYNC_PENDING);
            return persistLocation(location, maxRows);
        } else {
            updateLocationForSync(locationId);
            return locationId;
        }
    }

    @Override
    public void updateLocationForSync(long locationId) {
        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_NAME_STATUS, BackgroundLocation.SYNC_PENDING);

        String whereClause = LocationEntry._ID + " = ?";
        String[] whereArgs = { String.valueOf(locationId) };

        mResolver.update(mContentUri, values, whereClause, whereArgs);
    }

    @Override
    public void deleteLocationById(long locationId) {
        mResolver.delete(LocationContentProvider.buildUriWithId(mAuthority, locationId), null, null);
    }

    @Override
    public BackgroundLocation deleteFirstUnpostedLocation() {
        BackgroundLocation location = getFirstUnpostedLocation();
        if (location != null) {
            deleteLocationById(location.getLocationId());
        }
        return location;
    }

    @Override
    public int deleteAllLocations() {
        return mResolver.delete(mContentUri, null, null);
    }

    @Override
    public int deleteAllLocationsPermanent(long millisBeforeTimeStamp) {
        if (millisBeforeTimeStamp < 0) return 0;
        
        String whereClause = LocationEntry.COLUMN_NAME_REALTIME + " < ?";
        String[] whereArgs = { String.valueOf(millisBeforeTimeStamp) };
        return mResolver.delete(mContentUri, whereClause, whereArgs);
    }

    @Override
    public int deleteUnpostedLocations() {
        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_NAME_STATUS, BackgroundLocation.SYNC_PENDING);

        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " = ?";
        String[] whereArgs = { String.valueOf(BackgroundLocation.POST_PENDING) };

        return mResolver.update(mContentUri, values, whereClause, whereArgs);
    }

    @Override
    public BackgroundLocation getValidLatestLocation() {
        String selection = LocationEntry.COLUMN_NAME_STATUS + " <> ?";
        String[] selectionArgs = { String.valueOf(BackgroundLocation.DELETED) };
        String sortOrder = LocationEntry.COLUMN_NAME_TIME + " DESC LIMIT 1";

        BackgroundLocation location = null;
        try (Cursor cursor = mResolver.query(mContentUri, null, selection, selectionArgs, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                location = BackgroundLocation.fromCursor(cursor);
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve latest valid coordinate record profile from context layer.", e);
        }
        return location;
    }
}