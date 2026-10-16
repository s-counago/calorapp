package com.sejio.calorapp;

import android.content.Context;
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

    /**
     * The plan of one day. A task is planned on one day at most: `plan_date`, `plan_slot`
     * (morning, afternoon, evening) and `position` live on the task and sync with the backend.
     * A set time (`plan_time`, see TaskScheduleStore) always comes with the slot of its hour.
     */
    static List<List<Long>> read(Context context, String date) {
        LegacyImport.runOnce(context);
        List<List<Long>> result = empty();
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT local_id, plan_slot FROM tasks WHERE deleted = 0 "
                + "AND status != 'cancelled' AND following = 0 AND plan_date = ? ORDER BY position, created_at", date)) {
            result.get(section((String) row.get("plan_slot"))).add((Long) row.get("local_id"));
        }
        return result;
    }

    static void save(Context context, String date, List<List<Long>> sections) {
        List<List<Long>> before = read(context, date);
        for (List<Long> section : before) for (Long id : section) if (!contains(sections, id)) TaskScheduleStore.removeDay(context, id, date);
        persist(context, date, sections, before);
    }

    static int section(String slot) {
        if ("afternoon".equals(slot)) return 1;
        if ("evening".equals(slot)) return 2;
        return 0; // Morning, or planned elsewhere without a part of the day.
    }

    /** Writes only the tasks whose day, slot or order changed, so a reorder does not resend the whole plan. */
    private static void persist(Context context, String date, List<List<Long>> sections, List<List<Long>> before) {
        PausaDb db = PausaDb.get(context);
        for (List<Long> section : before) {
            for (Long id : section) {
                if (contains(sections, id)) continue;
                Map<String, Object> row = taskRow(db, id);
                if (row == null || !date.equals(row.get("plan_date"))) continue;
                Map<String, Object> data = new HashMap<>();
                data.put("plan_date", null);
                data.put("plan_slot", null);
                data.put("position", null);
                data.put("plan_time", null);
                data.put("plan_minutes", null);
                db.put("tasks", (String) row.get("id"), data, false);
            }
        }
        for (int i = 0; i < Math.min(3, sections.size()); i++) {
            List<Long> ids = sections.get(i);
            for (int j = 0; j < ids.size(); j++) {
                Map<String, Object> row = taskRow(db, ids.get(j));
                if (row == null) continue;
                double position = j + 1;
                Object current = row.get("position");
                // A task for any time that day (no slot, e.g. made from a habit) shows under the morning and keeps no slot.
                boolean anyTime = i == 0 && date.equals(row.get("plan_date")) && row.get("plan_slot") == null;
                // Dragged to another slot of the same day: its set time no longer fits, so it goes.
                boolean timeOff = row.get("plan_time") != null && date.equals(row.get("plan_date")) && !anyTime
                        && !SyncSchema.SLOTS[i].equals(row.get("plan_slot"));
                if (date.equals(row.get("plan_date")) && (anyTime || SyncSchema.SLOTS[i].equals(row.get("plan_slot")))
                        && current instanceof Number && ((Number) current).doubleValue() == position && !timeOff) continue;
                Map<String, Object> data = new HashMap<>();
                if (timeOff) { data.put("plan_time", null); data.put("plan_minutes", null); }
                data.put("plan_date", date);
                data.put("plan_slot", anyTime ? null : SyncSchema.SLOTS[i]);
                data.put("position", position);
                db.put("tasks", (String) row.get("id"), data, false);
            }
        }
    }

    private static Map<String, Object> taskRow(PausaDb db, long id) {
        for (Map<String, Object> row : db.rows("SELECT * FROM tasks WHERE local_id = ? AND deleted = 0", String.valueOf(id))) return row;
        return null;
    }

    static boolean contains(List<List<Long>> sections, long id) {
        for (List<Long> section : sections) if (section.contains(id)) return true;
        return false;
    }

    static void positionTimed(Context context, TaskScheduleStore.Schedule schedule) {
        List<List<Long>> sections = read(context, schedule.date());
        int target = schedule.minute() < 720 ? 0 : schedule.minute() < 1200 ? 1 : 2;
        List<List<Long>> before = read(context, schedule.date());
        if (sections.get(target).contains(schedule.taskId)) {
            persist(context, schedule.date(), sections, before);
            return;
        }
        for (List<Long> section : sections) section.remove(schedule.taskId);
        sections.get(target).add(schedule.taskId);
        persist(context, schedule.date(), sections, before);
    }

    /** Old per-day boards, read once by LegacyImport. Days are processed in order, so the latest plan of a task wins. */
    static Map<String, List<List<Long>>> legacyBoards(Context context) {
        Map<String, List<List<Long>>> days = new TreeMap<>();
        for (Map.Entry<String, ?> entry : context.getSharedPreferences("day_boards", Context.MODE_PRIVATE).getAll().entrySet()) {
            if (!entry.getKey().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !(entry.getValue() instanceof String)) continue;
            List<List<Long>> sections = empty();
            try {
                JSONArray json = new JSONArray((String) entry.getValue());
                for (int i = 0; i < Math.min(3, json.length()); i++) {
                    JSONArray ids = json.getJSONArray(i);
                    for (int j = 0; j < ids.length(); j++) if (!contains(sections, ids.getLong(j))) sections.get(i).add(ids.getLong(j));
                }
            } catch (JSONException ignored) { }
            days.put(entry.getKey(), sections);
        }
        try {
            JSONObject old = new JSONObject(context.getSharedPreferences("short_term_planner", 0).getString("assignments", "{}"));
            List<Long> times = new ArrayList<>();
            Iterator<String> keys = old.keys();
            while (keys.hasNext()) times.add(Long.parseLong(keys.next()));
            Collections.sort(times);
            for (long time : times) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis(time);
                String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.getTime());
                if (context.getSharedPreferences("day_boards", 0).contains(date)) continue;
                List<List<Long>> sections = days.get(date);
                if (sections == null) { sections = empty(); days.put(date, sections); }
                long id = old.getLong(String.valueOf(time));
                int hour = calendar.get(Calendar.HOUR_OF_DAY);
                if (!contains(sections, id)) sections.get(hour < 12 ? 0 : hour < 20 ? 1 : 2).add(id);
            }
        } catch (JSONException | NumberFormatException ignored) { }
        return days;
    }
}
