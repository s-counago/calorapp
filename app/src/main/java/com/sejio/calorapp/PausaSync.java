package com.sejio.calorapp;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;

/**
 * Schedules sync rounds with WorkManager: after every local change, when the app opens and every
 * 30 minutes, always waiting for a network. Without coverage the work simply stays queued.
 */
final class PausaSync {
    static final String ACTION_CHANGED = "com.sejio.calorapp.PAUSA_DATA_CHANGED";
    private static final String NOW = "pausa-sync";
    private static final String PERIODIC = "pausa-sync-periodic";

    private PausaSync() {}

    private static Constraints network() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    /** Syncs in a couple of seconds, or as soon as there is coverage. */
    static void requestSoon(Context context) {
        if (!CloudAuth.signedIn(context)) return;
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
                    new OneTimeWorkRequest.Builder(SyncWorker.class).setConstraints(network())
                            .setInitialDelay(2, TimeUnit.SECONDS)
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build());
        } catch (IllegalStateException notInitialised) {
            // WorkManager is not available in some instrumented tests.
        }
    }

    static void schedulePeriodic(Context context) {
        if (!CloudAuth.signedIn(context)) return;
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    new PeriodicWorkRequest.Builder(SyncWorker.class, 30, TimeUnit.MINUTES).setConstraints(network()).build());
        } catch (IllegalStateException notInitialised) {
            // See requestSoon.
        }
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(NOW);
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC);
    }

    /** What the diagnostics screen and the cloud screen show. */
    static String status(Context context) {
        if (!CloudAuth.prefs(context).contains("token")) return "Sin conectar";
        if (CloudAuth.authError(context)) return "Vuelve a conectar";
        PausaDb db = PausaDb.get(context);
        int pending = db.count("SELECT COUNT(*) FROM outbox");
        String error = db.state("last_error", "");
        long last = Long.parseLong(db.state("last_success_at", "0"));
        String when = last == 0 ? "aún no sincronizado" : "sincronizado " + ago(last);
        if (!error.isEmpty() && Long.parseLong(db.state("last_error_at", "0")) > last) {
            return (pending > 0 ? pending + " pendientes · " : "") + "sin conexión";
        }
        return pending > 0 ? pending + " pendientes · " + when : "Al día · " + when;
    }

    static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        if (minutes < 1) return "ahora mismo";
        if (minutes < 60) return "hace " + minutes + " min";
        if (minutes < 48 * 60) return "hace " + (minutes / 60) + " h";
        return "hace " + (minutes / 1440) + " días";
    }

    /** One sync round: push, pull, push, then upload queued logs. */
    public static final class SyncWorker extends Worker {
        public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
            super(context, parameters);
        }

        @NonNull @Override public Result doWork() {
            switch (round(getApplicationContext(), getRunAttemptCount())) {
                case RETRY: return Result.retry();
                case FAILURE: return Result.failure();
                default: return Result.success();
            }
        }

        enum Outcome { SUCCESS, RETRY, FAILURE }

        /** One round outside WorkManager too (tests, diagnostics). */
        static synchronized Outcome round(Context context, int attempt) {
            PausaLog.init(context);
            LegacyImport.runOnce(context);
            String token = CloudAuth.token(context);
            if (token == null || CloudAuth.authError(context)) return Outcome.SUCCESS;
            PausaDb db = PausaDb.get(context);
            String version = BuildConfig.VERSION_NAME;
            SyncEngine engine = new SyncEngine(HttpUrl.get(BuildConfig.PAUSA_API_URL), token, version,
                    (level, message, data) -> PausaLog.event(level, "sync", message, data));
            long started = System.currentTimeMillis();
            try {
                SyncEngine.Result result = engine.run(db.new Storage());
                db.setState("last_success_at", String.valueOf(System.currentTimeMillis()));
                db.setState("last_error", "");
                PausaLog.event("info", "sync", "Sync finished", CloudAuth.json("pushed", result.pushed, "stale", result.stale,
                        "pulled", result.pulled, "applied", result.applied, "cursor", result.cursor, "ms", System.currentTimeMillis() - started,
                        "attempt", attempt));
                changed(context, result.applied);
                uploadLogs(db, engine);
                return Outcome.SUCCESS;
            } catch (SyncEngine.AuthFailure rejected) {
                CloudAuth.markRejected(context, rejected.code);
                recordError(db, "auth:" + rejected.code);
                PausaLog.w("sync", "Backend rejected the device token: " + rejected.code);
                changed(context, 0);
                return Outcome.FAILURE;
            } catch (Exception failure) {
                recordError(db, failure.getClass().getSimpleName() + ": " + failure.getMessage());
                PausaLog.w("sync", "Sync failed (attempt " + attempt + ")", failure);
                changed(context, 0);
                // A 4xx other than 401 means this app sent something the server refuses: retrying will not help.
                if (failure instanceof SyncEngine.HttpFailure && ((SyncEngine.HttpFailure) failure).status < 500) return Outcome.FAILURE;
                return Outcome.RETRY;
            }
        }

        /** Tells open screens that the sync state changed; `applied` > 0 means data changed too. */
        private static void changed(Context context, int applied) {
            // Counters added on the web or by an agent show up in the notification too.
            if (applied > 0) NotificationHelper.show(context);
            context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()).putExtra("applied", applied));
        }

        private static void recordError(PausaDb db, String error) {
            db.setState("last_error", error);
            db.setState("last_error_at", String.valueOf(System.currentTimeMillis()));
        }

        /** Sends queued warnings, errors and sync events. A failure here never fails the sync. */
        static void uploadLogs(PausaDb db, SyncEngine engine) {
            for (int round = 0; round < 5; round++) {
                List<Map<String, Object>> rows = db.rows("SELECT * FROM log_queue ORDER BY id LIMIT 200");
                if (rows.isEmpty()) return;
                try {
                    JSONArray entries = new JSONArray();
                    for (Map<String, Object> row : rows) {
                        JSONObject entry = new JSONObject().put("at", row.get("at")).put("level", row.get("level"))
                                .put("tag", row.get("tag")).put("message", row.get("message"));
                        if (row.get("data") != null) entry.put("data", new JSONObject((String) row.get("data")));
                        entries.put(entry);
                    }
                    engine.uploadLogs(entries);
                    List<String> ids = new ArrayList<>();
                    for (Map<String, Object> row : rows) ids.add(String.valueOf(row.get("id")));
                    db.getWritableDatabase().execSQL("DELETE FROM log_queue WHERE id <= " + ids.get(ids.size() - 1));
                } catch (Exception failure) {
                    android.util.Log.w(PausaLog.TAG, "[sync] Log upload failed", failure);
                    return;
                }
            }
        }
    }
}
