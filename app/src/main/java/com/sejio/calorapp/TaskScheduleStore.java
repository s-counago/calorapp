package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The set time of a task (its «hora»). Since 6.19 it lives on the task itself, in the synced fields
 * `plan_time` (minutes after midnight on `plan_date`) and `plan_minutes`, so the web, agents and the
 * phone see the same hour. A task has one time at most, so a schedule's id is the task's backend id.
 *
 * The old local blocks (SharedPreferences "task_schedule") are moved onto their tasks once, by
 * {@link #moveLocalBlocks}; their Calendar events keep their ids (see CalendarSync).
 */
final class TaskScheduleStore {
    static final int DEFAULT_MINUTES = 30;

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
    }

    private TaskScheduleStore() {}

    /** Every task with a set time on its planned day. */
    static synchronized List<Schedule> all(Context c) {
        moveLocalBlocks(c);
        List<Schedule> rows = new ArrayList<>();
        for (Map<String, Object> row : PausaDb.get(c).rows("SELECT id, local_id, plan_date, plan_time, plan_minutes FROM tasks "
                + "WHERE deleted = 0 AND status != 'cancelled' AND plan_date IS NOT NULL AND plan_time IS NOT NULL")) {
            Schedule schedule = fromRow(row);
            if (schedule != null) rows.add(schedule);
        }
        return rows;
    }

    private static Schedule fromRow(Map<String, Object> row) {
        if (!(row.get("plan_time") instanceof Number) || row.get("local_id") == null) return null;
        int minute = ((Number) row.get("plan_time")).intValue();
        if (minute < 0 || minute > 1439) return null;
        Schedule schedule = new Schedule();
        schedule.id = (String) row.get("id");
        schedule.taskId = (Long) row.get("local_id");
        schedule.zone = TimeZone.getDefault().getID();
        try { schedule.start = start((String) row.get("plan_date"), minute); }
        catch (RuntimeException invalid) { return null; }
        schedule.minutes = minutes(row.get("plan_minutes"));
        schedule.eligible = true;
        return schedule;
    }

    /** The web allows 1-minute tasks; Calendar events here are 5 minutes at least. No duration means half an hour. */
    static int minutes(Object value) {
        if (!(value instanceof Number)) return DEFAULT_MINUTES;
        return Math.max(5, Math.min(1440, ((Number) value).intValue()));
    }

    static Schedule first(Context c, long taskId, String date) {
        for (Schedule row : all(c)) if (row.taskId == taskId && (date == null ? row.date().compareTo(DayPlanStore.day(0)) >= 0 : row.date().equals(date))) return row;
        return null;
    }

    /** Sets the task's time: its day, the slot of that hour, the minute and the duration. `id` is ignored (one time per task). */
    static synchronized void put(Context c, String id, long taskId, long start, int minutes) {
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException();
        String uid = TaskStore.uid(c, taskId);
        if (uid == null) return;
        Schedule target = new Schedule();
        target.id = uid; target.taskId = taskId; target.start = start; target.minutes = minutes;
        target.zone = TimeZone.getDefault().getID(); target.eligible = true;
        int minute = target.minute();
        Map<String, Object> data = new HashMap<>();
        data.put("plan_date", target.date());
        data.put("plan_slot", slotFor(minute));
        data.put("plan_time", minute);
        data.put("plan_minutes", minutes);
        PausaDb.get(c).put("tasks", uid, data, false);
        DayPlanStore.positionTimed(c, target);
        CalendarSync.request(c);
    }

    /** Takes the time off; the task stays on its day and slot. */
    static synchronized void remove(Context c, String id) {
        if (id == null) return;
        Map<String, Object> row = PausaDb.get(c).row("tasks", id);
        if (row == null || row.get("plan_time") == null) return;
        clearTime(c, id);
    }

    /** Puts a schedule back as it was (Deshacer). */
    static synchronized void restore(Context c, Schedule saved) {
        if (saved.deleted) remove(c, saved.id);
        else put(c, saved.id, saved.taskId, saved.start, saved.minutes);
    }

    /** Takes the time off the task if it was on `date`. */
    static synchronized void removeDay(Context c, long taskId, String date) {
        for (Map<String, Object> row : PausaDb.get(c).rows("SELECT id FROM tasks WHERE local_id = ? AND plan_date = ? AND plan_time IS NOT NULL",
                String.valueOf(taskId), date)) {
            clearTime(c, (String) row.get("id"));
        }
    }

    private static void clearTime(Context c, String uid) {
        Map<String, Object> data = new HashMap<>();
        data.put("plan_time", null);
        data.put("plan_minutes", null);
        PausaDb.get(c).put("tasks", uid, data, false);
        CalendarSync.request(c);
    }

    static String slotFor(int minute) {
        return minute < 720 ? "morning" : minute < 1200 ? "afternoon" : "evening";
    }

    static long start(String date, int minute) {
        int[] parts = Ledger.civil(Ledger.parseIso(date));
        Calendar calendar = Calendar.getInstance();
        calendar.clear(); calendar.setLenient(false);
        calendar.set(parts[0], parts[1] - 1, parts[2], minute / 60, minute % 60);
        return calendar.getTimeInMillis();
    }

    /** «18:30 · 45 min», or "" when the task has no time on that day. */
    static String describe(Context c, long id, String date) {
        Schedule row = first(c, id, date);
        if (row == null) return "";
        return String.format(Locale.ROOT, "%02d:%02d · %d min", row.minute() / 60, row.minute() % 60, row.minutes);
    }

    // ------------------------------------------------------------ the old local blocks

    static SharedPreferences legacy(Context c) { return c.getSharedPreferences("task_schedule", Context.MODE_PRIVATE); }

    /** One old block: what CalendarSync needs to move or delete its event. */
    static final class LegacyBlock {
        String id;
        long taskId, start;
        int minutes;
        boolean deleted;
    }

    static List<LegacyBlock> legacyBlocks(Context c) {
        List<LegacyBlock> blocks = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(legacy(c).getString("rows", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                LegacyBlock block = new LegacyBlock();
                block.id = json.getString("id"); block.taskId = json.getLong("taskId"); block.start = json.getLong("start");
                block.minutes = json.optInt("minutes", DEFAULT_MINUTES); block.deleted = json.optBoolean("deleted");
                blocks.add(block);
            }
        } catch (Exception ignored) { }
        return blocks;
    }

    /**
     * Moves each old block from today on onto its task (plan_time and plan_minutes), unless the task
     * already has a time or is planned on another day. Runs once. The block ids that moved are kept in
     * "moved" (block id → task id) so CalendarSync can keep the same Calendar event.
     */
    static synchronized void moveLocalBlocks(Context c) {
        SharedPreferences prefs = legacy(c);
        if (prefs.getBoolean("moved_to_tasks", false)) return;
        LegacyImport.runOnce(c);
        PausaDb db = PausaDb.get(c);
        String today = DayPlanStore.day(0);
        JSONObject moved = new JSONObject();
        int count = 0, skipped = 0;
        for (LegacyBlock block : legacyBlocks(c)) {
            if (block.deleted) continue;
            Schedule probe = new Schedule();
            probe.start = block.start; probe.zone = TimeZone.getDefault().getID();
            String date = probe.date();
            if (date.compareTo(today) < 0) continue;
            Map<String, Object> task = null;
            for (Map<String, Object> row : db.rows("SELECT * FROM tasks WHERE local_id = ? AND deleted = 0", String.valueOf(block.taskId))) task = row;
            if (task == null || task.get("plan_time") != null || (task.get("plan_date") != null && !date.equals(task.get("plan_date")))) { skipped++; continue; }
            int minute = probe.minute();
            Map<String, Object> data = new HashMap<>();
            data.put("plan_date", date);
            data.put("plan_slot", slotFor(minute));
            data.put("plan_time", minute);
            data.put("plan_minutes", Math.max(1, Math.min(1440, block.minutes)));
            db.put("tasks", (String) task.get("id"), data, false);
            try { moved.put(block.id, task.get("id")); } catch (Exception ignored) { }
            count++;
        }
        prefs.edit().putBoolean("moved_to_tasks", true).putString("moved", moved.toString()).apply();
        PausaLog.i("tasks", "local time blocks moved to tasks: " + count + " moved, " + skipped + " skipped");
        if (count > 0) CalendarSync.request(c);
    }

    /** Old block id → task id, for the blocks moveLocalBlocks put on a task. */
    static Map<String, String> movedBlocks(Context c) {
        Map<String, String> result = new HashMap<>();
        try {
            JSONObject moved = new JSONObject(legacy(c).getString("moved", "{}"));
            java.util.Iterator<String> keys = moved.keys();
            while (keys.hasNext()) { String key = keys.next(); result.put(key, moved.getString(key)); }
        } catch (Exception ignored) { }
        return result;
    }
}
