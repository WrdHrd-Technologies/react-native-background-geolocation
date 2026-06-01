package com.marianhello.logging;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.event.Level;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.db.names.ColumnName;
import ch.qos.logback.classic.db.names.DBNameResolver;
import ch.qos.logback.classic.db.names.DefaultDBNameResolver;
import ch.qos.logback.classic.db.names.TableName;
import ch.qos.logback.core.CoreConstants;


public final class DBLogReader {
    private static final String TAG = "DBLogReader";
    public static final String DB_FILENAME = "logback.db";

    private final Context mContext;
    private final DefaultDBNameResolver mDbNameResolver;
    private final Object mLock = new Object();
    private SQLiteDatabase mDatabase;

    public DBLogReader(@NonNull Context context) {
        this.mContext = context.getApplicationContext();
        this.mDbNameResolver = new DefaultDBNameResolver();
    }

 
    private SQLiteDatabase openDatabase() throws SQLException {
        synchronized (mLock) {
            if (mDatabase != null && mDatabase.isOpen()) {
                return mDatabase;
            }

            try {
                File dbFile = mContext.getDatabasePath(DB_FILENAME);
                mDatabase = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                return mDatabase;
            } catch (SQLiteException e) {
                throw new SQLException("Database infrastructure connection rejected tracking log reads.", e);
            }
        }
    }

    @NonNull
    public Collection<LogEntry> getEntries(int limit, int fromLogEntryId, @NonNull Level minLevel) {
        try {
            return getDbEntriesUnified(limit, fromLogEntryId, minLevel);
        } catch (Exception e) {
            Log.e(TAG, "Fatal barrier breakdown encountered during log extraction processing.", e);
            return Collections.emptyList();
        }
    }

    
    private Collection<LogEntry> getDbEntriesUnified(int limit, int fromLogEntryId, Level minLevel) throws SQLException {
        SQLiteDatabase db = openDatabase();
        
        String tEvent = mDbNameResolver.getTableName(TableName.LOGGING_EVENT);
        String tException = mDbNameResolver.getTableName(TableName.LOGGING_EVENT_EXCEPTION);

        String cEventId = mDbNameResolver.getColumnName(ColumnName.EVENT_ID);
        String cTimestmp = mDbNameResolver.getColumnName(ColumnName.TIMESTMP);
        String cMessage = mDbNameResolver.getColumnName(ColumnName.FORMATTED_MESSAGE);
        String cLogger = mDbNameResolver.getColumnName(ColumnName.LOGGER_NAME);
        String cLevel = mDbNameResolver.getColumnName(ColumnName.LEVEL_STRING);
        String cTraceId = mDbNameResolver.getColumnName(ColumnName.I);
        String cTraceLine = mDbNameResolver.getColumnName(ColumnName.TRACE_LINE);

        List<String> queryArgs = new ArrayList<>();
        List<String> validLevels = getLevelsAboveOrEqual(minLevel);
        
        StringBuilder levelPlaceholders = new StringBuilder();
        for (int i = 0; i < validLevels.size(); i++) {
            levelPlaceholders.append(i == 0 ? "?" : ",?");
            queryArgs.add(validLevels.get(i));
        }

        StringBuilder query = new StringBuilder();
        query.append("SELECT ")
             .append("e.").append(cEventId).append(" AS event_id, ")
             .append("e.").append(cTimestmp).append(", ")
             .append("e.").append(cMessage).append(", ")
             .append("e.").append(cLogger).append(", ")
             .append("e.").append(cLevel).append(", ")
             .append("x.").append(cTraceLine)
             .append(" FROM ").append(tEvent).append(" e")
             .append(" LEFT JOIN ").append(tException).append(" x")
             .append(" ON e.").append(cEventId).append(" = x.").append(cTraceId)
             .append(" WHERE e.").append(cLevel).append(" IN (").append(levelPlaceholders).append(")");

        if (fromLogEntryId > 0) {
            if (limit >= 0) {
                query.append(" AND e.").append(cEventId).append(" < ?");
            } else {
                query.append(" AND e.").append(cEventId).append(" > ?");
            }
            queryArgs.add(String.valueOf(fromLogEntryId));
        }

        if (limit < 0) {
            query.append(" ORDER BY e.").append(cTimestmp).append(" ASC, e.").append(cEventId).append(" ASC, x.").append(cTraceId).append(" ASC");
        } else {
            query.append(" ORDER BY e.").append(cTimestmp).append(" DESC, e.").append(cEventId).append(" DESC, x.").append(cTraceId).append(" ASC");
        }

        if (limit != 0) {
            query.append(" LIMIT ").append(Math.abs(limit));
        }

        Map<Integer, LogEntry> entryMap = new HashMap<>();
        String[] selectionArgs = queryArgs.toArray(new String[0]);

        try (Cursor cursor = db.rawQuery(query.toString(), selectionArgs)) {
            if (cursor == null) return Collections.emptyList();

            int idxId = cursor.getColumnIndexOrThrow("event_id");
            int idxTime = cursor.getColumnIndexOrThrow(cTimestmp);
            int idxMsg = cursor.getColumnIndexOrThrow(cMessage);
            int idxLogger = cursor.getColumnIndexOrThrow(cLogger);
            int idxLvl = cursor.getColumnIndexOrThrow(cLevel);
            int idxTrace = cursor.getColumnIndexOrThrow(cTraceLine);

            while (cursor.moveToNext()) {
                int eventId = cursor.getInt(idxId);
                LogEntry entry = entryMap.get(eventId);

                if (entry == null) {
                    entry = new LogEntry();
                    entry.setContext(0);
                    entry.setId(eventId);
                    entry.setTimestamp(cursor.getLong(idxTime));
                    entry.setMessage(cursor.getString(idxMsg));
                    entry.setLoggerName(cursor.getString(idxLogger));
                    entry.setLevel(cursor.getString(idxLvl));
                    entry.setStackTrace(new ArrayList<>());
                    
                    entryMap.put(eventId, entry);
                }

                // FIXED: Accumulates multi-line stack trace fragments instantly inside the unified JOIN loop pass
                if (!cursor.isNull(idxTrace)) {
                    Collection<String> traceList = entry.getStackTrace();
                    if (traceList instanceof List) {
                        ((List<String>) traceList).add(cursor.getString(idxTrace));
                    }
                }
            }
        } catch (SQLiteException e) {
            throw new SQLException("Database query translation mapping execution failed.", e);
        }

        return entryMap.values();
    }

    @NonNull
    private List<String> getLevelsAboveOrEqual(@NonNull Level targetLevel) {
        List<String> levels = new ArrayList<>();
        for (Level l : Level.values()) {
            if (l.toInt() >= targetLevel.toInt()) {
                levels.add(l.toString());
            }
        }
        return levels;
    }

    public void close() {
        synchronized (mLock) {
            if (mDatabase != null && mDatabase.isOpen()) {
                mDatabase.close();
                mDatabase = null;
            }
        }
    }
}