package com.sejio.calorapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** The owner's fixed lines and corrections. Bank data itself stays in the encrypted ledger. */
final class BudgetStore {
    private static final String PREFS = "budget", KEY = "settings";

    private BudgetStore() { }

    static Budget.Settings load(Context context) {
        String raw = prefs(context).getString(KEY, null);
        if (raw != null) {
            try { return Budget.Settings.fromJson(new JSONObject(raw)); }
            catch (Exception ignored) { /* Unreadable settings fall back to the defaults below, never to an empty budget. */ }
        }
        return Budget.Settings.defaults();
    }

    static void save(Context context, Budget.Settings settings) {
        try { prefs(context).edit().putString(KEY, settings.toJson().toString()).apply(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    static void reset(Context context) { prefs(context).edit().clear().apply(); }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
