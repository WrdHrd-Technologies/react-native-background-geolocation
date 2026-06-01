package com.marianhello.logging;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;

import ch.qos.logback.classic.LoggerContext;

public final class UncaughtExceptionLogger implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "UncaughtExceptionLogger";
    
    private static final AtomicBoolean sIsCrashing = new AtomicBoolean(false);
    private static final Object sRegisterLock = new Object();
    
    private static volatile Thread.UncaughtExceptionHandler sDefaultHandler;

    private final org.slf4j.Logger logger;

    public UncaughtExceptionLogger(@NonNull Context context) {
        LoggerManager.initialize();
        this.logger = LoggerManager.getLogger(UncaughtExceptionLogger.class);
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        boolean isFirstCrash = sIsCrashing.compareAndSet(false, true);

        try {
            if (isFirstCrash) {
                logger.error("FATAL CRASH DETECTED ON THREAD: [{}]", thread.getName(), throwable);
            } else {
                Log.e(TAG, "Secondary concurrent thread crash intercepted: " + thread.getName(), throwable);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal logging subsystem breakdown. Dumping raw trace directly to Logcat.", throwable);
        } finally {
            try {
                org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
                if (factory instanceof LoggerContext) {
                    LoggerContext ctx = (LoggerContext) factory;
                    Log.i(TAG, "Flushing asynchronous log memory rings to persistent database store...");
                    ctx.stop(); 
                }
            } catch (Exception ignored) {}

            if (sDefaultHandler != null) {
                sDefaultHandler.uncaughtException(thread, throwable);
            } else {
                System.exit(1);
            }
        }
    }

    public static void register(@NonNull Context context) {
        synchronized (sRegisterLock) {
            Thread.UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
            
            if (!(currentHandler instanceof UncaughtExceptionLogger)) {
                sDefaultHandler = currentHandler;
                Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionLogger(context.getApplicationContext()));
                Log.i(TAG, "Global crash interception firewall successfully deployed.");
            }
        }
    }
}