package com.sejio.calorapp;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

public final class CalendarSyncJob extends JobService {
    private Thread worker;

    @Override public boolean onStartJob(JobParameters parameters) {
        final long generation;
        synchronized (CalendarSync.class) {
            CalendarSync.running = true;
            generation = CalendarSync.prefs(this).getLong("generation", 0);
        }
        worker = new Thread(() -> {
            boolean retry;
            try {
                synchronized (CalendarSync.NETWORK_LOCK) {
                    if (Thread.currentThread().isInterrupted()) return;
                    retry = CalendarSync.run(this);
                }
            }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
            catch (Exception failure) {
                CalendarSync.prefs(this).edit().putString("status", "Pendiente de sincronizar · revisa la conexión o el acceso a Google").apply();
                retry = true;
            }
            boolean again = retry;
            Thread finished = Thread.currentThread();
            if (!finished.isInterrupted()) new Handler(Looper.getMainLooper()).post(() -> {
                synchronized (CalendarSync.class) {
                    if (worker != finished) return;
                    CalendarSync.running = false;
                    worker = null;
                    jobFinished(parameters, CalendarSync.enabled(this) && (again || generation != CalendarSync.prefs(this).getLong("generation", 0)));
                }
            });
        }, "pausa-calendar");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        synchronized (CalendarSync.class) {
            if (worker != null) worker.interrupt();
            worker = null;
            CalendarSync.running = false;
        }
        return CalendarSync.enabled(this);
    }
}
