package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;

final class CalorieStore {
    private static final String PREFS_NAME = "calories";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_CALORIE_LIMIT = "calorie_limit";
    private static final String KEY_CIGARETTE_COUNT = "cigarette_count";
    private static final String KEY_CIGARETTE_GOAL = "cigarette_goal";
    private static final String KEY_PROTEIN_TOTAL = "protein_total";
    private static final String KEY_PROTEIN_GOAL = "protein_goal";
    private static final String KEY_NOTIFICATION_ENABLED = "notification_enabled";
    private static final int STEP = 100;
    private static final int DEFAULT_CIGARETTE_GOAL = 12;

    private CalorieStore() {
    }

    static int get(Context context) {
        return preferences(context).getInt(KEY_TOTAL, 0);
    }

    static int addStep(Context context) {
        return add(context, STEP);
    }

    static int getCalorieLimit(Context context) {
        return preferences(context).getInt(KEY_CALORIE_LIMIT, 2450);
    }

    static void setCalorieLimit(Context context, int limit) {
        if (limit < 1) throw new IllegalArgumentException("El límite debe ser positivo");
        preferences(context).edit().putInt(KEY_CALORIE_LIMIT, limit).apply();
    }

    static int add(Context context, int amount) {
        int total = get(context) + amount;
        preferences(context).edit().putInt(KEY_TOTAL, total).apply();
        return total;
    }

    static void reset(Context context) {
        preferences(context).edit().putInt(KEY_TOTAL, 0).apply();
    }

    static int getCigaretteCount(Context context) {
        return preferences(context).getInt(KEY_CIGARETTE_COUNT, 0);
    }

    static int adjustCigaretteCount(Context context, int delta) {
        int count = Math.max(0, getCigaretteCount(context) + delta);
        preferences(context).edit().putInt(KEY_CIGARETTE_COUNT, count).apply();
        return count;
    }

    static int getCigaretteGoal(Context context) {
        return preferences(context).getInt(KEY_CIGARETTE_GOAL, DEFAULT_CIGARETTE_GOAL);
    }

    static void setCigaretteGoal(Context context, int goal) {
        int normalizedGoal = Math.max(1, goal);
        preferences(context).edit().putInt(KEY_CIGARETTE_GOAL, normalizedGoal).apply();
    }

    static int getProtein(Context context) {
        return preferences(context).getInt(KEY_PROTEIN_TOTAL, 0);
    }

    static int addProtein(Context context, int grams) {
        int total = Math.max(0, getProtein(context) + grams);
        preferences(context).edit().putInt(KEY_PROTEIN_TOTAL, total).apply();
        return total;
    }

    static int getProteinGoal(Context context) {
        return preferences(context).getInt(KEY_PROTEIN_GOAL, 120);
    }

    static void setProteinGoal(Context context, int grams) {
        preferences(context).edit().putInt(KEY_PROTEIN_GOAL, Math.max(1, grams)).apply();
    }

    static void resetProtein(Context context) {
        preferences(context).edit().putInt(KEY_PROTEIN_TOTAL, 0).apply();
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
