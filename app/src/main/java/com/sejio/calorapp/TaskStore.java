package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

final class TaskStore {
    private static final String PREFS_NAME = "daily_tasks";
    private static final String KEY_TASKS = "tasks";
    private static final String KEY_NOTION_REVIEW_DONE = "notion_review_done";
    private static final String KEY_NOTION_REVIEW_PERIOD = "notion_review_period";
    private static final String KEY_HABIT_REVIEW_DONE = "habit_review_done";

    private TaskStore() {
    }

    static List<Task> getAll(Context context) {
        List<Task> tasks = new ArrayList<>();
        String storedTasks = preferences(context).getString(KEY_TASKS, "[]");

        try {
            JSONArray jsonTasks = new JSONArray(storedTasks);
            for (int index = 0; index < jsonTasks.length(); index++) {
                JSONObject jsonTask = jsonTasks.getJSONObject(index);
                tasks.add(new Task(
                        jsonTask.getLong("id"),
                        jsonTask.getString("text"),
                        jsonTask.optBoolean("done", false)
                ));
            }
        } catch (JSONException ignored) {
            // Keep the screen usable if the stored data is ever malformed.
        }

        return tasks;
    }

    static long add(Context context, String text) {
        String normalizedText = text.trim();
        if (normalizedText.isEmpty()) {
            return -1;
        }

        List<Task> tasks = getAll(context);
        long id = System.currentTimeMillis();
        while (containsId(tasks, id)) {
            id++;
        }
        tasks.add(new Task(id, normalizedText, false));
        save(context, tasks);
        return id;
    }

    static void setDone(Context context, long id, boolean done) {
        List<Task> tasks = getAll(context);
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            if (task.id == id) {
                tasks.set(index, new Task(task.id, task.text, done));
                save(context, tasks);
                return;
            }
        }
    }

    static void setText(Context context, long id, String text) {
        String normalizedText = text.trim();
        if (normalizedText.isEmpty()) {
            return;
        }

        List<Task> tasks = getAll(context);
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            if (task.id == id) {
                tasks.set(index, new Task(task.id, normalizedText, task.done));
                save(context, tasks);
                return;
            }
        }
    }

    static void delete(Context context, long id) {
        List<Task> tasks = getAll(context);
        for (int index = 0; index < tasks.size(); index++) {
            if (tasks.get(index).id == id) {
                tasks.remove(index);
                save(context, tasks);
                return;
            }
        }
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
        List<Task> tasks = getAll(context);
        if (containsId(tasks, task.id)) return;
        tasks.add(Math.max(0, Math.min(index < 0 ? tasks.size() : index, tasks.size())), task);
        save(context, tasks);
    }

    static Task find(Context context, long id) {
        for (Task task : getAll(context)) if (task.id == id) return task;
        return null;
    }

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

    private static boolean containsId(List<Task> tasks, long id) {
        for (Task task : tasks) {
            if (task.id == id) {
                return true;
            }
        }
        return false;
    }

    private static void save(Context context, List<Task> tasks) {
        JSONArray jsonTasks = new JSONArray();
        for (Task task : tasks) {
            JSONObject jsonTask = new JSONObject();
            try {
                jsonTask.put("id", task.id);
                jsonTask.put("text", task.text);
                jsonTask.put("done", task.done);
                jsonTasks.put(jsonTask);
            } catch (JSONException ignored) {
                // These primitive values are always valid JSON.
            }
        }

        preferences(context).edit().putString(KEY_TASKS, jsonTasks.toString()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static final class Task {
        final long id;
        final String text;
        final boolean done;

        Task(long id, String text, boolean done) {
            this.id = id;
            this.text = text;
            this.done = done;
        }
    }
}
