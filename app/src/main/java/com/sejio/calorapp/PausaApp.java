package com.sejio.calorapp;

import android.app.Application;

/** Starts the logger before anything else runs and records crashes in the local log. */
public final class PausaApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        PausaLog.init(this);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            PausaLog.e("crash", "Uncaught exception in " + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
        PausaLog.i("app", "Started " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
    }
}
