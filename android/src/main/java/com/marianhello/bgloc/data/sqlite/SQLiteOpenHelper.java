package com.marianhello.bgloc.data.sqlite;

import android.content.Context;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract.LocationEntry;
import com.marianhello.bgloc.data.sqlite.SQLiteConfigurationContract.ConfigurationEntry;

import java.util.ArrayList;
import java.util.List;

public class SQLiteOpenHelper extends android.database.sqlite.SQLiteOpenHelper {
    private static final String TAG = "SQLiteOpenHelper";
    public static final String SQLITE_DATABASE_NAME = "cordova_bg_geolocation.db";
    public static final int DATABASE_VERSION = 20;

    public static final String TEXT_TYPE = " TEXT";
    public static final String INTEGER_TYPE = " INTEGER";
    public static final String REAL_TYPE = " REAL";
    public static final String COMMA_SEP = ",";

    private static volatile SQLiteOpenHelper sInstance;

    public static SQLiteOpenHelper getHelper(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (SQLiteOpenHelper.class) {
                if (sInstance == null) {
                    sInstance = new SQLiteOpenHelper(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    public SQLiteOpenHelper(Context context) {
        super(context, SQLITE_DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        try {
            if (!db.isReadOnly()) {
                db.enableWriteAheadLogging();
                Log.d(TAG, "Write-Ahead Logging mode successfully enabled via official SDK framework endpoints.");
            }

            db.setForeignKeyConstraintsEnabled(true);
            db.setMaxSqlCacheSize(100);

        } catch (Exception e) {
            Log.e(TAG, "Critical failure assigning safe SQLite parameters inside onConfigure loop.", e);
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.i(TAG, "Initializing database structures: " + this.getDatabaseName());

        try {
            db.execSQL("PRAGMA journal_mode=WAL;");
        } catch (Exception ignored) {}

        db.beginTransaction();
        try {
            db.execSQL(SQLiteLocationContract.LocationEntry.SQL_CREATE_LOCATION_TABLE);
            db.execSQL(SQLiteConfigurationContract.ConfigurationEntry.SQL_CREATE_CONFIG_TABLE);
            db.execSQL(SQLiteLocationContract.LocationEntry.SQL_CREATE_LOCATION_TABLE_TIME_IDX);
            db.execSQL(SQLiteLocationContract.LocationEntry.SQL_CREATE_LOCATION_TABLE_BATCH_ID_IDX);
            db.execSQL(SQLiteLocationContract.LocationEntry.SQL_CREATE_LOCATION_TABLE_STATUS_TIME_IDX);
            db.execSQL(SQLiteSettingContract.SettingEntry.SQL_CREATE_SETTING_TABLE);
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, "Fatal interruption encountered during table schema instantiation.", e);
            throw e;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "Upgrading tracking database schema from version [" + oldVersion + "] to [" + newVersion + "]");

        try {
            db.execSQL("PRAGMA journal_mode=WAL;");
        } catch (Exception ignored) {}

        List<String> migrations = new ArrayList<>();
        int currentVersion = oldVersion;

        if (currentVersion == 10) {
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_STATUS + INTEGER_TYPE);
            migrations.add(SQLiteLocationContract.LocationEntry.SQL_CREATE_LOCATION_TABLE_TIME_IDX);
            migrations.add(SQLiteConfigurationContract.ConfigurationEntry.SQL_DROP_CONFIG_TABLE);

            migrations.add("CREATE TABLE " + ConfigurationEntry.TABLE_NAME + " (" +
                    ConfigurationEntry._ID + " INTEGER PRIMARY KEY," +
                    ConfigurationEntry.COLUMN_NAME_RADIUS + REAL_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_DISTANCE_FILTER + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_DESIRED_ACCURACY + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_DEBUG + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_NOTIF_TITLE + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_NOTIF_TEXT + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_NOTIF_ICON_SMALL + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_NOTIF_ICON_LARGE + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_NOTIF_COLOR + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_STOP_TERMINATE + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_STOP_ON_STILL + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_START_BOOT + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_START_FOREGROUND + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_LOCATION_PROVIDER + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_INTERVAL + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_FASTEST_INTERVAL + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_ACTIVITIES_INTERVAL + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_URL + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_SYNC_URL + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_SYNC_THRESHOLD + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_HEADERS + TEXT_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_MAX_LOCATIONS + INTEGER_TYPE + COMMA_SEP +
                    ConfigurationEntry.COLUMN_NAME_HEARTBEAT_INTERVAL + INTEGER_TYPE + ")");
            currentVersion = 11;
        }

        if (currentVersion == 11) {
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_RADIUS + REAL_TYPE);
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_HAS_ACCURACY + INTEGER_TYPE);
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_HAS_SPEED + INTEGER_TYPE);
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_HAS_BEARING + INTEGER_TYPE);
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_HAS_ALTITUDE + INTEGER_TYPE);
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_HAS_RADIUS + INTEGER_TYPE);
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + INTEGER_TYPE);
            migrations.add(SQLiteLocationContract.LocationEntry.SQL_CREATE_LOCATION_TABLE_BATCH_ID_IDX);
            migrations.add("UPDATE " + LocationEntry.TABLE_NAME + " SET " +
                    LocationEntry.COLUMN_NAME_HAS_ACCURACY + "= 1," +
                    LocationEntry.COLUMN_NAME_HAS_SPEED + "= 1," +
                    LocationEntry.COLUMN_NAME_HAS_BEARING + "= 1," +
                    LocationEntry.COLUMN_NAME_HAS_ALTITUDE + "= 1," +
                    LocationEntry.COLUMN_NAME_HAS_RADIUS + "= 1");
            currentVersion = 12;
        }

        if (currentVersion == 12) {
            migrations.add("ALTER TABLE " + ConfigurationEntry.TABLE_NAME + " ADD COLUMN " + ConfigurationEntry.COLUMN_NAME_TEMPLATE + TEXT_TYPE);
            currentVersion = 13;
        }

        if (currentVersion == 13) {
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_MOCK_FLAGS + INTEGER_TYPE);
            currentVersion = 14;
        }

        if (currentVersion == 14) {
            migrations.add("ALTER TABLE " + ConfigurationEntry.TABLE_NAME + " ADD COLUMN " + ConfigurationEntry.COLUMN_NAME_NOTIFICATIONS_ENABLED + INTEGER_TYPE);
            currentVersion = 15;
        }

        if (currentVersion == 15) {
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_BATTERY_LEVEL + INTEGER_TYPE);
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_CHARGING_FLAG + INTEGER_TYPE);
            currentVersion = 16;
        }

        if (currentVersion == 16) {
            migrations.add("ALTER TABLE " + ConfigurationEntry.TABLE_NAME + " ADD COLUMN " + ConfigurationEntry.COLUMN_NAME_HEARTBEAT_INTERVAL + INTEGER_TYPE);
            currentVersion = 17;
        }

        if (currentVersion == 17) {
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_REALTIME + INTEGER_TYPE);
            migrations.add("ALTER TABLE " + LocationEntry.TABLE_NAME + " ADD COLUMN " + LocationEntry.COLUMN_NAME_ELAPSEDREALTIMENANO + INTEGER_TYPE);
            currentVersion = 18;
        }

        if (currentVersion == 18) {
            migrations.add(SQLiteSettingContract.SettingEntry.SQL_CREATE_SETTING_TABLE);
            currentVersion = 19;
        }

        if (currentVersion == 19) {
            migrations.add(SQLiteLocationContract.LocationEntry.SQL_CREATE_LOCATION_TABLE_STATUS_TIME_IDX);
            currentVersion = 20;
        }

        if (!migrations.isEmpty()) {
            db.beginTransaction();
            try {
                for (String sql : migrations) {
                    Log.d(TAG, "Executing upgrade statement: " + sql);
                    db.execSQL(sql);
                }
                db.setTransactionSuccessful();
                Log.i(TAG, "Database upgrade transaction finalized successfully.");
            } catch (SQLException e) {
                Log.e(TAG, "FATAL ERROR: Failed executing storage schema upgrades. Rolling back all alterations.", e);
                throw e;
            } finally {
                db.endTransaction();
            }
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Downgrade requested. Purging all local structural tables to avoid collision anomalies.");
        db.beginTransaction();
        try {
            db.execSQL(SQLiteLocationContract.LocationEntry.SQL_DROP_LOCATION_TABLE);
            db.execSQL(SQLiteConfigurationContract.ConfigurationEntry.SQL_DROP_CONFIG_TABLE);
            db.execSQL(SQLiteSettingContract.SettingEntry.SQL_DROP_SETTING_TABLE);
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, "Failed purging database layers safely during downgrade.", e);
        } finally {
            db.endTransaction();
        }
        onCreate(db);
    }

    public void execAndLogSql(SQLiteDatabase db, String sql) {
        if (db == null || TextUtils.isEmpty(sql)) return;

        if (sql.toLowerCase().contains("journal_mode") && sql.toLowerCase().contains("truncate")) {
            Log.w(TAG, "Intercepted and suppressed third-party TRUNCATE override attempt to maintain WAL mode stability.");
            return;
        }

        Log.d(TAG, "Dispatching custom SQL statement: " + sql);
        try {
            db.execSQL(sql);
        } catch (SQLException e) {
            Log.e(TAG, "Execution exception thrown by database engine for string: " + sql, e);
        }
    }
}