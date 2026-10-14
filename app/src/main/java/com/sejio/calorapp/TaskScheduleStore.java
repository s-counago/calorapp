package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

final class TaskScheduleStore {
    static final class Schedule {
        String id, zone;
        long taskId, start;
        int minutes;
        boolean deleted, eligible;

        String date() {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            format.setTimeZone(TimeZone.getTimeZone(zone));
            return format.format(new Date(start));
        }

        int minute() {
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(zone));
            calendar.setTimeInMillis(start);
            return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("taskId", taskId).put("start", start).put("minutes", minutes)
                    .put("zone", zone).put("deleted", deleted).put("eligible", eligible);
        }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("task_schedule", Context.MODE_PRIVATE); }

    static synchronized List<Schedule> all(Context c) {
        migrate(c);
        List<Schedule> rows = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(c).getString("rows", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                Schedule row = new Schedule();
                row.id = json.getString("id"); row.taskId = json.getLong("taskId"); row.start = json.getLong("start");
                row.minutes = json.getInt("minutes"); row.zone = json.getString("zone");
                row.deleted = json.optBoolean("deleted"); row.eligible = json.optBoolean("eligible");
                rows.add(row);
            }
        } catch (Exception ignored) { }
        return rows;
    }

    static Schedule first(Context c, long taskId, String date) {
        Schedule found = null;
        for (Schedule row : all(c)) if (!row.deleted && row.taskId == taskId && (date == null || row.date().equals(date))
                && (date != null || row.date().compareTo(DayPlanStore.day(0)) >= 0) && (found == null || row.start < found.start)) found = row;
        return found;
    }

    static synchronized void put(Context c, String id, long taskId, long start, int minutes) {
        if (minutes < 5 || minutes > 1440) throw new IllegalArgumentException();
        List<Schedule> rows = all(c);
        Schedule target = null;
        for (Schedule row : rows) if (row.id.equals(id)) target = row;
        if (target == null) { target = new Schedule(); target.id = UUID.randomUUID().toString().replace("-", ""); rows.add(target); }
        target.taskId = taskId; target.start = start; target.minutes = minutes; target.zone = TimeZone.getDefault().getID();
        target.deleted = false; target.eligible = true;
        write(c, rows);
        DayPlanStore.positionTimed(c, target);
    }

    static synchronized void remove(Context c, String id) {
        List<Schedule> rows = all(c);
        for (Schedule row : rows) if (row.id.equals(id)) row.deleted = true;
        write(c, rows);
    }

    static synchronized void restore(Context c, Schedule saved) {
        List<Schedule> rows = all(c);
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(saved.id)) { rows.set(i, saved); write(c, rows); return; }
    }

    static synchronized void removeDay(Context c, long taskId, String date) {
        List<Schedule> rows = all(c); boolean changed = false;
        for (Schedule row : rows) if (!row.deleted && row.taskId == taskId && row.date().equals(date)) { row.deleted = true; changed = true; }
        if (changed) write(c, rows);
    }

    static long start(String date, int minute) {
        int[] parts = Ledger.civil(Ledger.parseIso(date));
        Calendar calendar = Calendar.getInstance();
        calendar.clear(); calendar.setLenient(false);
        calendar.set(parts[0], parts[1] - 1, parts[2], minute / 60, minute % 60);
        return calendar.getTimeInMillis();
    }

    static String describe(Context c, long id, String date) {
        Schedule row = first(c, id, date);
        if (row == null) return "";
        return String.format(Locale.ROOT, "%02d:%02d · %d min", row.minute() / 60, row.minute() % 60, row.minutes);
    }

    private static void write(Context c, List<Schedule> rows) {
        try {
            JSONArray array = new JSONArray();
            for (Schedule row : rows) array.put(row.json());
            prefs(c).edit().putString("rows", array.toString()).apply();
            CalendarSync.request(c);
        } catch (Exception failure) { throw new IllegalStateException("No se pudo guardar el horario", failure); }
    }

    private static void migrate(Context c) {
        if (prefs(c).getBoolean("migrated", false)) return;
        JSONArray rows = new JSONArray();
        try {
            JSONObject old = new JSONObject(c.getSharedPreferences("short_term_planner", 0).getString("assignments", "{}"));
            Iterator<String> keys = old.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Schedule row = new Schedule();
                row.id = UUID.randomUUID().toString().replace("-", ""); row.taskId = old.getLong(key);
                row.start = Long.parseLong(key); row.minutes = 30; row.zone = TimeZone.getDefault().getID();
                rows.put(row.json());
            }
            prefs(c).edit().putString("rows", rows.toString()).putBoolean("migrated", true).apply();
        } catch (Exception ignored) { }
    }
}
