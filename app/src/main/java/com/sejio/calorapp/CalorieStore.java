package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class CalorieStore {
    static final String PREFS_NAME = "calories";
    static final String KEY_TOTAL = "total";
    static final String KEY_CALORIE_LIMIT = "calorie_limit";
    static final String KEY_CIGARETTE_COUNT = "cigarette_count";
    static final String KEY_CIGARETTE_GOAL = "cigarette_goal";
    static final String KEY_PROTEIN_TOTAL = "protein_total";
    static final String KEY_PROTEIN_GOAL = "protein_goal";
    private static final String KEY_NOTIFICATION_ENABLED = "notification_enabled";
    private static final int STEP = 100;
    private static final int DEFAULT_CIGARETTE_GOAL = 12;

    private CalorieStore() {
    }

    // Counters are entries ("+300 kcal at 14:02") in metric_entries, summed per calendar day, so
    // additions made offline here and on the web never overwrite each other. Goals are settings.

    static int get(Context context) {
        return total(context, "calories");
    }

    static int addStep(Context context) {
        return add(context, STEP);
    }

    static int getCalorieLimit(Context context) {
        return goal(context, "calorie_goal", 2450);
    }

    static void setCalorieLimit(Context context, int limit) {
        if (limit < 1) throw new IllegalArgumentException("El límite debe ser positivo");
        setGoal(context, "calorie_goal", limit);
    }

    static int add(Context context, int amount) {
        log(context, "calories", amount);
        return get(context);
    }

    /** Clears today's calories with one negative entry, so the reset also reaches other devices. */
    static void reset(Context context) {
        log(context, "calories", -get(context));
    }

    static int getCigaretteCount(Context context) {
        return total(context, "cigarettes");
    }

    static int adjustCigaretteCount(Context context, int delta) {
        int current = getCigaretteCount(context);
        log(context, "cigarettes", Math.max(0, current + delta) - current);
        return getCigaretteCount(context);
    }

    static int getCigaretteGoal(Context context) {
        return goal(context, "cigarette_goal", DEFAULT_CIGARETTE_GOAL);
    }

    static void setCigaretteGoal(Context context, int goal) {
        setGoal(context, "cigarette_goal", Math.max(1, goal));
    }

    static int getProtein(Context context) {
        return total(context, "protein");
    }

    static int addProtein(Context context, int grams) {
        int current = getProtein(context);
        log(context, "protein", Math.max(0, current + grams) - current);
        return getProtein(context);
    }

    static int getProteinGoal(Context context) {
        return goal(context, "protein_goal", 120);
    }

    static void setProteinGoal(Context context, int grams) {
        setGoal(context, "protein_goal", Math.max(1, grams));
    }

    static void resetProtein(Context context) {
        log(context, "protein", -getProtein(context));
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    static int total(Context context, String kind) {
        return total(context, kind, today());
    }

    /** Total of a counter on any day (YYYY-MM-DD). */
    static int total(Context context, String kind, String day) {
        LegacyImport.runOnce(context);
        for (Map<String, Object> row : PausaDb.get(context).rows(
                "SELECT COALESCE(SUM(amount), 0) AS total FROM metric_entries WHERE deleted = 0 AND day = ? AND kind = ?", day, kind)) {
            return (int) Math.round(((Number) row.get("total")).doubleValue());
        }
        return 0;
    }

    /**
     * Fixes a forgotten day: sets its total by adding the difference as one correction entry, like
     * set_counter in the backend, so entries made on another device still add up.
     */
    static void setTotal(Context context, String kind, String day, int total) {
        log(context, kind, Math.max(0, total) - total(context, kind, day), day);
    }

    private static void log(Context context, String kind, int amount) {
        log(context, kind, amount, today());
    }

    private static void log(Context context, String kind, int amount, String day) {
        if (amount == 0) return;
        LegacyImport.runOnce(context);
        Map<String, Object> data = new HashMap<>();
        data.put("day", day);
        data.put("kind", kind);
        data.put("amount", (double) amount);
        data.put("at", System.currentTimeMillis());
        PausaDb.get(context).put("metric_entries", PausaDb.uuid(), data, false);
    }

    static int goal(Context context, String key, int fallback) {
        LegacyImport.runOnce(context);
        Map<String, Object> row = PausaDb.get(context).row("settings", key);
        if (row == null || Long.valueOf(1).equals(row.get("deleted"))) return fallback;
        try { return (int) Math.round(Double.parseDouble((String) row.get("value"))); }
        catch (RuntimeException invalid) { return fallback; }
    }

    private static void setGoal(Context context, String key, int value) {
        LegacyImport.runOnce(context);
        Map<String, Object> data = new HashMap<>();
        data.put("value", String.valueOf(value));
        PausaDb.get(context).put("settings", key, data, false);
    }

    static boolean isNotificationEnabled(Context context) {
        return preferences(context).getBoolean(KEY_NOTIFICATION_ENABLED, true);
    }

    static void setNotificationEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
