package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class PlannerStore {
    private static final String PREFS_NAME = "short_term_planner";
    private static final String KEY_ASSIGNMENTS = "assignments";

    private PlannerStore() {
    }

    static Map<Long, Long> getAssignments(Context context, long oldestSlot) {
        Map<Long, Long> assignments = new HashMap<>();
        String storedAssignments = preferences(context).getString(KEY_ASSIGNMENTS, "{}");
        List<TaskStore.Task> tasks = TaskStore.getAll(context);
        boolean changed = false;

        try {
            JSONObject jsonAssignments = new JSONObject(storedAssignments);
            Iterator<String> keys = jsonAssignments.keys();
            while (keys.hasNext()) {
                String slotKey = keys.next();
                long slotStart = Long.parseLong(slotKey);
                long taskId = jsonAssignments.getLong(slotKey);
                if (slotStart >= oldestSlot && containsTask(tasks, taskId)) {
                    assignments.put(slotStart, taskId);
                } else {
                    changed = true;
                }
            }
        } catch (JSONException | NumberFormatException ignored) {
            changed = true;
        }

        if (changed) {
            save(context, assignments);
        }
        return assignments;
    }

    static void assign(Context context, long slotStart, long taskId) {
        Map<Long, Long> assignments = getAssignments(context, currentSlotStart());
        assignments.put(slotStart, taskId);
        save(context, assignments);
    }

    static void clear(Context context, long slotStart) {
        Map<Long, Long> assignments = getAssignments(context, currentSlotStart());
        if (assignments.remove(slotStart) != null) {
            save(context, assignments);
        }
    }

    static long currentSlotStart() {
        long halfHour = 30L * 60L * 1000L;
        long now = System.currentTimeMillis();
        return now - (now % halfHour);
    }

    private static boolean containsTask(List<TaskStore.Task> tasks, long taskId) {
        for (TaskStore.Task task : tasks) {
            if (task.id == taskId) {
                return true;
            }
        }
        return false;
    }

    private static void save(Context context, Map<Long, Long> assignments) {
        JSONObject jsonAssignments = new JSONObject();
        for (Map.Entry<Long, Long> assignment : assignments.entrySet()) {
            try {
                jsonAssignments.put(
                        String.valueOf(assignment.getKey()),
                        assignment.getValue()
                );
            } catch (JSONException ignored) {
                // Long values are always valid JSON.
            }
        }
        preferences(context).edit()
                .putString(KEY_ASSIGNMENTS, jsonAssignments.toString())
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
