package com.marianhello.bgloc.headless;

import android.content.Context;
import androidx.annotation.Nullable;
import java.lang.ref.WeakReference;

public abstract class AbstractTaskRunner implements TaskRunner {
    private WeakReference<Context> mContextRef;

    public AbstractTaskRunner() {
        mContextRef = new WeakReference<>(null);
    }

    public abstract void runTask(Task task);

    public void setContext(@Nullable Context context) {
        if (context != null) {
            mContextRef = new WeakReference<>(context.getApplicationContext());
        } else {
            mContextRef = new WeakReference<>(null);
        }
    }

    @Nullable
    protected Context getContext() {
        return mContextRef.get();
    }
}