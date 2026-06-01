package com.marianhello.bgloc.data.sqlite;

import android.provider.BaseColumns;

import static com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper.COMMA_SEP;
import static com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper.INTEGER_TYPE;
import static com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper.REAL_TYPE;
import static com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper.TEXT_TYPE;

public final class SQLiteLocationContract {
    
    private SQLiteLocationContract() {
        throw new UnsupportedOperationException("Contract schema definition layer cannot be instantiated.");
    }

    public static abstract class LocationEntry implements BaseColumns {
        public static final String TABLE_NAME = "location";
        public static final String COLUMN_NAME_NULLABLE = "NULLHACK";
        public static final String COLUMN_NAME_TIME = "time";
        public static final String COLUMN_NAME_ACCURACY = "accuracy";
        public static final String COLUMN_NAME_SPEED = "speed";
        public static final String COLUMN_NAME_BEARING = "bearing";
        public static final String COLUMN_NAME_ALTITUDE = "altitude";
        public static final String COLUMN_NAME_LATITUDE = "latitude";
        public static final String COLUMN_NAME_LONGITUDE = "longitude";
        public static final String COLUMN_NAME_RADIUS = "radius";
        public static final String COLUMN_NAME_HAS_ACCURACY = "has_accuracy";
        public static final String COLUMN_NAME_HAS_SPEED = "has_speed";
        public static final String COLUMN_NAME_HAS_BEARING = "has_bearing";
        public static final String COLUMN_NAME_HAS_ALTITUDE = "has_altitude";
        public static final String COLUMN_NAME_HAS_RADIUS = "has_radius";
        public static final String COLUMN_NAME_PROVIDER = "provider";
        public static final String COLUMN_NAME_LOCATION_PROVIDER = "service_provider";
        public static final String COLUMN_NAME_STATUS = "valid";
        public static final String COLUMN_NAME_BATCH_START_MILLIS = "batch_start";
        public static final String COLUMN_NAME_MOCK_FLAGS = "mock_flags";
        public static final String COLUMN_NAME_BATTERY_LEVEL = "battery_level";
        public static final String COLUMN_NAME_CHARGING_FLAG = "charging_flag";
        public static final String COLUMN_NAME_REALTIME = "realtime";
        public static final String COLUMN_NAME_ELAPSEDREALTIMENANO = "elapsedrealtimenano";

        public static final String SQL_CREATE_LOCATION_TABLE =
                "CREATE TABLE " + LocationEntry.TABLE_NAME + " (" +
                        LocationEntry._ID + " INTEGER PRIMARY KEY," +
                        LocationEntry.COLUMN_NAME_TIME + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_ACCURACY + " " + REAL_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_SPEED + " " + REAL_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_BEARING + " " + REAL_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_ALTITUDE + " " + REAL_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_LATITUDE + " " + REAL_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_LONGITUDE + " " + REAL_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_RADIUS + " " + REAL_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_HAS_ACCURACY + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_HAS_SPEED + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_HAS_BEARING + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_HAS_ALTITUDE + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_HAS_RADIUS + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_PROVIDER + " " + TEXT_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_LOCATION_PROVIDER + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_STATUS + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_MOCK_FLAGS + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_BATTERY_LEVEL + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_CHARGING_FLAG + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_REALTIME + " " + INTEGER_TYPE + COMMA_SEP +
                        LocationEntry.COLUMN_NAME_ELAPSEDREALTIMENANO + " " + INTEGER_TYPE +
                        " )";

        public static final String SQL_DROP_LOCATION_TABLE =
                "DROP TABLE IF EXISTS " + LocationEntry.TABLE_NAME;

        public static final String SQL_CREATE_LOCATION_TABLE_TIME_IDX =
                "CREATE INDEX IF NOT EXISTS idx_location_time ON " + LocationEntry.TABLE_NAME + " (" + LocationEntry.COLUMN_NAME_TIME + ")";

        public static final String SQL_CREATE_LOCATION_TABLE_BATCH_ID_IDX =
                "CREATE INDEX IF NOT EXISTS idx_location_batch_id ON " + LocationEntry.TABLE_NAME + " (" + LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + ")";
        
        public static final String SQL_CREATE_LOCATION_TABLE_STATUS_TIME_IDX =
                "CREATE INDEX IF NOT EXISTS idx_location_status_time ON " + LocationEntry.TABLE_NAME + 
                " (" + LocationEntry.COLUMN_NAME_STATUS + ", " + LocationEntry.COLUMN_NAME_TIME + ")";

        public static final String DIR_BASEPATH = "locations";
        public static final String ITEM_BASEPATH = "locations/#";

        public static final String[] PROJECTION_ALL = {
                _ID,
                COLUMN_NAME_TIME,
                COLUMN_NAME_ACCURACY,
                COLUMN_NAME_SPEED,
                COLUMN_NAME_BEARING,
                COLUMN_NAME_ALTITUDE,
                COLUMN_NAME_LATITUDE,
                COLUMN_NAME_LONGITUDE,
                COLUMN_NAME_RADIUS,
                COLUMN_NAME_HAS_ACCURACY,
                COLUMN_NAME_HAS_SPEED,
                COLUMN_NAME_HAS_BEARING,
                COLUMN_NAME_HAS_ALTITUDE,
                COLUMN_NAME_HAS_RADIUS,
                COLUMN_NAME_PROVIDER,
                COLUMN_NAME_LOCATION_PROVIDER,
                COLUMN_NAME_STATUS,
                COLUMN_NAME_BATCH_START_MILLIS,
                COLUMN_NAME_MOCK_FLAGS,
                COLUMN_NAME_BATTERY_LEVEL,
                COLUMN_NAME_CHARGING_FLAG,
                COLUMN_NAME_REALTIME,
                COLUMN_NAME_ELAPSEDREALTIMENANO
        };
    }
}