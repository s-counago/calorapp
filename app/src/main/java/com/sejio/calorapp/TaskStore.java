package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

final class TaskStore {
    static final String PREFS_NAME = "daily_tasks";
    static final String KEY_TASKS = "tasks";
    private static final String KEY_NOTION_REVIEW_DONE = "notion_review_done";
    private static final String KEY_NOTION_REVIEW_PERIOD = "notion_review_period";
    private static final String KEY_HABIT_REVIEW_DONE = "habit_review_done";

    private TaskStore() {
    }

    static List<Task> getAll(Context context) {
        LegacyImport.runOnce(context);
        List<Task> tasks = new ArrayList<>();
        // List order is creation order; `position` belongs to the day plan (see DayPlanStore).
        for (Map<String, Object> row : PausaDb.get(context).rows(
                "SELECT * FROM tasks WHERE deleted = 0 AND status != 'cancelled' ORDER BY created_at, local_id")) {
            tasks.add(fromRow(row));
        }
        return tasks;
    }

    static Task fromRow(Map<String, Object> row) {
        return new Task((Long) row.get("local_id"), (String) row.get("title"), "done".equals(row.get("status")),
                Long.valueOf(1).equals(row.get("following")), text(row.get("waiting_for")),
                text(row.get("expected_date")), text(row.get("review_date")));
    }

    static long add(Context context, String text) {
        String normalizedText = text.trim();
        if (normalizedText.isEmpty()) {
            return -1;
        }
        LegacyImport.runOnce(context);
        Map<String, Object> data = new HashMap<>();
        data.put("title", normalizedText);
        data.put("status", "todo");
        data.put("created_at", System.currentTimeMillis());
        data.put("following", 0);
        long id = PausaDb.get(context).put("tasks", PausaDb.uuid(), data, false, nextLocalId(context));
        changed(context);
        return id;
    }

    /** Keeps the old id scheme (creation milliseconds) so new ids never collide with imported ones. */
    private static long nextLocalId(Context context) {
        long max = 0;
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT MAX(local_id) AS max FROM tasks")) {
            if (row.get("max") != null) max = (Long) row.get("max");
        }
        return Math.max(System.currentTimeMillis(), max + 1);
    }

    static void setDone(Context context, long id, boolean done) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", done ? "done" : "todo");
        data.put("completed_at", done ? System.currentTimeMillis() : null);
        update(context, id, data);
    }

    static void setText(Context context, long id, String text) {
        String normalizedText = text.trim();
        if (normalizedText.isEmpty()) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("title", normalizedText);
        update(context, id, data);
    }

    static void delete(Context context, long id) {
        String uid = uid(context, id);
        if (uid == null) return;
        PausaDb.get(context).put("tasks", uid, new HashMap<>(), true);
        changed(context);
    }

    static void setFollowup(Context context, long id, boolean following, String waitingFor, String expectedDate, String reviewDate) {
        Task task = find(context, id);
        if (task == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("following", following ? 1 : 0);
        data.put("waiting_for", empty(waitingFor.trim()));
        data.put("expected_date", empty(expectedDate));
        data.put("review_date", empty(reviewDate));
        if (following && task.done) { data.put("status", "todo"); data.put("completed_at", null); }
        update(context, id, data);
    }

    static int indexOf(Context context, long id) {
        List<Task> tasks = getAll(context);
        for (int index = 0; index < tasks.size(); index++) {
            if (tasks.get(index).id == id) return index;
        }
        return -1;
    }

    /** Puts a deleted task back with its original id, so plan references come back with it. */
    static void restore(Context context, Task task, int index) {
        if (find(context, task.id) != null) return;
        String uid = uid(context, task.id);
        Map<String, Object> data = new HashMap<>();
        data.put("title", task.text);
        data.put("status", task.done ? "done" : "todo");
        data.put("following", task.following ? 1 : 0);
        data.put("waiting_for", empty(task.waitingFor));
        data.put("expected_date", empty(task.expectedDate));
        data.put("review_date", empty(task.reviewDate));
        // The tombstone keeps created_at, so the task returns to its old place in the list.
        if (uid == null) {
            data.put("created_at", System.currentTimeMillis());
            PausaDb.get(context).put("tasks", PausaDb.uuid(), data, false, task.id);
        } else {
            PausaDb.get(context).put("tasks", uid, data, false);
        }
        changed(context);
    }

    static Task find(Context context, long id) {
        for (Map<String, Object> row : PausaDb.get(context).rows(
                "SELECT * FROM tasks WHERE local_id = ? AND deleted = 0 AND status != 'cancelled'", String.valueOf(id))) {
            return fromRow(row);
        }
        return null;
    }

    /** The backend id of a task (also of a deleted one), or null. */
    static String uid(Context context, long id) {
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT id FROM tasks WHERE local_id = ?", String.valueOf(id))) {
            return (String) row.get("id");
        }
        return null;
    }

    static void update(Context context, long id, Map<String, Object> data) {
        String uid = uid(context, id);
        if (uid == null) return;
        PausaDb.get(context).put("tasks", uid, data, false);
        changed(context);
    }

    private static void changed(Context context) {
        CalendarSync.request(context);
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }

    static boolean isNotionReviewDone(Context context) {
        resetDailyNotionReview(context);
        return preferences(context).getBoolean(KEY_NOTION_REVIEW_DONE, false);
    }

    static void setNotionReviewDone(Context context, boolean done) {
        resetDailyNotionReview(context);
        preferences(context).edit().putBoolean(KEY_NOTION_REVIEW_DONE, done).apply();
    }

    static boolean isHabitReviewDone(Context context) {
        resetDailyNotionReview(context);
        return preferences(context).getBoolean(KEY_HABIT_REVIEW_DONE, false);
    }

    static void setHabitReviewDone(Context context, boolean done) {
        resetDailyNotionReview(context);
        preferences(context).edit().putBoolean(KEY_HABIT_REVIEW_DONE, done).apply();
    }

    /** Both daily rituals share one reset period, so they always start the day together. */
    static void resetDailyNotionReview(Context context) {
        SharedPreferences preferences = preferences(context);
        String currentPeriod = currentResetPeriod();
        if (!currentPeriod.equals(preferences.getString(KEY_NOTION_REVIEW_PERIOD, ""))) {
            preferences.edit()
                    .putString(KEY_NOTION_REVIEW_PERIOD, currentPeriod)
                    .putBoolean(KEY_NOTION_REVIEW_DONE, false)
                    .putBoolean(KEY_HABIT_REVIEW_DONE, false)
                    .apply();
        }
    }

    private static String currentResetPeriod() {
        Calendar period = Calendar.getInstance();
        if (period.get(Calendar.HOUR_OF_DAY) < 3) {
            period.add(Calendar.DAY_OF_YEAR, -1);
        }
        return String.format(
                Locale.ROOT,
                "%04d-%02d-%02d",
                period.get(Calendar.YEAR),
                period.get(Calendar.MONTH) + 1,
                period.get(Calendar.DAY_OF_MONTH)
        );
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static final class Task {
        final long id;
        final String text;
        final boolean done;
        final boolean following;
        final String waitingFor, expectedDate, reviewDate;

        Task(long id, String text, boolean done) {
            this(id, text, done, false, "", "", "");
        }

        Task(long id, String text, boolean done, boolean following, String waitingFor, String expectedDate, String reviewDate) {
            this.id = id;
            this.text = text;
            this.done = done;
            this.following = following;
            this.waitingFor = waitingFor;
            this.expectedDate = expectedDate;
            this.reviewDate = reviewDate;
        }

        Task copy(String text, boolean done) {
            return new Task(id, text, done, following, waitingFor, expectedDate, reviewDate);
        }
    }
}
