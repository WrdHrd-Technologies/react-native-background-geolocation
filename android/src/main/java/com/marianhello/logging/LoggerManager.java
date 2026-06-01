package com.marianhello.logging;

import android.util.Log;

import androidx.annotation.NonNull;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.android.LogcatAppender;
import ch.qos.logback.classic.android.SQLiteAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;

public final class LoggerManager {
    private static final String TAG = "LoggerManager";
    public static final String SQLITE_APPENDER_NAME = "sqlite";
    private static final String ASYNC_SQLITE_APPENDER_NAME = "async_sqlite";
    private static final String ASYNC_LOGCAT_APPENDER_NAME = "async_logcat";

    private static final Object sLock = new Object();
    private static boolean sIsInitialized = false;

    private LoggerManager() {
        throw new UnsupportedOperationException("Utility infrastructure layer cannot be instantiated.");
    }

    public static void initialize() {
        synchronized (sLock) {
            if (sIsInitialized) return;

            try {
                org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
                if (!(factory instanceof LoggerContext)) {
                    Log.w(TAG, "Log factory provider is not ready. Aborting configuration boot trace.");
                    return;
                }

                LoggerContext context = (LoggerContext) factory;
                context.reset();
                context.setPackagingDataEnabled(false);

                PatternLayoutEncoder encoder = new PatternLayoutEncoder();
                encoder.setContext(context);
                encoder.setPattern("%msg");
                encoder.start();

                LogcatAppender logcatAppender = new LogcatAppender();
                logcatAppender.setContext(context);
                logcatAppender.setEncoder(encoder);
                logcatAppender.start();

                AsyncAppender asyncLogcat = new AsyncAppender();
                asyncLogcat.setName(ASYNC_LOGCAT_APPENDER_NAME);
                asyncLogcat.setContext(context);
                asyncLogcat.setQueueSize(512);
                asyncLogcat.setDiscardingThreshold(0); 
                asyncLogcat.addAppender(logcatAppender);
                asyncLogcat.start();

                Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                root.setLevel(Level.TRACE);
                root.addAppender(asyncLogcat);

                sIsInitialized = true;
                Log.i(TAG, "Logback configuration engine initialized with asynchronous pipelines.");

            } catch (Exception e) {
                Log.e(TAG, "Fatal configuration breakdown processing log framework bootstrapping setup.", e);
            }
        }
    }

    public static void enableDBLogging() {
        if (!sIsInitialized) {
            initialize();
        }

        synchronized (sLock) {
            Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            
            if (root.getAppender(SQLITE_APPENDER_NAME) == null && root.getAppender(ASYNC_SQLITE_APPENDER_NAME) == null) {
                org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
                if (!(factory instanceof LoggerContext)) return;

                LoggerContext context = (LoggerContext) factory;
                
                Log.d(TAG, "Compiling production database log appender target: logback.db");
                SQLiteAppender sqliteAppender = new SQLiteAppender();
                sqliteAppender.setName(SQLITE_APPENDER_NAME);
                sqliteAppender.setMaxHistory("3 days"); 
                sqliteAppender.setContext(context);
                sqliteAppender.start();

                AsyncAppender asyncSqlite = new AsyncAppender();
                asyncSqlite.setName(ASYNC_SQLITE_APPENDER_NAME);
                asyncSqlite.setContext(context);
                asyncSqlite.setQueueSize(1024);
                asyncSqlite.setDiscardingThreshold(0);
                asyncSqlite.addAppender(sqliteAppender);
                asyncSqlite.start();

                root.addAppender(asyncSqlite);
                Log.i(TAG, "Database diagnostic logging pipeline activated securely.");
            }
        }
    }

    public static void disableDBLogging() {
        synchronized (sLock) {
            Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            Appender<ILoggingEvent> asyncAppender = root.getAppender(ASYNC_SQLITE_APPENDER_NAME);
            
            if (asyncAppender != null) {
                asyncAppender.stop(); 
                root.detachAppender(asyncAppender);
                Log.i(TAG, "Database logging tracking network disconnected cleanly.");
            }

            Appender<ILoggingEvent> directAppender = root.getAppender(SQLITE_APPENDER_NAME);
            if (directAppender != null) {
                directAppender.stop();
                root.detachAppender(directAppender);
            }
        }
    }

    @NonNull
    public static org.slf4j.Logger getLogger(@NonNull Class<?> forClass) {
        if (!sIsInitialized) {
            initialize();
        }
        return org.slf4j.LoggerFactory.getLogger(forClass);
    }
}