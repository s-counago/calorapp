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
        // Board tasks live in the Kanban (KanbanView) and join the list only while planned on a day.
        for (Map<String, Object> row : PausaDb.get(context).rows(
                "SELECT * FROM tasks WHERE deleted = 0 AND status != 'cancelled' AND (board IS NULL OR plan_date IS NOT NULL) "
                        + "ORDER BY created_at, local_id")) {
            tasks.add(fromRow(row));
        }
        return tasks;
    }

    static Task fromRow(Map<String, Object> row) {
        Task task = new Task((Long) row.get("local_id"), (String) row.get("title"), "done".equals(row.get("status")),
                Long.valueOf(1).equals(row.get("following")), text(row.get("waiting_for")),
                text(row.get("expected_date")), text(row.get("review_date")), (String) row.get("board"), (String) row.get("habit_id"),
                (String) row.get("plan_date"), (String) row.get("plan_slot"));
        task.planTime = row.get("plan_time") instanceof Number ? ((Number) row.get("plan_time")).intValue() : null;
        task.planMinutes = row.get("plan_minutes") instanceof Number ? ((Number) row.get("plan_minutes")).intValue() : null;
        return task;
    }

    /**
     * A task made from a habit for one day: lane prioridad, any time that day, linked to the habit so
     * completing one marks the other. Asking twice for the same habit and day returns the same task,
     * as habitToTask does in the backend. The Calendar sync adds it as an all-day event.
     */
    static long addFromHabit(Context context, HabitStore.Habit habit, String day) {
        LegacyImport.runOnce(context);
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT local_id FROM tasks WHERE deleted = 0 AND habit_id = ? "
                + "AND plan_date = ? AND status != 'cancelled' LIMIT 1", habit.uid, day)) {
            return (Long) row.get("local_id");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("title", habit.name);
        data.put("board", "priority");
        data.put("status", "todo");
        data.put("created_at", System.currentTimeMillis());
        data.put("following", 0);
        data.put("plan_date", day);
        data.put("habit_id", habit.uid);
        long id = PausaDb.get(context).put("tasks", PausaDb.uuid(), data, false, nextLocalId(context));
        PausaLog.i("tasks", "habit task " + day);
        changed(context);
        return id;
    }

    static long add(Context context, String text) {
        return add(context, text, null);
    }

    /** Adds a task to the list, or to a Kanban column when `board` is set. */
    static long add(Context context, String text, String board) {
        String normalizedText = text.trim();
        if (normalizedText.isEmpty()) {
            return -1;
        }
        LegacyImport.runOnce(context);
        Map<String, Object> data = new HashMap<>();
        data.put("title", normalizedText);
        data.put("board", board);
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
        // A task made from a habit marks (or unmarks) the habit on its day.
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT t.plan_date, h.local_id FROM tasks t JOIN habits h ON h.id = t.habit_id "
                + "WHERE t.local_id = ? AND t.plan_date IS NOT NULL AND h.deleted = 0", String.valueOf(id))) {
            HabitStore.setDoneOnDay(context, (Long) row.get("local_id"), (String) row.get("plan_date"), done);
        }
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
        // A follow-up lives in the list's follow-up panel, so it leaves the Kanban.
        if (following) data.put("board", null);
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
        data.put("board", task.board);
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

    // ------------------------------------------------------------------ Kanban

    /** Kanban columns in board order, as stored in `tasks.board`. */
    static final String[] BOARD = {"stack", "priority", "vault", "failed"};
    static final String[] BOARD_LABELS = {"Stack infinito", "Prioridad", "Baúl", "Fracasos"};
    /** Tasks done before the board existed (the old Notion count); synced as a setting. */
    static final String DONE_BASE = "tasks_done_base";

    /** Open tasks of one column, newest first. */
    static List<Task> board(Context context, String column) {
        LegacyImport.runOnce(context);
        List<Task> tasks = new ArrayList<>();
        for (Map<String, Object> row : PausaDb.get(context).rows(
                "SELECT * FROM tasks WHERE deleted = 0 AND status = 'todo' AND board = ? ORDER BY " + listOrder(context), column)) {
            tasks.add(fromRow(row));
        }
        return tasks;
    }

    static Map<String, Integer> boardCounts(Context context) {
        Map<String, Integer> counts = new HashMap<>();
        for (String column : BOARD) counts.put(column, 0);
        for (Map<String, Object> row : PausaDb.get(context).rows(
                "SELECT board, COUNT(*) AS n FROM tasks WHERE deleted = 0 AND status = 'todo' AND board IS NOT NULL GROUP BY board")) {
            counts.put((String) row.get("board"), ((Long) row.get("n")).intValue());
        }
        return counts;
    }

    /** Every task done: the old count plus board tasks completed since. Same rule as the backend's board summary. */
    static int doneTotal(Context context) {
        long base = 0;
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT value FROM settings WHERE id = ? AND deleted = 0", DONE_BASE)) {
            try { base = Math.round(Double.parseDouble((String) row.get("value"))); } catch (RuntimeException ignored) { }
        }
        return (int) base + PausaDb.get(context).count(
                "SELECT COUNT(*) FROM tasks WHERE deleted = 0 AND board IS NOT NULL AND status = 'done'");
    }

    static void setBoard(Context context, long id, String column) {
        Map<String, Object> data = new HashMap<>();
        data.put("board", column);
        update(context, id, data);
    }

    // ------------------------------------------------------------------ Lanes (pausa_v7)

    /**
     * Each task is in exactly one lane, derived from `board` and `following` as laneOf does in the
     * backend: waiting when following, the board column when there is one, otherwise the inbox.
     */
    static final String[] LANES = {"inbox", "priority", "stack", "waiting", "vault", "failed"};
    static final String[] LANE_LABELS = {"Bandeja", "Prioridad", "Stack", "Esperando", "Baúl", "Fracasos"};

    private static final String LANE_SQL = "CASE WHEN following = 1 THEN 'waiting' "
            + "WHEN board IN ('stack', 'priority', 'vault', 'failed') THEN board ELSE 'inbox' END";

    static String laneLabel(String lane) {
        for (int i = 0; i < LANES.length; i++) if (LANES[i].equals(lane)) return LANE_LABELS[i];
        return LANE_LABELS[0];
    }

    /**
     * Newest first. With dated_last, tasks without a date come first (still newest first) and those with
     * a day or a due date go to the bottom, soonest first. Same rule as datedLast in the web.
     */
    private static String listOrder(Context context) {
        String newest = "created_at DESC, local_id DESC";
        if (!Features.isOn(context, Features.DATED_LAST)) return newest;
        return "COALESCE(plan_date, due_date) IS NOT NULL, COALESCE(plan_date, due_date), " + newest;
    }

    /** Open tasks of one lane, newest first. */
    static List<Task> lane(Context context, String lane) {
        LegacyImport.runOnce(context);
        List<Task> tasks = new ArrayList<>();
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT * FROM tasks WHERE deleted = 0 AND status = 'todo' AND "
                + LANE_SQL + " = ? ORDER BY " + listOrder(context), lane)) {
            tasks.add(fromRow(row));
        }
        return tasks;
    }

    static Map<String, Integer> laneCounts(Context context) {
        LegacyImport.runOnce(context);
        Map<String, Integer> counts = new HashMap<>();
        for (String lane : LANES) counts.put(lane, 0);
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT " + LANE_SQL + " AS lane, COUNT(*) AS n FROM tasks "
                + "WHERE deleted = 0 AND status = 'todo' GROUP BY lane")) {
            counts.put((String) row.get("lane"), ((Long) row.get("n")).intValue());
        }
        return counts;
    }

    /** Moves a task to a lane; its cita stays as it was. */
    static void setLane(Context context, long id, String lane) {
        Map<String, Object> data = new HashMap<>();
        boolean column = false;
        for (String board : BOARD) column |= board.equals(lane);
        data.put("board", column ? lane : null);
        data.put("following", "waiting".equals(lane) ? 1 : 0);
        update(context, id, data);
    }

    /**
     * Plans a task on `day` (YYYY-MM-DD) in a slot (morning, afternoon, evening) or, with a null slot,
     * at any time that day; a null day takes it off the plan. It goes last in its slot, and a timed
     * block left on the day it leaves is removed.
     */
    static void plan(Context context, long id, String day, String slot) {
        LegacyImport.runOnce(context);
        PausaDb db = PausaDb.get(context);
        for (Map<String, Object> row : db.rows("SELECT id, plan_date FROM tasks WHERE local_id = ?", String.valueOf(id))) {
            String old = (String) row.get("plan_date");
            if (old != null && !old.equals(day)) TaskScheduleStore.removeDay(context, id, old);
            Map<String, Object> data = new HashMap<>();
            data.put("plan_date", day);
            data.put("plan_slot", day == null ? null : slot);
            double position = 0;
            if (day != null) {
                for (Map<String, Object> max : db.rows("SELECT MAX(position) AS max FROM tasks WHERE deleted = 0 AND plan_date = ?", day)) {
                    if (max.get("max") instanceof Number) position = ((Number) max.get("max")).doubleValue();
                }
            }
            data.put("position", day == null ? null : position + 1);
            if (day == null) { data.put("plan_time", null); data.put("plan_minutes", null); }
            db.put("tasks", (String) row.get("id"), data, false);
            changed(context);
        }
    }

    /** Open tasks planned on a day before today: what was left behind. Most recent first. */
    static List<Task> leftOver(Context context) {
        LegacyImport.runOnce(context);
        List<Task> tasks = new ArrayList<>();
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT * FROM tasks WHERE deleted = 0 AND status = 'todo' "
                + "AND plan_date IS NOT NULL AND plan_date < ? ORDER BY plan_date DESC, position LIMIT 100", DayPlanStore.day(0))) {
            tasks.add(fromRow(row));
        }
        return tasks;
    }

    /** Open tasks planned on one day. */
    static List<Task> plannedOn(Context context, String day) {
        LegacyImport.runOnce(context);
        List<Task> tasks = new ArrayList<>();
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT * FROM tasks WHERE deleted = 0 AND status != 'cancelled' "
                + "AND plan_date = ? ORDER BY CASE plan_slot WHEN 'morning' THEN 1 WHEN 'afternoon' THEN 2 WHEN 'evening' THEN 3 ELSE 0 END, position", day)) {
            tasks.add(fromRow(row));
        }
        return tasks;
    }

    static int openOn(Context context, String day) {
        return PausaDb.get(context).count("SELECT COUNT(*) FROM tasks WHERE deleted = 0 AND status = 'todo' AND plan_date = ?", day);
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
        /** Kanban column, or null for an ordinary task. */
        final String board;
        /** Backend id of the habit this task was made from, or null. */
        final String habitId;
        /** Its cita: the planned day (YYYY-MM-DD) and slot, or null. */
        final String planDate, planSlot;
        /** Its set time on planDate (minutes after midnight) and duration in minutes, or null. Read from the row. */
        Integer planTime, planMinutes;

        /** «18:30», or "" without a set time. */
        String clock() {
            return planTime == null ? "" : String.format(Locale.ROOT, "%02d:%02d", planTime / 60, planTime % 60);
        }

        Task(long id, String text, boolean done) {
            this(id, text, done, false, "", "", "", null);
        }

        Task(long id, String text, boolean done, boolean following, String waitingFor, String expectedDate, String reviewDate) {
            this(id, text, done, following, waitingFor, expectedDate, reviewDate, null);
        }

        Task(long id, String text, boolean done, boolean following, String waitingFor, String expectedDate, String reviewDate, String board) {
            this(id, text, done, following, waitingFor, expectedDate, reviewDate, board, null);
        }

        Task(long id, String text, boolean done, boolean following, String waitingFor, String expectedDate, String reviewDate, String board,
                String habitId) {
            this(id, text, done, following, waitingFor, expectedDate, reviewDate, board, habitId, null, null);
        }

        Task(long id, String text, boolean done, boolean following, String waitingFor, String expectedDate, String reviewDate, String board,
                String habitId, String planDate, String planSlot) {
            this.planDate = planDate;
            this.planSlot = planSlot;
            this.board = board;
            this.habitId = habitId;
            this.id = id;
            this.text = text;
            this.done = done;
            this.following = following;
            this.waitingFor = waitingFor;
            this.expectedDate = expectedDate;
            this.reviewDate = reviewDate;
        }

        String lane() {
            if (following) return "waiting";
            for (String column : BOARD) if (column.equals(board)) return column;
            return "inbox";
        }

        Task copy(String text, boolean done) {
            return new Task(id, text, done, following, waitingFor, expectedDate, reviewDate, board, habitId, planDate, planSlot);
        }
    }
}
