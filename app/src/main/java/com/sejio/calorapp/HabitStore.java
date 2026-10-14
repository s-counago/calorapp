package com.sejio.calorapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Persistence and scoring for habits that have a weekly minimum and one check per day. */
final class HabitStore {
    static final int MINIMUM_FIRST_REWARD = 5;
    private static final String PREFS_NAME = "weekly_habits";
    private static final String KEY_HABITS = "habits_v1";
    private static final String KEY_CHART_WEEKS = "chart_weeks";
    private static final String DAY_PATTERN = "yyyy-MM-dd";

    private HabitStore() {}

    /**
     * Habits and their checks sync with the backend (one habit_checks row per day done). Points are
     * derived here from the checks and kept in the local habit_scores table; weeks whose checks or
     * goal changed on another device are scored again when they are read.
     */
    static List<Habit> getAll(Context context) {
        LegacyImport.runOnce(context);
        PausaDb db = PausaDb.get(context);
        List<Habit> habits = new ArrayList<>();
        for (Map<String, Object> row : db.rows("SELECT * FROM habits WHERE deleted = 0 AND archived = 0 ORDER BY position, local_id")) {
            Habit habit = new Habit((Long) row.get("local_id"), (String) row.get("name"),
                    clampGoal(((Long) row.get("weekly_goal")).intValue()), (String) row.get("created_day"));
            habit.uid = (String) row.get("id");
            for (Map<String, Object> check : db.rows("SELECT day FROM habit_checks WHERE habit_id = ? AND deleted = 0 ORDER BY day", habit.uid)) {
                habit.completedDays.add((String) check.get("day"));
            }
            List<String> scoredDays = new ArrayList<>();
            int scoredGoal = habit.weeklyGoal;
            List<Map<String, Object>> scores = db.rows("SELECT json FROM habit_scores WHERE habit_id = ?", habit.uid);
            try {
                if (!scores.isEmpty()) {
                    JSONObject object = new JSONObject((String) scores.get(0).get("json"));
                    readIntegers(object.optJSONObject("rewardsByDay"), habit.rewardsByDay);
                    readIntegers(object.optJSONObject("baseRewards"), habit.baseRewards);
                    readStrings(object.optJSONObject("baseAwardDays"), habit.baseAwardDays);
                    readIntegers(object.optJSONObject("penaltiesByWeek"), habit.penaltiesByWeek);
                    readIntegers(object.optJSONObject("completionAdjustmentsByWeek"), habit.completionAdjustmentsByWeek);
                    readStrings(object.optJSONArray("scoredDays"), scoredDays);
                    scoredGoal = object.optInt("scoredGoal", habit.weeklyGoal);
                }
            } catch (JSONException ignored) {
                // A damaged score is rebuilt from the checks below.
            }
            boolean changed = rescore(habit, scoredDays, scoredGoal);
            changed |= settleFinishedWeeks(habit);
            if (changed) saveScore(db, habit);
            habits.add(habit);
        }
        return habits;
    }

    /** Scores again, oldest first, every week whose checks differ from the ones last scored. */
    static boolean rescore(Habit habit, List<String> scoredDays, int scoredGoal) {
        Set<String> weeks = new TreeSet<>();
        Set<String> now = new HashSet<>(habit.completedDays), before = new HashSet<>(scoredDays);
        for (String day : now) if (!before.contains(day)) weeks.add(weekStart(day));
        for (String day : before) if (!now.contains(day)) weeks.add(weekStart(day));
        if (scoredGoal != habit.weeklyGoal) weeks.add(weekStart(today()));
        for (String week : weeks) {
            String latest = latestCompletionInWeek(habit, week);
            rebuildWeekRewards(habit, week, latest == null ? week : latest);
        }
        return !weeks.isEmpty();
    }

    static long add(Context context, String name, int weeklyGoal) {
        String normalized = name.trim();
        if (normalized.isEmpty()) return -1;
        LegacyImport.runOnce(context);
        PausaDb db = PausaDb.get(context);
        long localId = System.currentTimeMillis();
        for (Map<String, Object> row : db.rows("SELECT MAX(local_id) AS max FROM habits")) {
            if (row.get("max") != null) localId = Math.max(localId, (Long) row.get("max") + 1);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("name", normalized);
        data.put("weekly_goal", clampGoal(weeklyGoal));
        data.put("created_day", today());
        data.put("archived", 0);
        return db.put("habits", PausaDb.uuid(), data, false, localId);
    }

    static void update(Context context, long id, String name, int weeklyGoal) {
        List<Habit> habits = getAll(context);
        Habit habit = find(habits, id);
        if (habit != null) update(context, id, name, weeklyGoal, completedThisWeek(habit));
    }

    static void update(Context context, long id, String name, int weeklyGoal, int completed) {
        String normalized = name.trim();
        if (normalized.isEmpty()) return;
        List<Habit> habits = getAll(context);
        Habit habit = find(habits, id);
        if (habit == null) return;
        habit.name = normalized;
        habit.weeklyGoal = clampGoal(weeklyGoal);

        // Lowering a target after already doing enough work should acknowledge that work now.
        String currentWeek = weekStart(today());
        int desired = Math.max(0, Math.min(daysElapsedThisWeek(), completed));
        if (desired == 0) habit.completedDays.remove(today());
        if (desired == daysElapsedThisWeek() && !habit.completedDays.contains(today())) {
            habit.completedDays.add(today());
        }
        habit.completionAdjustmentsByWeek.put(currentWeek,
                desired - markedDaysInWeek(habit, currentWeek));
        rebuildWeekRewards(habit, currentWeek, today());
        save(context, habits);
    }

    static void delete(Context context, long id) {
        Habit habit = find(getAll(context), id);
        if (habit != null) PausaDb.get(context).put("habits", habit.uid, new HashMap<>(), true);
    }

    static ToggleResult setDoneToday(Context context, long id, boolean done) {
        return setDoneOnDay(context, id, today(), done);
    }

    /** Package-visible date seam used by the instrumentation test and week calculations. */
    static ToggleResult setDoneOnDay(Context context, long id, String day, boolean done) {
        List<Habit> habits = getAll(context);
        Habit habit = find(habits, id);
        if (habit == null) return new ToggleResult(done, 0, false, 0, 0);
        boolean wasDone = habit.completedDays.contains(day);
        String week = weekStart(day);
        int before = totalPoints(habit);
        boolean minimumAlreadyReached = habit.baseRewards.containsKey(week);
        if (done != wasDone) {
            if (done) habit.completedDays.add(day);
            else habit.completedDays.remove(day);
            rebuildWeekRewards(habit, week, day);
            save(context, habits);
        }
        int pointChange = totalPoints(habit) - before;
        boolean minimumReached = !minimumAlreadyReached && habit.baseRewards.containsKey(week);
        int count = countInWeek(habit, week);
        return new ToggleResult(done, pointChange, minimumReached, count, habit.weeklyGoal);
    }

    static int completedThisWeek(Habit habit) {
        return countInWeek(habit, weekStart(today()));
    }

    static boolean isDoneToday(Habit habit) {
        return habit.completedDays.contains(today());
    }

    static int daysElapsedThisWeek() {
        return (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
    }

    static int graphWeeks(Context context) {
        return Math.max(10, Math.min(104, preferences(context).getInt(KEY_CHART_WEEKS, 10)));
    }

    static void setGraphWeeks(Context context, int weeks) {
        preferences(context).edit().putInt(KEY_CHART_WEEKS, Math.max(10, Math.min(104, weeks))).apply();
    }

    static int totalPoints(Habit habit) {
        long total = 0;
        for (int reward : habit.rewardsByDay.values()) total += reward;
        for (int penalty : habit.penaltiesByWeek.values()) total -= penalty;
        return clampPoints(total);
    }

    static int totalPoints(List<Habit> habits) {
        long total = 0;
        for (Habit habit : habits) total += totalPoints(habit);
        return clampPoints(total);
    }

    static int currentStreak(Habit habit) {
        String current = weekStart(today());
        if (!habit.baseRewards.containsKey(current)) current = addDays(current, -7);
        int streak = 0;
        while (habit.baseRewards.containsKey(current)) {
            streak++;
            current = addDays(current, -7);
        }
        return streak;
    }

    static int achievedThisWeek(List<Habit> habits) {
        String week = weekStart(today());
        int count = 0;
        for (Habit habit : habits) if (habit.baseRewards.containsKey(week)) count++;
        return count;
    }

    static int nextReward(Habit habit) {
        String week = weekStart(today());
        Integer base = habit.baseRewards.get(week);
        return base == null
                ? minimumRewardForWeek(habit, week)
                : bonusFor(base);
    }

    static List<Integer> sparkline(Habit habit, int weeks) {
        String currentWeek = weekStart(today());
        int pointCount = Math.max(10, Math.min(104, weeks));
        String firstWeek = addDays(currentWeek, -(pointCount - 1) * 7);
        Map<String, Integer> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> reward : habit.rewardsByDay.entrySet()) {
            String week = weekStart(reward.getKey());
            changes.put(week, value(changes, week) + reward.getValue());
        }
        for (Map.Entry<String, Integer> penalty : habit.penaltiesByWeek.entrySet()) {
            changes.put(penalty.getKey(), value(changes, penalty.getKey()) - penalty.getValue());
        }
        long cumulative = 0;
        for (Map.Entry<String, Integer> change : changes.entrySet()) {
            if (change.getKey().compareTo(firstWeek) < 0) cumulative += change.getValue();
        }
        List<Integer> values = new ArrayList<>();
        for (int index = 0; index < pointCount; index++) {
            String boundary = addDays(firstWeek, index * 7);
            cumulative += value(changes, boundary);
            values.add(clampPoints(cumulative));
        }
        return values;
    }

    static int previousWeekPenalty(Habit habit) {
        return value(habit.penaltiesByWeek, addDays(weekStart(today()), -7));
    }

    private static boolean settleFinishedWeeks(Habit habit) {
        String currentWeek = weekStart(today());
        String week = weekStart(habit.createdDay);
        boolean changed = false;
        while (week.compareTo(currentWeek) < 0) {
            if (!habit.baseRewards.containsKey(week) && !habit.penaltiesByWeek.containsKey(week)) {
                int score = scoreThroughWeek(habit, addDays(week, -7));
                int penalty = score == 0 ? 0 : clampPoints((score * 15L + 99) / 100);
                habit.penaltiesByWeek.put(week, penalty);
                changed = true;
            }
            week = addDays(week, 7);
        }
        return changed;
    }

    private static int scoreThroughWeek(Habit habit, String boundaryWeek) {
        long score = 0;
        for (Map.Entry<String, Integer> reward : habit.rewardsByDay.entrySet()) {
            if (weekStart(reward.getKey()).compareTo(boundaryWeek) <= 0) score += reward.getValue();
        }
        for (Map.Entry<String, Integer> penalty : habit.penaltiesByWeek.entrySet()) {
            if (penalty.getKey().compareTo(boundaryWeek) <= 0) score -= penalty.getValue();
        }
        return clampPoints(score);
    }

    private static int countInWeek(Habit habit, String week) {
        return Math.max(0, markedDaysInWeek(habit, week) + value(habit.completionAdjustmentsByWeek, week));
    }

    private static int markedDaysInWeek(Habit habit, String week) {
        int count = 0;
        for (String day : habit.completedDays) if (week.equals(weekStart(day))) count++;
        return count;
    }

    private static void rebuildWeekRewards(Habit habit, String week, String referenceDay) {
        Integer existingBase = habit.baseRewards.get(week);
        List<String> rewardDays = new ArrayList<>(habit.rewardsByDay.keySet());
        for (String rewardDay : rewardDays) {
            if (week.equals(weekStart(rewardDay))) habit.rewardsByDay.remove(rewardDay);
        }
        int completed = countInWeek(habit, week);
        if (completed < habit.weeklyGoal) {
            habit.baseRewards.remove(week);
            habit.baseAwardDays.remove(week);
            return;
        }
        int base = existingBase == null
                ? minimumRewardForWeek(habit, week) : existingBase;
        // This only matters after lowering a goal: keep the earned base attached
        // to the latest remaining completion so a same-day undo stays reversible.
        String awardDay = latestCompletionInWeek(habit, week);
        if (awardDay == null) awardDay = referenceDay;
        habit.baseRewards.put(week, base);
        habit.baseAwardDays.put(week, awardDay);
        habit.rewardsByDay.put(awardDay,
                clampPoints(base + (completed - habit.weeklyGoal) * (long) bonusFor(base)));
    }

    private static int minimumRewardForWeek(Habit habit, String week) {
        Integer previous = habit.baseRewards.get(addDays(week, -7));
        return previous == null ? MINIMUM_FIRST_REWARD : nextBaseReward(previous);
    }

    static int nextBaseReward(int previousReward) {
        return clampPoints((previousReward * 115L + 99) / 100);
    }

    private static int clampPoints(long points) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, points));
    }

    private static int bonusFor(int base) {
        return Math.max(1, (int) ((base + 4L) / 5)); // ceil(base * 20%)
    }

    private static String latestCompletionInWeek(Habit habit, String week) {
        String latest = null;
        for (String day : habit.completedDays) {
            if (week.equals(weekStart(day)) && (latest == null || day.compareTo(latest) > 0)) latest = day;
        }
        return latest;
    }

    static String today() {
        return format(Calendar.getInstance());
    }

    static String weekStart(String day) {
        Calendar calendar = parse(day);
        int offset = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday = 0.
        calendar.add(Calendar.DAY_OF_YEAR, -offset);
        return format(calendar);
    }

    static String addDays(String day, int days) {
        Calendar calendar = parse(day);
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return format(calendar);
    }

    private static Calendar parse(String day) {
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat formatter = new SimpleDateFormat(DAY_PATTERN, Locale.ROOT);
        formatter.setLenient(false);
        try {
            calendar.setTime(formatter.parse(day));
        } catch (ParseException error) {
            calendar.setTimeInMillis(System.currentTimeMillis());
        }
        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static String format(Calendar calendar) {
        return new SimpleDateFormat(DAY_PATTERN, Locale.ROOT).format(calendar.getTime());
    }

    private static int clampGoal(int goal) {
        return Math.max(1, Math.min(7, goal));
    }

    private static int value(Map<String, Integer> values, String key) {
        Integer value = values.get(key);
        return value == null ? 0 : value;
    }

    private static Habit find(List<Habit> habits, long id) {
        for (Habit habit : habits) if (habit.id == id) return habit;
        return null;
    }

    /** Writes what changed: the habit row, added or removed checks, and the local score. */
    private static void save(Context context, List<Habit> habits) {
        PausaDb db = PausaDb.get(context);
        for (Habit habit : habits) {
            Map<String, Object> row = db.row("habits", habit.uid);
            if (row == null || !habit.name.equals(row.get("name")) || habit.weeklyGoal != ((Long) row.get("weekly_goal")).intValue()) {
                Map<String, Object> data = new HashMap<>();
                data.put("name", habit.name);
                data.put("weekly_goal", habit.weeklyGoal);
                data.put("created_day", habit.createdDay);
                db.put("habits", habit.uid, data, false, habit.id);
            }
            Set<String> stored = new HashSet<>();
            for (Map<String, Object> check : db.rows("SELECT day FROM habit_checks WHERE habit_id = ? AND deleted = 0", habit.uid)) {
                stored.add((String) check.get("day"));
            }
            for (String day : habit.completedDays) if (!stored.contains(day)) putCheck(db, habit.uid, day, true);
            for (String day : stored) if (!habit.completedDays.contains(day)) putCheck(db, habit.uid, day, false);
            saveScore(db, habit);
        }
    }

    private static void putCheck(PausaDb db, String habitId, String day, boolean done) {
        Map<String, Object> data = new HashMap<>();
        data.put("habit_id", habitId);
        data.put("day", day);
        db.put("habit_checks", habitId + ":" + day, data, !done);
    }

    static void saveScore(PausaDb db, Habit habit) {
        try {
            ContentValues values = new ContentValues();
            values.put("habit_id", habit.uid);
            values.put("json", scoreJson(habit).toString());
            db.getWritableDatabase().insertWithOnConflict("habit_scores", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (JSONException ignored) {
            // All values are JSON primitives.
        }
    }

    static JSONObject scoreJson(Habit habit) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("rewardsByDay", integers(habit.rewardsByDay));
        object.put("baseRewards", integers(habit.baseRewards));
        object.put("baseAwardDays", strings(habit.baseAwardDays));
        object.put("penaltiesByWeek", integers(habit.penaltiesByWeek));
        object.put("completionAdjustmentsByWeek", integers(habit.completionAdjustmentsByWeek));
        object.put("scoredDays", strings(habit.completedDays));
        object.put("scoredGoal", habit.weeklyGoal);
        return object;
    }

    /** Old SharedPreferences habits, read once by LegacyImport. */
    static List<Habit> legacy(Context context) {
        List<Habit> habits = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences(context).getString(KEY_HABITS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.getJSONObject(index);
                Habit habit = new Habit(object.getLong("id"), object.getString("name"),
                        clampGoal(object.optInt("weeklyGoal", 1)), object.optString("createdDay", today()));
                readStrings(object.optJSONArray("completedDays"), habit.completedDays);
                readIntegers(object.optJSONObject("rewardsByDay"), habit.rewardsByDay);
                readIntegers(object.optJSONObject("baseRewards"), habit.baseRewards);
                readStrings(object.optJSONObject("baseAwardDays"), habit.baseAwardDays);
                readIntegers(object.optJSONObject("penaltiesByWeek"), habit.penaltiesByWeek);
                readIntegers(object.optJSONObject("completionAdjustmentsByWeek"), habit.completionAdjustmentsByWeek);
                habits.add(habit);
            }
        } catch (JSONException ignored) {
            // A damaged entry is skipped; the old preferences stay as a backup.
        }
        return habits;
    }

    private static JSONArray strings(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static JSONObject integers(Map<String, Integer> values) throws JSONException {
        JSONObject object = new JSONObject();
        for (Map.Entry<String, Integer> value : values.entrySet()) object.put(value.getKey(), value.getValue());
        return object;
    }

    private static JSONObject strings(Map<String, String> values) throws JSONException {
        JSONObject object = new JSONObject();
        for (Map.Entry<String, String> value : values.entrySet()) object.put(value.getKey(), value.getValue());
        return object;
    }

    private static void readStrings(JSONArray source, List<String> destination) throws JSONException {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) destination.add(source.getString(index));
        Collections.sort(destination);
    }

    private static void readIntegers(JSONObject source, Map<String, Integer> destination) throws JSONException {
        if (source == null) return;
        JSONArray names = source.names();
        if (names == null) return;
        for (int index = 0; index < names.length(); index++) {
            String name = names.getString(index);
            destination.put(name, source.getInt(name));
        }
    }

    private static void readStrings(JSONObject source, Map<String, String> destination) throws JSONException {
        if (source == null) return;
        JSONArray names = source.names();
        if (names == null) return;
        for (int index = 0; index < names.length(); index++) {
            String name = names.getString(index);
            destination.put(name, source.getString(name));
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static final class Habit {
        final long id;
        /** Backend id (UUID). */
        String uid;
        String name;
        int weeklyGoal;
        final String createdDay;
        final List<String> completedDays = new ArrayList<>();
        final Map<String, Integer> rewardsByDay = new LinkedHashMap<>();
        final Map<String, Integer> baseRewards = new LinkedHashMap<>();
        final Map<String, String> baseAwardDays = new LinkedHashMap<>();
        final Map<String, Integer> penaltiesByWeek = new LinkedHashMap<>();
        final Map<String, Integer> completionAdjustmentsByWeek = new LinkedHashMap<>();

        Habit(long id, String name, int weeklyGoal, String createdDay) {
            this.id = id;
            this.name = name;
            this.weeklyGoal = weeklyGoal;
            this.createdDay = createdDay;
        }
    }

    static final class ToggleResult {
        final boolean checked;
        final int pointChange;
        final boolean minimumReached;
        final int completed;
        final int goal;

        ToggleResult(boolean checked, int pointChange, boolean minimumReached, int completed, int goal) {
            this.checked = checked;
            this.pointChange = pointChange;
            this.minimumReached = minimumReached;
            this.completed = completed;
            this.goal = goal;
        }
    }
}
