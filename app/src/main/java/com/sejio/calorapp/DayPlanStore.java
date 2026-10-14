package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.text.SimpleDateFormat;
import java.util.*;

/** Ordered task references per local calendar day; task text always lives in TaskStore. */
final class DayPlanStore {
    static final String[] LABELS = {"Por la mañana", "Por la tarde", "Por la noche"};

    static String day(int offset) {
        Calendar date = Calendar.getInstance();
        date.add(Calendar.DAY_OF_YEAR, offset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date.getTime());
    }

    static List<List<Long>> empty() {
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < 3; i++) result.add(new ArrayList<>());
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("day_boards", Context.MODE_PRIVATE);
    }

    static List<List<Long>> read(Context context, String date) {
        migrate(context);
        List<List<Long>> result = empty();
        Set<Long> valid = new HashSet<>();
        for (TaskStore.Task task : TaskStore.getAll(context)) if (!task.following) valid.add(task.id);
        Set<Long> seen = new HashSet<>();
        try {
            JSONArray sections = new JSONArray(prefs(context).getString(date, "[]"));
            for (int i = 0; i < Math.min(3, sections.length()); i++) {
                JSONArray ids = sections.getJSONArray(i);
                for (int j = 0; j < ids.length(); j++) {
                    long id = ids.getLong(j);
                    if (valid.contains(id) && seen.add(id)) result.get(i).add(id);
                }
            }
        } catch (JSONException ignored) { }
        for (TaskScheduleStore.Schedule schedule : TaskScheduleStore.all(context)) {
            if (schedule.deleted || !schedule.date().equals(date) || !valid.contains(schedule.taskId)) continue;
            if (!contains(result, schedule.taskId)) result.get(schedule.minute() < 720 ? 0 : schedule.minute() < 1200 ? 1 : 2).add(schedule.taskId);
        }
        return result;
    }

    static void save(Context context, String date, List<List<Long>> sections) {
        List<List<Long>> before = read(context, date);
        for (List<Long> section : before) for (Long id : section) if (!contains(sections, id)) TaskScheduleStore.removeDay(context, id, date);
        JSONArray json = new JSONArray();
        for (List<Long> section : sections) json.put(new JSONArray(section));
        prefs(context).edit().putString(date, json.toString()).apply();
    }

    static boolean contains(List<List<Long>> sections, long id) {
        for (List<Long> section : sections) if (section.contains(id)) return true;
        return false;
    }

    static void positionTimed(Context context, TaskScheduleStore.Schedule schedule) {
        List<List<Long>> sections = read(context, schedule.date());
        int target = schedule.minute() < 720 ? 0 : schedule.minute() < 1200 ? 1 : 2;
        if (sections.get(target).contains(schedule.taskId)) return;
        for (List<Long> section : sections) section.remove(schedule.taskId);
        sections.get(target).add(schedule.taskId);
        JSONArray json = new JSONArray();
        for (List<Long> section : sections) json.put(new JSONArray(section));
        prefs(context).edit().putString(schedule.date(), json.toString()).apply();
    }

    // Read legacy data directly: the old getter deletes elapsed slots while reading.
    private static void migrate(Context context) {
        SharedPreferences target = prefs(context);
        if (target.getBoolean("migrated", false)) return;
        Map<String, List<List<Long>>> days = new TreeMap<>();
        try {
            JSONObject old = new JSONObject(context.getSharedPreferences("short_term_planner", 0)
                    .getString("assignments", "{}"));
            List<Long> times = new ArrayList<>();
            Iterator<String> keys = old.keys();
            while (keys.hasNext()) times.add(Long.parseLong(keys.next()));
            Collections.sort(times);
            for (long time : times) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis(time);
                String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.getTime());
                List<List<Long>> sections = days.get(date);
                if (sections == null) { sections = empty(); days.put(date, sections); }
                long id = old.getLong(String.valueOf(time));
                int hour = calendar.get(Calendar.HOUR_OF_DAY);
                if (!contains(sections, id)) sections.get(hour < 12 ? 0 : hour < 20 ? 1 : 2).add(id);
            }
        } catch (JSONException | NumberFormatException ignored) { }
        SharedPreferences.Editor editor = target.edit();
        for (Map.Entry<String, List<List<Long>>> day : days.entrySet()) {
            if (target.contains(day.getKey())) continue;
            JSONArray json = new JSONArray();
            for (List<Long> section : day.getValue()) json.put(new JSONArray(section));
            editor.putString(day.getKey(), json.toString());
        }
        editor.putBoolean("migrated", true).apply();
    }
}
