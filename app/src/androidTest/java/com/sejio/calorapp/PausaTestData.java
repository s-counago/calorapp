package com.sejio.calorapp;

import android.content.Context;

/** Disposable emulator only: wipes the local Pausa data that instrumented tests replace. */
final class PausaTestData {
    private PausaTestData() {}

    /** Empty PausaDb and old preferences, marked as already imported. */
    static void reset(Context context) {
        for (String prefs : new String[]{"daily_tasks", "day_boards", "short_term_planner", "weekly_habits", "calories"}) {
            context.getSharedPreferences(prefs, 0).edit().clear().commit();
        }
        PausaDb.resetForTests(context);
    }

    /** Runs the one-time SharedPreferences import again over fixtures a test just wrote. */
    static void reimport(Context context) {
        PausaDb.get(context).setState("imported", null);
        LegacyImport.forgetForTests();
        LegacyImport.runOnce(context);
    }
}
