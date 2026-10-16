package com.sejio.calorapp;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.TimeZone;

public final class FeedbackSmokeTest extends Instrumentation {
    private Activity activity;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context c = getTargetContext();
            for (String prefs : new String[]{"google_calendar", "daily_tasks", "day_boards", "short_term_planner", "task_schedule"}) c.getSharedPreferences(prefs, 0).edit().clear().commit();
            PausaTestData.reset(c);
            long task = TaskStore.add(c, "Preparar la propuesta");
            long stack = TaskStore.add(c, "Leer el libro");
            check(TaskScheduleStore.all(c).isEmpty(), "backlog has no calendar events");
            TaskSheets.Plan plan = new TaskSheets.Plan(0, 0); plan.minute = 9 * 60 + 15; plan.minutes = 45;
            TaskSheets.applyPlan(c, task, plan);
            TaskScheduleStore.Schedule initial = TaskScheduleStore.first(c, task, DayPlanStore.day(0));
            check(initial != null && initial.eligible && initial.minute() == 555 && initial.minutes == 45, "exact time persisted");
            java.util.Map<String, Object> synced = PausaDb.get(c).row("tasks", TaskStore.uid(c, task));
            check(Long.valueOf(555).equals(synced.get("plan_time")) && Long.valueOf(45).equals(synced.get("plan_minutes"))
                    && "morning".equals(synced.get("plan_slot")), "time synced as plan_time and plan_minutes");
            plan = TaskSheets.planOf(c, task); plan.day = 1; plan.minute = 16 * 60 + 10;
            TaskSheets.applyPlan(c, task, plan);
            TaskScheduleStore.Schedule moved = TaskScheduleStore.first(c, task, DayPlanStore.day(1));
            check(moved != null && moved.id.equals(initial.id), "moving preserves event identity");
            check(!DayPlanStore.contains(DayPlanStore.read(c, DayPlanStore.day(0)), task), "old day cleared");
            check(DayPlanStore.read(c, DayPlanStore.day(1)).get(1).contains(task), "afternoon board agrees");
            check(TaskScheduleStore.first(c, stack, null) == null, "backlog remains untouched");
            plan = TaskSheets.planOf(c, task); plan.minute = -1;
            TaskSheets.applyPlan(c, task, plan);
            check(TaskScheduleStore.first(c, task, null) == null, "clearing time removes event");
            check(DayPlanStore.contains(DayPlanStore.read(c, DayPlanStore.day(1)), task), "clearing time preserves day");
            TaskScheduleStore.restore(c, moved);
            long waiting = TaskStore.add(c, "Respuesta sobre el presupuesto");
            TaskStore.setFollowup(c, waiting, true, "De la gestoría", DayPlanStore.day(4), DayPlanStore.day(1));
            long done = TaskStore.add(c, "Enviar documentación"); TaskStore.setDone(c, done, true);
            TaskStore.setText(c, waiting, "Confirmación del presupuesto");
            check(TaskStore.find(c, waiting).following && TaskStore.find(c, waiting).waitingFor.equals("De la gestoría"), "followup metadata survives editing");
            check(!PlannerStore.getAssignments(c, 0).containsValue(waiting), "followup not actionable");
            TimeZone prior = TimeZone.getDefault();
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"));
                boolean rejected = false;
                try { TaskScheduleStore.start("2027-03-28", 150); } catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "nonexistent DST time rejected");
            } finally { TimeZone.setDefault(prior); }
            activity = startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                find(activity.getWindow().getDecorView(), "Tareas").performClick();
                check(find(activity.getWindow().getDecorView(), "2 pendientes · 1 en seguimiento · 1 hecha") != null, "three status counts visible");
            });
            capture("feedback-tareas.png");
            Dialog[] editor = new Dialog[1];
            runOnMainSync(() -> editor[0] = TaskSheets.edit(activity, TaskStore.find(c, task), null));
            capture("feedback-horario.png");
            runOnMainSync(() -> editor[0].dismiss());
            Method synthetic = BudgetUiSmokeTest.class.getDeclaredMethod("synthetic"); synthetic.setAccessible(true);
            JSONArray rows = (JSONArray) synthetic.invoke(null);
            rows.put(new JSONObject().put("id", "credit-test").put("bank", "abanca").put("productType", "card")
                    .put("productLabel", "Tarjeta de crédito").put("data", new JSONObject().put("operationDate", "2026-10-04")
                            .put("operationType", "FRA. VENTA").put("description", "COMPRA DE PRUEBA")
                            .put("amount", new JSONObject().put("amount", "-24.90").put("currency", "EUR"))));
            for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).optString("productType").equals("card")) rows.getJSONObject(i).put("productId", "fixture-credit").put("productKind", "credito");
            BudgetStore.reset(c);
            BudgetView.source = new BudgetView.Source() {
                public JSONArray movements(Context context) { return rows; }
                public JSONArray portfolios(Context context) { return new JSONArray(); }
                public long lastSync(Context context, String bank) { return 0; }
            };
            BudgetView.todayOverride = Ledger.parseIso("2026-10-05");
            runOnMainSync(() -> find(activity.getWindow().getDecorView(), "Dinero").performClick());
            SystemClock.sleep(1500);
            runOnMainSync(() -> { activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); find(activity.getWindow().getDecorView(), "Movimientos").performClick(); });
            waitForIdleSync();
            runOnMainSync(() -> find(activity.getWindow().getDecorView(), "Tarjetas de crédito").performClick());
            capture("feedback-credito.png");
            check(find(activity.getWindow().getDecorView(), "Cobro previsto · 2026-11-30") != null, "credit due date visible");
            result.putString("stream", "PASS: " + checks + " checks. feedback-*.png in external files\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure)); finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private View find(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().equalsIgnoreCase(text)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(350);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream file = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, file); }
        bitmap.recycle();
    }
}
