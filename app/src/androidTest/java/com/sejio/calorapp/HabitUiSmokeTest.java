package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;

public final class HabitUiSmokeTest extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            context.getSharedPreferences("weekly_habits", 0).edit().clear().commit();
            int goal = Math.min(2, HabitStore.daysElapsedThisWeek());
            HabitStore.add(context, "Llamar a mis abuelas", goal);
            getUiAutomation();
            startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            tap("Hábitos", true);
            tap("Editar hábito: Llamar a mis abuelas", true);
            check(find("Días hechos esta semana", false) != null, "editor includes weekly count");
            for (int count = 0; count < goal; count++) tap("Aumentar días hechos esta semana", true);
            capture(context, "habit-editor-smoke.png");
            tap("Guardar", false);
            check(HabitStore.completedThisWeek(HabitStore.getAll(context).get(0)) == goal,
                    "editor saves corrected marks");
            check(HabitStore.totalPoints(HabitStore.getAll(context).get(0)) == 5,
                    "editor reconciles points");
            check(find(goal + " / " + goal + " esta semana", false) != null,
                    "card displays corrected count");
            tap("Elegir número de semanas del gráfico", true);
            tap("104 semanas", false);
            check(HabitStore.graphWeeks(context) == 104, "selector persists two-year window");
            tap("Abrir evolución de puntos de Llamar a mis abuelas", true);
            check(find("Hace 103 semanas  →  esta semana", false) != null, "chart opens enlarged");
            capture(context, "habit-expanded-smoke.png");
            tap("Cerrar", false);
            File screenshot = capture(context, "habit-ui-smoke.png");
            result.putString("stream", "PASS: " + checks + " habit UI checks. Screenshot: " + screenshot);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void settle() {
        waitForIdleSync();
        SystemClock.sleep(700);
    }

    private void tap(String label, boolean description) {
        AccessibilityNodeInfo node = find(label, description);
        if (node == null) throw new AssertionError("Missing target: " + label);
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        settle();
        node = find(label, description);
        if (node == null) throw new AssertionError("Missing target after scrolling: " + label);
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        long time = SystemClock.uptimeMillis();
        pointer(time, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
        SystemClock.sleep(100);
        pointer(time, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY());
        settle();
    }

    private void pointer(long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0);
        getUiAutomation().injectInputEvent(event, true);
        event.recycle();
    }

    private AccessibilityNodeInfo find(String label, boolean description) {
        for (int attempt = 0; attempt < 20; attempt++) {
            AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), label, description);
            if (node != null) return node;
            SystemClock.sleep(300);
        }
        return null;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label, boolean description) {
        if (node == null) return null;
        CharSequence value = description ? node.getContentDescription() : node.getText();
        if (value != null && label.equalsIgnoreCase(value.toString())) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = find(node.getChild(index), label, description);
            if (child != null) return child;
        }
        return null;
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    private File capture(Context context, String name) throws Exception {
        File screenshot = new File(context.getExternalFilesDir(null), name);
        try (FileOutputStream output = new FileOutputStream(screenshot)) {
            Bitmap bitmap = getUiAutomation().takeScreenshot();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
        }
        return screenshot;
    }
}
