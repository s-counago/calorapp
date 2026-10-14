package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import java.util.Map;

/**
 * Disposable emulator only, driven step by step from the PC against `wrangler dev` (see
 * app/README or CLAUDE.md): `-e step reset|login|edit|sync|dump [-e token pd_…]`.
 */
public final class CloudSyncProbe extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            PausaLog.init(context);
            String step = arguments.getString("step", "dump");
            StringBuilder out = new StringBuilder(step).append(": ");
            switch (step) {
                case "reset":
                    PausaTestData.reset(context);
                    CloudAuth.prefs(context).edit().clear().commit();
                    break;
                case "login":
                    CloudAuth.store(context, arguments.getString("token"), "emu-device", "emulador@test");
                    break;
                case "edit":
                    long task = TaskStore.add(context, arguments.getString("title", "Tarea sin cobertura"));
                    java.util.List<java.util.List<Long>> plan = DayPlanStore.read(context, DayPlanStore.day(0));
                    plan.get(0).add(task);
                    DayPlanStore.save(context, DayPlanStore.day(0), plan);
                    CalorieStore.add(context, 350);
                    long habit = HabitStore.add(context, "Caminar", 3);
                    HabitStore.setDoneToday(context, habit, true);
                    TaskStore.setFollowup(context, TaskStore.add(context, "Esperar respuesta"), true, "Laura", "", "2026-10-20");
                    break;
                case "screens":
                    for (Class<?> screen : new Class<?>[]{CloudActivity.class, DiagnosticsActivity.class}) {
                        Activity activity = startActivitySync(new android.content.Intent(context, screen).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                        waitForIdleSync();
                        android.os.SystemClock.sleep(800);
                        java.io.File file = new java.io.File(context.getExternalFilesDir(null), screen.getSimpleName() + ".png");
                        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                            getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
                        }
                        activity.finish();
                        out.append(file.getName()).append(' ');
                    }
                    break;
                case "sync":
                    out.append(PausaSync.SyncWorker.round(context, 0)).append(' ');
                    break;
                default:
                    break;
            }
            PausaDb db = PausaDb.get(context);
            out.append("outbox=").append(db.count("SELECT COUNT(*) FROM outbox"))
                    .append(" cursor=").append(db.state("cursor", "0"))
                    .append(" error=").append(db.state("last_error", ""))
                    .append(" calories=").append(CalorieStore.get(context))
                    .append(" logs=").append(db.count("SELECT COUNT(*) FROM log_queue"));
            for (TaskStore.Task item : TaskStore.getAll(context)) {
                out.append("\n  task ").append(item.id).append(" ").append(item.text).append(item.done ? " [hecha]" : "")
                        .append(item.following ? " [espera " + item.waitingFor + "]" : "");
            }
            for (Map<String, Object> row : db.rows("SELECT local_id, plan_date, plan_slot FROM tasks WHERE plan_date IS NOT NULL")) {
                out.append("\n  plan ").append(row.get("local_id")).append(" ").append(row.get("plan_date")).append(" ").append(row.get("plan_slot"));
            }
            result.putString("stream", out.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
