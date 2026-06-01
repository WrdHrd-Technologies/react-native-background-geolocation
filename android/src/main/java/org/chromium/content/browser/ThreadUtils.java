package org.chromium.content.browser;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;


public final class ThreadUtils {

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private ThreadUtils() {
        throw new UnsupportedOperationException("Utility infrastructure layer cannot be initialized.");
    }

    public static void runOnUiThreadBlocking(@NonNull final Runnable r) {
        if (runningOnUiThread()) {
            r.run();
        } else {
            FutureTask<Void> task = new FutureTask<>(r, null);
            postOnUiThread(task);
            try {
                task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); 
                throw new RuntimeException("Thread interrupted while executing synchronous UI block.", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Exception occurred while awaiting structural runnable completion.", e.getCause());
            }
        }
    }

    @Nullable
    public static <T> T runOnUiThreadBlockingNoException(@NonNull Callable<T> c) {
        try {
            return runOnUiThreadBlocking(c);
        } catch (ExecutionException e) {
            throw new RuntimeException("Error occurred waiting for main thread execution response.", e.getCause());
        }
    }

    @Nullable
    public static <T> T runOnUiThreadBlocking(@NonNull Callable<T> c) throws ExecutionException {
        if (runningOnUiThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }
        
        FutureTask<T> task = new FutureTask<>(c);
        postOnUiThread(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for callable parameters to resolve.", e);
        }
    }

    @NonNull
    public static <T> FutureTask<T> runOnUiThread(@NonNull FutureTask<T> task) {
        if (runningOnUiThread()) {
            task.run();
        } else {
            postOnUiThread(task);
        }
        return task;
    }

    @NonNull
    public static <T> FutureTask<T> runOnUiThread(@NonNull Callable<T> c) {
        return runOnUiThread(new FutureTask<>(c));
    }

    public static void runOnUiThread(@NonNull Runnable r) {
        if (runningOnUiThread()) {
            r.run();
        } else {
            sMainHandler.post(r);
        }
    }

    @NonNull
    public static <T> FutureTask<T> postOnUiThread(@NonNull FutureTask<T> task) {
        sMainHandler.post(task);
        return task;
    }

    public static void postOnUiThread(@NonNull Runnable r) {
        sMainHandler.post(r);
    }

    public static void assertOnUiThread() {
        if (!runningOnUiThread()) {
            throw new IllegalStateException("Execution boundary violation: This operation must run on the UI thread.");
        }
    }

    public static boolean runningOnUiThread() {
        return Looper.getMainLooper().getThread() == Thread.currentThread();
    }
}