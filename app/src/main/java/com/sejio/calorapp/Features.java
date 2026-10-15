package com.sejio.calorapp;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Parts of the app Sejio can switch on or off from Ajustes → Funciones. Each one is the synced
 * setting `feature:<key>` with value "on" or "off", so a change made on the web or by an agent
 * (set_feature) reaches the phone on its next pull.
 *
 * To add a feature: add it here and to FEATURES in backend/src/domain.ts with the same key, and give
 * it a default that keeps the app as it was. The Funciones sheet lists every entry of ALL.
 */
final class Features {
    static final class Feature {
        final String key, title, description;
        final boolean defaultOn;
        Feature(String key, String title, String description, boolean defaultOn) {
            this.key = key; this.title = title; this.description = description; this.defaultOn = defaultOn;
        }
    }

    static final Feature REVIEW_ZOOM = new Feature("review_zoom", "Resumen Día · Semana · Mes",
            "Un selector encima de Hoy para alejarte del día y ver la semana o el mes.", false);

    static final Feature TASK_KANBAN = new Feature("task_kanban", "Tablero Kanban en Tareas",
            "Un segmento Kanban en Tareas con stack infinito, prioridad, baúl y fracasos, y el contador de tareas hechas.", false);

    static final Feature HEALTH_CONNECT = new Feature("health_connect", "Salud del reloj",
            "Sueño, pasos, ejercicio y pulso en reposo del Pixel Watch, leídos de Health Connect y mostrados en Hoy.", false);

    static final Feature HABIT_TASKS = new Feature("habit_tasks", "Hábito → tarea",
            "Un botón en cada hábito para ponerlo como tarea de hoy o de mañana, con un evento de día completo en Google Calendar.", false);

    static final Feature FOCUS_NOTE = new Feature("focus_note", "Nota en Hoy",
            "Una frase o un plan para los próximos días, en una línea discreta bajo el saludo de Hoy.", false);

    static final Feature[] ALL = {REVIEW_ZOOM, TASK_KANBAN, HABIT_TASKS, FOCUS_NOTE, HEALTH_CONNECT};

    private Features() {}

    static String settingId(Feature feature) { return "feature:" + feature.key; }

    static boolean isOn(Context context, Feature feature) {
        LegacyImport.runOnce(context);
        Map<String, Object> row = PausaDb.get(context).row("settings", settingId(feature));
        if (row == null || Long.valueOf(1).equals(row.get("deleted"))) return feature.defaultOn;
        Object value = row.get("value");
        return "on".equals(value) || (!"off".equals(value) && feature.defaultOn);
    }

    static void set(Context context, Feature feature, boolean on) {
        LegacyImport.runOnce(context);
        Map<String, Object> data = new HashMap<>();
        data.put("value", on ? "on" : "off");
        PausaDb.get(context).put("settings", settingId(feature), data, false);
        PausaLog.i("features", feature.key + (on ? " on" : " off"));
    }

    static int countOn(Context context) {
        int count = 0;
        for (Feature feature : ALL) if (isOn(context, feature)) count++;
        return count;
    }

    static String summary(Context context) {
        int on = countOn(context);
        return on == 0 ? "Ninguna activa" : on == ALL.length && on > 1 ? "Todas activas" : on + (on == 1 ? " activa" : " activas");
    }

    /** One switch per feature. `changed` runs after every toggle so the screen behind can adapt. */
    static void sheet(Context context, Runnable changed) {
        PausaUi.Sheet sheet = new PausaUi.Sheet(context, "Funciones");
        sheet.subtitle("Enciende solo lo que te ayude. Se sincroniza con la nube.");
        for (Feature feature : ALL) sheet.add(row(context, feature, changed), 8);
        sheet.show();
    }

    private static LinearLayout row(Context context, Feature feature, Runnable changed) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(PausaUi.dp(context, 64));
        row.setPadding(PausaUi.dp(context, 16), PausaUi.dp(context, 8), PausaUi.dp(context, 8), PausaUi.dp(context, 8));
        row.setBackground(PausaUi.surface(context, PausaUi.CREAM, 18));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(PausaUi.text(context, feature.title, 15, PausaUi.INK, false));
        TextView detail = PausaUi.text(context, feature.description, 12, PausaUi.MUTED, false);
        detail.setPadding(0, PausaUi.dp(context, 3), 0, 0);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(context);
        toggle.setContentDescription(feature.title);
        toggle.setMinHeight(PausaUi.dp(context, 48));
        toggle.setChecked(isOn(context, feature));
        toggle.setOnCheckedChangeListener((v, checked) -> { set(context, feature, checked); changed.run(); });
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        return row;
    }
}
