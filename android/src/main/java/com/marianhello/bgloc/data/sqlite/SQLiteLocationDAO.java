package com.marianhello.bgloc.data.sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.Log;

import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract.LocationEntry;

import java.util.ArrayList;
import java.util.Collection;

public class SQLiteLocationDAO implements LocationDAO {
    private static final String TAG = "SQLiteLocationDAO";
    private final SQLiteDatabase db;

    public SQLiteLocationDAO(Context context) {
        SQLiteOpenHelper helper = SQLiteOpenHelper.getHelper(context.getApplicationContext());
        this.db = helper.getWritableDatabase();
    }

    public SQLiteLocationDAO(SQLiteDatabase db) {
        this.db = db;
    }

    private static final int INDEX_ID                  = 0;
    private static final int INDEX_TIME                = 1;
    private static final int INDEX_ACCURACY            = 2;
    private static final int INDEX_SPEED               = 3;
    private static final int INDEX_BEARING             = 4;
    private static final int INDEX_ALTITUDE            = 5;
    private static final int INDEX_LATITUDE            = 6;
    private static final int INDEX_LONGITUDE           = 7;
    private static final int INDEX_RADIUS              = 8;
    private static final int INDEX_HAS_ACCURACY        = 9;
    private static final int INDEX_HAS_SPEED           = 10;
    private static final int INDEX_HAS_BEARING         = 11;
    private static final int INDEX_HAS_ALTITUDE        = 12;
    private static final int INDEX_HAS_RADIUS          = 13;
    private static final int INDEX_PROVIDER            = 14;
    private static final int INDEX_LOCATION_PROVIDER   = 15;
    private static final int INDEX_STATUS              = 16;
    private static final int INDEX_BATCH_START_MILLIS  = 17;
    private static final int INDEX_MOCK_FLAGS          = 18;
    private static final int INDEX_BATTERY_LEVEL       = 19;
    private static final int INDEX_CHARGING_FLAG       = 20;
    private static final int INDEX_REALTIME            = 21;
    private static final int INDEX_ELAPSEDREALTIMENANO = 22;

    private Collection<BackgroundLocation> getLocations(String whereClause, String[] whereArgs) {
        Collection<BackgroundLocation> locations = new ArrayList<>();
        String orderBy = LocationEntry.COLUMN_NAME_TIME + " ASC";

        try (Cursor cursor = db.query(
                LocationEntry.TABLE_NAME,
                LocationEntry.PROJECTION_ALL,
                whereClause,
                whereArgs,
                null,
                null,
                orderBy
        )) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    locations.add(hydrate(cursor));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to compile location records array from storage layer query.", e);
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
        String whereClause = LocationEntry._ID + " = ?";
        String[] whereArgs = { String.valueOf(id) };
        BackgroundLocation location = null;

        try (Cursor cursor = db.query(
                LocationEntry.TABLE_NAME,
                LocationEntry.PROJECTION_ALL,
                whereClause,
                whereArgs,
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToNext()) {
                location = hydrate(cursor);
                if (!cursor.isLast()) {
                    throw new RuntimeException("Data integrity fault: Target coordinate identifier [" + id + "] is not unique.");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error performing single identifier row profile isolation lookup.", e);
        }

        return location;
    }

    @Override
    public BackgroundLocation getFirstUnpostedLocation() {
        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " = ?";
        String[] whereArgs = { String.valueOf(BackgroundLocation.POST_PENDING) };
        String orderBy = LocationEntry.COLUMN_NAME_TIME + " ASC";
        String limit = "1";

        BackgroundLocation location = null;
        try (Cursor cursor = db.query(
                LocationEntry.TABLE_NAME,
                LocationEntry.PROJECTION_ALL,
                whereClause,
                whereArgs,
                null,
                null,
                orderBy,
                limit
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                location = hydrate(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting queue extraction head tracking point.", e);
        }
        
        return location;
    }

    @Override
    public BackgroundLocation getNextUnpostedLocation(long fromId) {
        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " = ? AND " + LocationEntry._ID + " <> ?";
        String[] whereArgs = { String.valueOf(BackgroundLocation.POST_PENDING), String.valueOf(fromId) };
        String orderBy = LocationEntry.COLUMN_NAME_TIME + " ASC";
        String limit = "1";

        BackgroundLocation location = null;
        try (Cursor cursor = db.query(
                LocationEntry.TABLE_NAME,
                LocationEntry.PROJECTION_ALL,
                whereClause,
                whereArgs,
                null,
                null,
                orderBy,
                limit
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                location = hydrate(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed resolving sequential processing item target from queue bounds.", e);
        }

        return location;
    }

    @Override
    public long getUnpostedLocationsCount() {
        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " = ?";
        String[] whereArgs = { String.valueOf(BackgroundLocation.POST_PENDING) };
        return DatabaseUtils.queryNumEntries(db, LocationEntry.TABLE_NAME, whereClause, whereArgs);
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

        return DatabaseUtils.queryNumEntries(db, LocationEntry.TABLE_NAME, whereClause, whereArgs);
    }

    @Override
    public long persistLocation(BackgroundLocation location) {
        if (location == null) return -1;
        ContentValues values = getContentValues(location);
        return db.insertOrThrow(LocationEntry.TABLE_NAME, LocationEntry.COLUMN_NAME_NULLABLE, values);
    }

    @Override
    public long persistLocation(BackgroundLocation location, int maxRows) {
        if (maxRows <= 0) return -1;

        long rowCount = DatabaseUtils.queryNumEntries(db, LocationEntry.TABLE_NAME);
        if (rowCount < maxRows) {
            return persistLocation(location);
        }

        db.beginTransactionNonExclusive();
        long newRowId = -1;
        try {
            String deleteSql = "DELETE FROM " + LocationEntry.TABLE_NAME +
                    " WHERE " + LocationEntry._ID + " IN " +
                    "(SELECT " + LocationEntry._ID + " FROM " + LocationEntry.TABLE_NAME +
                    " ORDER BY " + LocationEntry.COLUMN_NAME_TIME + " ASC LIMIT ?)";
            
            long excess = (rowCount - maxRows) + 1;
            db.execSQL(deleteSql, new Object[]{excess});

            ContentValues values = getContentValues(location);
            newRowId = db.insertOrThrow(LocationEntry.TABLE_NAME, LocationEntry.COLUMN_NAME_NULLABLE, values);

            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "Atomic database cleanup pruning/insertion transaction failed.", e);
        } finally {
            db.endTransaction();
        }
        
        return newRowId;
    }

    @Override
    public void deleteLocationById(long locationId) {
        if (locationId < 0) return;

        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_NAME_STATUS, BackgroundLocation.DELETED);

        String whereClause = LocationEntry._ID + " = ?";
        String[] whereArgs = { String.valueOf(locationId) };

        db.update(LocationEntry.TABLE_NAME, values, whereClause, whereArgs);
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

        db.update(LocationEntry.TABLE_NAME, values, whereClause, whereArgs);
    }

    @Override
    public int deleteAllLocationsPermanent(long millisBeforeTimeStamp) {
        if (millisBeforeTimeStamp < 0) return 0;

        String whereClause = LocationEntry.COLUMN_NAME_REALTIME + " < ?";
        String[] whereArgs = { String.valueOf(millisBeforeTimeStamp) };
        return db.delete(LocationEntry.TABLE_NAME, whereClause, whereArgs);
    }

    @Override
    public int deleteAllLocations() {
        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_NAME_STATUS, BackgroundLocation.DELETED);
        return db.update(LocationEntry.TABLE_NAME, values, null, null);
    }

    @Override
    public int deleteUnpostedLocations() {
        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_NAME_STATUS, BackgroundLocation.SYNC_PENDING);

        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " = ?";
        String[] whereArgs = { String.valueOf(BackgroundLocation.POST_PENDING) };

        return db.update(LocationEntry.TABLE_NAME, values, whereClause, whereArgs);
    }


    private BackgroundLocation hydrate(Cursor c) {
        BackgroundLocation l = new BackgroundLocation(c.getString(INDEX_PROVIDER));
        
        l.setLocationId(c.getLong(INDEX_ID));
        l.setTime(c.getLong(INDEX_TIME));
        
        if (c.getInt(INDEX_HAS_ACCURACY) == 1) l.setAccuracy(c.getFloat(INDEX_ACCURACY));
        if (c.getInt(INDEX_HAS_SPEED) == 1) l.setSpeed(c.getFloat(INDEX_SPEED));
        if (c.getInt(INDEX_HAS_BEARING) == 1) l.setBearing(c.getFloat(INDEX_BEARING));
        if (c.getInt(INDEX_HAS_ALTITUDE) == 1) l.setAltitude(c.getDouble(INDEX_ALTITUDE));
        if (c.getInt(INDEX_HAS_RADIUS) == 1) l.setRadius(c.getFloat(INDEX_RADIUS));
        
        l.setLatitude(c.getDouble(INDEX_LATITUDE));
        l.setLongitude(c.getDouble(INDEX_LONGITUDE));
        l.setLocationProvider(c.getInt(INDEX_LOCATION_PROVIDER));
        l.setStatus(c.getInt(INDEX_STATUS));
        l.setBatchStartMillis(c.getLong(INDEX_BATCH_START_MILLIS));
        l.setMockFlags(c.getInt(INDEX_MOCK_FLAGS));
        l.setBatteryLevel(c.getInt(INDEX_BATTERY_LEVEL));
        l.setIsCharging(c.getInt(INDEX_CHARGING_FLAG) == 1);
        l.setRealTime(c.getLong(INDEX_REALTIME));
        
        l.setElapsedRealtimeNanos(c.getLong(INDEX_ELAPSEDREALTIMENANO));

        return l;
    }

    private ContentValues getContentValues(BackgroundLocation l) {
        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_NAME_PROVIDER, l.getProvider());
        values.put(LocationEntry.COLUMN_NAME_TIME, l.getTime());
        values.put(LocationEntry.COLUMN_NAME_ACCURACY, l.getAccuracy());
        values.put(LocationEntry.COLUMN_NAME_SPEED, l.getSpeed());
        values.put(LocationEntry.COLUMN_NAME_BEARING, l.getBearing());
        values.put(LocationEntry.COLUMN_NAME_ALTITUDE, l.getAltitude());
        values.put(LocationEntry.COLUMN_NAME_RADIUS, l.getRadius());
        values.put(LocationEntry.COLUMN_NAME_LATITUDE, l.getLatitude());
        values.put(LocationEntry.COLUMN_NAME_LONGITUDE, l.getLongitude());
        values.put(LocationEntry.COLUMN_NAME_HAS_ACCURACY, l.hasAccuracy() ? 1 : 0);
        values.put(LocationEntry.COLUMN_NAME_HAS_SPEED, l.hasSpeed() ? 1 : 0);
        values.put(LocationEntry.COLUMN_NAME_HAS_BEARING, l.hasBearing() ? 1 : 0);
        values.put(LocationEntry.COLUMN_NAME_HAS_ALTITUDE, l.hasAltitude() ? 1 : 0);
        values.put(LocationEntry.COLUMN_NAME_HAS_RADIUS, l.hasRadius() ? 1 : 0);
        values.put(LocationEntry.COLUMN_NAME_LOCATION_PROVIDER, l.getLocationProvider());
        values.put(LocationEntry.COLUMN_NAME_STATUS, l.getStatus());
        values.put(LocationEntry.COLUMN_NAME_BATCH_START_MILLIS, l.getBatchStartMillis());
        values.put(LocationEntry.COLUMN_NAME_MOCK_FLAGS, l.getMockFlags());
        values.put(LocationEntry.COLUMN_NAME_BATTERY_LEVEL, l.getBatteryLevel());
        values.put(LocationEntry.COLUMN_NAME_CHARGING_FLAG, l.getIsCharging() ? 1 : 0);
        values.put(LocationEntry.COLUMN_NAME_REALTIME, l.getRealTime());
        values.put(LocationEntry.COLUMN_NAME_ELAPSEDREALTIMENANO, l.getElapsedRealtimeNanos());

        return values;
    }

    @Override
    public BackgroundLocation getValidLatestLocation() {
        String whereClause = LocationEntry.COLUMN_NAME_STATUS + " <> ?";
        String[] whereArgs = { String.valueOf(BackgroundLocation.DELETED) };
        String orderBy = LocationEntry.COLUMN_NAME_TIME + " DESC";
        String limit = "1";

        BackgroundLocation location = null;
        try (Cursor cursor = db.query(
                LocationEntry.TABLE_NAME,
                LocationEntry.PROJECTION_ALL,
                whereClause,
                whereArgs,
                null,
                null,
                orderBy,
                limit
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                location = hydrate(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract latest valid location record from database layer.", e);
        }

        return location;
    }
}