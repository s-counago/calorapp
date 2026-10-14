package com.sejio.calorapp;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves the data the app kept in SharedPreferences before the backend existed into PausaDb, once,
 * and queues all of it for the first sync. Old ids become local ids, so timed schedules, Calendar
 * events and anything else that points at a task keeps working. The old preferences are left in
 * place as a backup.
 */
final class LegacyImport {
    private static volatile boolean done;

    private LegacyImport() {}

    static void runOnce(Context context) {
        if (done) return;
        synchronized (LegacyImport.class) {
            if (done) return;
            PausaDb db = PausaDb.get(context);
            if (db.state("imported", null) == null) {
                try {
                    run(context, db);
                } catch (RuntimeException failure) {
                    // Leave the flag unset so the next start tries again; the app keeps working on an empty base.
                    PausaLog.e("import", "Import from SharedPreferences failed", failure);
                    return;
                }
            }
            done = true;
        }
    }

    /** For instrumented tests that wipe PausaDb and then read again. */
    static void markDone() {
        done = true;
    }

    /** For instrumented tests that write old preferences and import them again. */
    static void forgetForTests() {
        done = false;
    }

    private static void run(Context context, PausaDb pausa) {
        long now = System.currentTimeMillis();
        int tasks = 0, planned = 0, habits = 0, checks = 0;
        SQLiteDatabase db = pausa.getWritableDatabase();
        db.beginTransaction();
        try {
            Map<Long, String> taskIds = new HashMap<>();
            try {
                JSONArray stored = new JSONArray(context.getSharedPreferences(TaskStore.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(TaskStore.KEY_TASKS, "[]"));
                for (int i = 0; i < stored.length(); i++) {
                    JSONObject task = stored.getJSONObject(i);
                    long id = task.getLong("id");
                    if (taskIds.containsKey(id)) continue;
                    boolean isDone = task.optBoolean("done", false);
                    Map<String, Object> data = new HashMap<>();
                    data.put("title", task.getString("text"));
                    data.put("status", isDone ? "done" : "todo");
                    data.put("completed_at", isDone ? now : null);
                    // Old ids were creation times, which keeps the list in its order.
                    data.put("created_at", id);
                    data.put("following", task.optBoolean("following", false) ? 1 : 0);
                    data.put("waiting_for", blank(task.optString("waitingFor")));
                    data.put("expected_date", blank(task.optString("expectedDate")));
                    data.put("review_date", blank(task.optString("reviewDate")));
                    String uid = PausaDb.uuid();
                    pausa.write(db, "tasks", uid, data, false, id, now);
                    PausaDb.enqueue(db, "tasks", uid);
                    taskIds.put(id, uid);
                    tasks++;
                }
            } catch (JSONException damaged) {
                PausaLog.w("import", "Stored tasks were not valid JSON", damaged);
            }

            // Day plans: the latest day a task was planned on wins.
            Map<String, Map<String, Object>> plans = new HashMap<>();
            for (Map.Entry<String, List<List<Long>>> day : DayPlanStore.legacyBoards(context).entrySet()) {
                for (int section = 0; section < 3; section++) {
                    List<Long> ids = day.getValue().get(section);
                    for (int j = 0; j < ids.size(); j++) {
                        String uid = taskIds.get(ids.get(j));
                        if (uid == null) continue;
                        Map<String, Object> plan = new HashMap<>();
                        plan.put("plan_date", day.getKey());
                        plan.put("plan_slot", SyncSchema.SLOTS[section]);
                        plan.put("position", (double) (j + 1));
                        plans.put(uid, plan);
                    }
                }
            }
            for (Map.Entry<String, Map<String, Object>> plan : plans.entrySet()) {
                pausa.write(db, "tasks", plan.getKey(), plan.getValue(), false, null, now);
                planned++;
            }

            int position = 0;
            for (HabitStore.Habit habit : HabitStore.legacy(context)) {
                habit.uid = PausaDb.uuid();
                Map<String, Object> data = new HashMap<>();
                data.put("name", habit.name);
                data.put("weekly_goal", habit.weeklyGoal);
                data.put("created_day", habit.createdDay);
                data.put("archived", 0);
                data.put("position", (double) ++position);
                pausa.write(db, "habits", habit.uid, data, false, habit.id, now);
                PausaDb.enqueue(db, "habits", habit.uid);
                for (String day : habit.completedDays) {
                    Map<String, Object> check = new HashMap<>();
                    check.put("habit_id", habit.uid);
                    check.put("day", day);
                    String id = habit.uid + ":" + day;
                    pausa.write(db, "habit_checks", id, check, false, null, now);
                    PausaDb.enqueue(db, "habit_checks", id);
                    checks++;
                }
                try {
                    ContentValues score = new ContentValues();
                    score.put("habit_id", habit.uid);
                    score.put("json", HabitStore.scoreJson(habit).toString());
                    db.insertWithOnConflict("habit_scores", null, score, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (JSONException ignored) {
                    // Rebuilt from the checks on the next read.
                }
                habits++;
            }

            // Counters: what was on screen becomes today's first entry; goals become settings.
            SharedPreferences counters = context.getSharedPreferences(CalorieStore.PREFS_NAME, Context.MODE_PRIVATE);
            String today = CalorieStore.today();
            entry(pausa, db, today, "calories", counters.getInt(CalorieStore.KEY_TOTAL, 0), now);
            entry(pausa, db, today, "protein", counters.getInt(CalorieStore.KEY_PROTEIN_TOTAL, 0), now);
            entry(pausa, db, today, "cigarettes", counters.getInt(CalorieStore.KEY_CIGARETTE_COUNT, 0), now);
            setting(pausa, db, counters, CalorieStore.KEY_CALORIE_LIMIT, "calorie_goal", now);
            setting(pausa, db, counters, CalorieStore.KEY_PROTEIN_GOAL, "protein_goal", now);
            setting(pausa, db, counters, CalorieStore.KEY_CIGARETTE_GOAL, "cigarette_goal", now);

            ContentValues flag = new ContentValues();
            flag.put("key", "imported");
            flag.put("value", String.valueOf(now));
            db.insertWithOnConflict("sync_state", null, flag, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        PausaLog.event("info", "import", "Imported SharedPreferences into PausaDb",
                CloudAuth.json("tasks", tasks, "planned", planned, "habits", habits, "checks", checks));
    }

    private static void entry(PausaDb pausa, SQLiteDatabase db, String day, String kind, int amount, long now) {
        if (amount == 0) return;
        Map<String, Object> data = new HashMap<>();
        data.put("day", day);
        data.put("kind", kind);
        data.put("amount", (double) amount);
        data.put("at", now);
        String id = PausaDb.uuid();
        pausa.write(db, "metric_entries", id, data, false, null, now);
        PausaDb.enqueue(db, "metric_entries", id);
    }

    private static void setting(PausaDb pausa, SQLiteDatabase db, SharedPreferences prefs, String key, String id, long now) {
        if (!prefs.contains(key)) return;
        Map<String, Object> data = new HashMap<>();
        data.put("value", String.valueOf(prefs.getInt(key, 0)));
        pausa.write(db, "settings", id, data, false, null, now);
        PausaDb.enqueue(db, "settings", id);
    }

    private static String blank(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
