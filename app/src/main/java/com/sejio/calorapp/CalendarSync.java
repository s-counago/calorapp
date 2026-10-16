package com.sejio.calorapp;

import android.accounts.Account;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okio.ByteString;

final class CalendarSync {
    static final Object NETWORK_LOCK = new Object();
    static boolean running;
    static final int JOB = 7134;
    static final String EVENTS_SCOPE = "https://www.googleapis.com/auth/calendar.app.created";
    static final String LIST_SCOPE = "https://www.googleapis.com/auth/calendar.calendarlist.readonly";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("google_calendar", Context.MODE_PRIVATE); }
    static List<Scope> scopes() { return Arrays.asList(new Scope(EVENTS_SCOPE), new Scope(LIST_SCOPE)); }
    static AuthorizationRequest authorization(String account) {
        return AuthorizationRequest.builder().setAccount(new Account(account, "com.google")).setRequestedScopes(scopes()).build();
    }
    static String status(Context c) { return prefs(c).getString("status", "Sin conectar"); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }

    static synchronized void request(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putLong("generation", p.getLong("generation", 0) + 1).apply();
        if (!enabled(c)) return;
        p.edit().putString("status", "Pendiente de sincronizar").apply();
        if (!running) schedule(c);
    }

    static void schedule(Context c) {
        if (!enabled(c)) return;
        try {
            JobScheduler scheduler = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null && scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(c, CalendarSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(5000).setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) == JobScheduler.RESULT_SUCCESS) return;
        } catch (SecurityException | IllegalStateException failure) {
            android.util.Log.w("CalendarSync", "Calendar job could not be scheduled", failure);
        }
        prefs(c).edit().putString("status", "No se pudo programar la sincronización · vuelve a abrir Pausa").apply();
    }

    static synchronized void resume(Context c) {
        if (!enabled(c) || running) return;
        try {
            JobScheduler scheduler = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) for (JobInfo job : scheduler.getAllPendingJobs()) if (job.getId() == JOB) return;
            schedule(c);
        } catch (SecurityException | IllegalStateException failure) {
            prefs(c).edit().putString("status", "No se pudo programar la sincronización · vuelve a abrir Pausa").apply();
        }
    }

    static boolean run(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        if (!enabled(c)) return false;
        long generation = p.getLong("generation", 0);
        String account = p.getString("account", "");
        AuthorizationResult authorization = Tasks.await(Identity.getAuthorizationClient(c).authorize(authorization(account)), 45, TimeUnit.SECONDS);
        if (authorization.hasResolution() || authorization.getAccessToken() == null || !hasScopes(authorization)) {
            p.edit().putString("status", "Necesita permiso · abre Conectar Google Calendar").apply(); return false;
        }
        String token = authorization.getAccessToken();
        CalendarApi api = new CalendarApi(token);
        try {
            String calendar = p.getString("calendar", "");
            if (calendar.isEmpty()) {
                String marker = p.getString("marker", "");
                if (marker.isEmpty()) {
                    marker = "Calendario de Pausa · " + UUID.randomUUID();
                    if (!p.edit().putString("marker", marker).commit()) throw new IllegalStateException();
                }
                calendar = api.findCalendar(marker);
                if (calendar.isEmpty()) {
                    if (p.getBoolean("creating", false)) {
                        p.edit().putString("status", "Comprobando la creación del calendario; vuelve a sincronizar en unos minutos").apply();
                        return true;
                    }
                    if (!p.edit().putBoolean("creating", true).commit()) throw new IllegalStateException();
                    try { calendar = api.createCalendar(marker, TimeZone.getDefault().getID()); }
                    catch (CalendarApi.Failure failure) {
                        if (failure.status >= 400 && failure.status < 500) p.edit().putBoolean("creating", false).commit();
                        throw failure;
                    }
                }
                if (!p.edit().putString("calendar", calendar).putBoolean("creating", false).commit()) throw new IllegalStateException();
            }
            if (!syncTimedTasks(c, api, calendar, account)) return false;
            // Tasks made from a habit (feature habit_tasks) go in as all-day events on their day, unless they have a time.
            for (Map<String, Object> row : PausaDb.get(c).rows("SELECT id, title, plan_date, plan_time, status, deleted FROM tasks WHERE habit_id IS NOT NULL")) {
                if (Thread.currentThread().isInterrupted() || !enabled(c) || !account.equals(p.getString("account", ""))) return false;
                String uid = (String) row.get("id");
                String key = "synced.task." + uid;
                String prior = p.getString(key, "");
                String remote = p.getString("event.task." + uid, CalendarEvent.idFor(uid));
                boolean removed = Long.valueOf(1).equals(row.get("deleted")) || row.get("plan_date") == null || "cancelled".equals(row.get("status"))
                        || row.get("plan_time") != null; // With a set time it goes in as a timed event instead.
                if (removed) {
                    if (prior.equals("deleted") || prior.isEmpty()) {
                        if (prior.isEmpty() && !p.edit().putString(key, "deleted").commit()) throw new IllegalStateException();
                        continue;
                    }
                    api.delete(calendar, remote);
                    if (!p.edit().putString(key, "deleted").commit()) throw new IllegalStateException();
                    continue;
                }
                if (prior.equals("deleted")) {
                    remote = UUID.randomUUID().toString().replace("-", "");
                    if (!p.edit().putString("event.task." + uid, remote).putString(key, "").commit()) throw new IllegalStateException();
                }
                JSONObject body = CalendarEvent.allDay(remote, (String) row.get("title"), (String) row.get("plan_date"));
                String fingerprint = ByteString.encodeUtf8(body.toString()).sha256().hex();
                if (fingerprint.equals(p.getString(key, ""))) continue;
                api.upsert(calendar, body);
                if (!p.edit().putString(key, fingerprint).commit()) throw new IllegalStateException();
            }
            boolean again = generation != p.getLong("generation", 0);
            p.edit().putString("status", again ? "Pendiente de sincronizar" : "Sincronizado con el calendario Pausa")
                    .putLong("lastSync", System.currentTimeMillis()).apply();
            return again;
        } catch (CalendarApi.Failure failure) {
            if (failure.status == 401) {
                Tasks.await(Identity.getAuthorizationClient(c).clearToken(ClearTokenRequest.builder().setToken(token).build()), 15, TimeUnit.SECONDS);
                p.edit().putString("status", "Renovando el acceso a Google Calendar").apply(); return true;
            }
            if (failure.status == 403 || failure.status == 404 || failure.status == 410) {
                p.edit().putString("status", "Google Calendar necesita revisión (" + failure.status + ") · abre la conexión").apply(); return false;
            }
            throw failure;
        }
    }

    /**
     * Tasks with a set time (plan_time) go in as timed events. Each one is keyed by its task id, so a
     * time set on the web or by an agent reaches Calendar too. The old local blocks that were moved
     * onto their tasks keep their event; the ones from today on that could not move are deleted.
     */
    private static boolean syncTimedTasks(Context c, CalendarApi api, String calendar, String account) throws Exception {
        SharedPreferences p = prefs(c);
        List<TaskScheduleStore.Schedule> timed = TaskScheduleStore.all(c);
        Map<String, String> moved = TaskScheduleStore.movedBlocks(c);
        if (!p.getBoolean("blocks_moved", false)) {
            SharedPreferences.Editor editor = p.edit();
            for (Map.Entry<String, String> block : moved.entrySet()) {
                String prior = p.getString("synced." + block.getKey(), "");
                if (prior.isEmpty() || prior.equals("deleted")) continue;
                editor.putString("event.time." + block.getValue(), p.getString("event." + block.getKey(), block.getKey()))
                        .putString("synced.time." + block.getValue(), prior);
            }
            if (!editor.putBoolean("blocks_moved", true).commit()) throw new IllegalStateException();
        }
        long today = TaskScheduleStore.start(DayPlanStore.day(0), 0);
        for (TaskScheduleStore.LegacyBlock block : TaskScheduleStore.legacyBlocks(c)) {
            if (moved.containsKey(block.id)) continue;
            String key = "synced." + block.id;
            String prior = p.getString(key, "");
            if (prior.isEmpty() || prior.equals("deleted") || (!block.deleted && block.start < today)) continue;
            if (Thread.currentThread().isInterrupted() || !enabled(c) || !account.equals(p.getString("account", ""))) return false;
            api.delete(calendar, p.getString("event." + block.id, block.id));
            if (!p.edit().putString(key, "deleted").commit()) throw new IllegalStateException();
        }
        Set<String> live = new HashSet<>();
        for (TaskScheduleStore.Schedule row : timed) {
            if (Thread.currentThread().isInterrupted() || !enabled(c) || !account.equals(p.getString("account", ""))) return false;
            TaskStore.Task task = TaskStore.find(c, row.taskId);
            if (task == null || task.following) continue;
            live.add(row.id);
            String key = "synced.time." + row.id;
            String prior = p.getString(key, "");
            String remote = p.getString("event.time." + row.id, CalendarEvent.idFor(row.id + "@time"));
            if (prior.equals("deleted")) {
                remote = UUID.randomUUID().toString().replace("-", "");
                if (!p.edit().putString("event.time." + row.id, remote).putString(key, "").commit()) throw new IllegalStateException();
            }
            JSONObject body = CalendarEvent.body(remote, task.text, row.start, row.minutes, row.zone);
            String fingerprint = ByteString.encodeUtf8(body.toString()).sha256().hex();
            if (fingerprint.equals(p.getString(key, ""))) continue;
            api.upsert(calendar, body);
            if (!p.edit().putString(key, fingerprint).commit()) throw new IllegalStateException();
        }
        // Times taken off, tasks deleted or moved to Esperando: their event goes.
        for (String key : new ArrayList<>(p.getAll().keySet())) {
            if (!key.startsWith("synced.time.")) continue;
            String uid = key.substring("synced.time.".length());
            if (live.contains(uid) || "deleted".equals(p.getString(key, ""))) continue;
            if (Thread.currentThread().isInterrupted() || !enabled(c) || !account.equals(p.getString("account", ""))) return false;
            api.delete(calendar, p.getString("event.time." + uid, CalendarEvent.idFor(uid + "@time")));
            if (!p.edit().putString(key, "deleted").commit()) throw new IllegalStateException();
        }
        return true;
    }

    static boolean hasScopes(AuthorizationResult result) {
        return result.getGrantedScopes().contains(EVENTS_SCOPE) && result.getGrantedScopes().contains(LIST_SCOPE);
    }
}
