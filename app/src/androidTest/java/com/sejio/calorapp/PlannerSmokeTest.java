package com.sejio.calorapp;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;

/** Run only on a disposable test device: these fixtures replace task/planner preferences. */
public final class PlannerSmokeTest extends Instrumentation {
    private Activity activity;
    private PlannerView planner;
    private long first, second, third;
    private int checks;
    private int dayOffset = 1;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null) dayOffset = Integer.parseInt(args.getString("dayOffset", "1"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            context.getSharedPreferences("daily_tasks", 0).edit().clear().commit();
            context.getSharedPreferences("day_boards", 0).edit().clear().commit();
            context.getSharedPreferences("short_term_planner", 0).edit().clear().commit();
            first = TaskStore.add(context, "Preparar el informe");
            second = TaskStore.add(context, "Comprar para la cena");
            third = TaskStore.add(context, "Preparar comida");
            Calendar time = Calendar.getInstance();
            time.add(Calendar.DAY_OF_YEAR, 1);
            time.set(Calendar.HOUR_OF_DAY, 9);
            time.set(Calendar.MINUTE, 0);
            long early = time.getTimeInMillis();
            time.set(Calendar.HOUR_OF_DAY, 18);
            long late = time.getTimeInMillis();
            context.getSharedPreferences("short_term_planner", 0).edit()
                    .putString("assignments", "{\"" + early + "\":" + first + ",\"" + late + "\":" + second + "}").commit();
            List<List<Long>> migrated = DayPlanStore.read(context, DayPlanStore.day(1));
            check(migrated.get(0).contains(first) && migrated.get(1).contains(second), "legacy migration");
            migrated.get(2).add(third);
            DayPlanStore.save(context, DayPlanStore.day(1), migrated);
            check(DayPlanStore.read(context, DayPlanStore.day(1)).get(2).contains(third), "persistence");
            long removed = TaskStore.add(context, "Deleted fixture");
            migrated.get(2).add(removed);
            DayPlanStore.save(context, DayPlanStore.day(1), migrated);
            TaskStore.delete(context, removed);
            check(!DayPlanStore.contains(DayPlanStore.read(context, DayPlanStore.day(1)), removed), "deleted references filtered");
            List<List<Long>> today = DayPlanStore.empty();
            long todayId = TaskStore.add(context, "Pasear");
            today.get(1).add(todayId);
            DayPlanStore.save(context, DayPlanStore.day(0), today);
            TaskStore.setDone(context, todayId, true);
            if (dayOffset == 0) DayPlanStore.save(context, DayPlanStore.day(0),
                    DayPlanStore.read(context, DayPlanStore.day(1)));
            Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            runOnMainSync(() -> {
                check(findText(activity.getWindow().getDecorView(), "Hoy") != null, "today section");
                findText(activity.getWindow().getDecorView(), "Tareas").performClick();
                check(((Integer) field(activity, "currentSection")) == 2, "Tareas opens the list");
                ((PausaUi.Segmented) field(activity, "taskTabs")).item(dayOffset == 0 ? 1 : 2).performClick();
                check(((Integer) field(activity, "currentSection")) == (dayOffset == 0 ? 6 : 4), "segment opens the board");
                planner = (PlannerView) field(activity, dayOffset == 0 ? "todayPlannerView" : "plannerView");
                LinearLayout[] zones = (LinearLayout[]) field(planner, "zones");
                findDescriptionPrefix(zones[0], "Añadir tareas:").performClick();
                Dialog sheet = (Dialog) field(planner, "sheet");
                View root = sheet.getWindow().getDecorView();
                EditText input = findInput(root);
                input.setText("Revisar documentación");
                findText(root, "Añadir").performClick();
                check(input.length() == 0 && sheet.isShowing(), "continuous input");
                input.setText("Enviar propuesta");
                findText(root, "Añadir").performClick();
                check(DayPlanStore.read(context, DayPlanStore.day(dayOffset)).get(0).size() == 3, "multiple new tasks");
                if (dayOffset == 1) {
                    findText(root, "Pasear").performClick();
                    check(TaskStore.getAll(context).stream().anyMatch(t -> t.text.equals("Pasear") && !t.done), "repeat completed creates pending copy");
                } else {
                    check(DayPlanStore.read(context, DayPlanStore.day(1)).get(0).size() == 1, "editing today preserves tomorrow");
                }
                sheet.dismiss();
            });
            waitForIdleSync();
            SystemClock.sleep(300);
            // Exercise a real long-press gesture and Android's native drag dispatch.
            LinearLayout[] cards = (LinearLayout[]) field(planner, "cards");
            View source = findTag(cards[0], first);
            int[] from = new int[2], to = new int[2];
            source.getLocationOnScreen(from);
            cards[1].getLocationOnScreen(to);
            float x = from[0] + source.getWidth() / 2f;
            float y = from[1] + source.getHeight() / 2f;
            float endY = to[1] + cards[1].getHeight() - 5;
            long down = SystemClock.uptimeMillis();
            pointer(down, MotionEvent.ACTION_DOWN, x, y);
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 200);
            check(field(planner, "dragged") != null, "long press starts drag");
            for (int i = 1; i <= 20; i++) {
                pointer(down, MotionEvent.ACTION_MOVE, x, y + (endY - y) * i / 20f);
                SystemClock.sleep(35);
            }
            for (int i = 0; i < 4; i++) {
                cards[1].getLocationOnScreen(to);
                endY = to[1] + 10;
                pointer(down, MotionEvent.ACTION_MOVE, x, endY);
                SystemClock.sleep(100);
            }
            pointer(down, MotionEvent.ACTION_UP, x, endY);
            waitForIdleSync();
            SystemClock.sleep(300);
            check(DayPlanStore.read(context, DayPlanStore.day(dayOffset)).get(1).contains(first), "drag across sections: " + DayPlanStore.read(context, DayPlanStore.day(dayOffset)));
            runOnMainSync(() -> {
                LinearLayout[] updated = (LinearLayout[]) field(planner, "cards");
                findTag(updated[1], first).performClick();
                Dialog edit = (Dialog) field(planner, "sheet");
                findText(edit.getWindow().getDecorView(), "Quitar del plan").performClick();
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                check(!DayPlanStore.contains(DayPlanStore.read(context, DayPlanStore.day(dayOffset)), first), "remove from plan");
                check(TaskStore.getAll(context).stream().anyMatch(t -> t.id == first), "remove preserves task");
                findText(activity.getWindow().getDecorView(), "Deshacer").performClick();
                check(DayPlanStore.read(context, DayPlanStore.day(dayOffset)).get(1).contains(first), "undo restores section");
                List<List<Long>> before = DayPlanStore.read(context, DayPlanStore.day(dayOffset));
                planner.refresh();
                check(before.equals(DayPlanStore.read(context, DayPlanStore.day(dayOffset))), "refresh preserves order");
            });
            waitForIdleSync();
            File screenshot = new File(context.getExternalFilesDir(null), dayOffset == 0 ? "planner-today-smoke.png" : "planner-smoke.png");
            SystemClock.sleep(800); // Let the dialog dismissal animation finish before visual QA.
            try (FileOutputStream output = new FileOutputStream(screenshot)) {
                getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            runOnMainSync(() -> verifyRollover(context));
            result.putString("stream", "PASS: " + checks + " checks. Screenshot: " + screenshot);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void verifyRollover(Context context) {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid"));
        now.clear();
        now.set(2030, Calendar.DECEMBER, 31, 23, 59, 59);
        List<List<Long>> upcoming = DayPlanStore.empty();
        upcoming.get(0).add(first);
        upcoming.get(0).add(second);
        upcoming.get(2).add(third);
        DayPlanStore.save(context, "2031-01-01", upcoming);
        PlannerView today = new PlannerView(activity, 0, () -> now);
        PlannerView tomorrow = new PlannerView(activity, 1, () -> now);
        check(((List<?>) field(tomorrow, "plan")).equals(upcoming), "tomorrow reads its calendar date");
        check(((List<?>) field(today, "plan")).equals(DayPlanStore.empty()), "today does not show tomorrow early");
        findTag(((LinearLayout[]) field(tomorrow, "cards"))[0], first).performClick();
        Dialog oldEditor = (Dialog) field(tomorrow, "sheet");
        now.add(Calendar.SECOND, 2);
        ((Runnable) field(today, "clockTick")).run();
        ((Runnable) field(tomorrow, "clockTick")).run();
        check(((List<?>) field(today, "plan")).equals(upcoming), "midnight preserves tomorrow's order and dayparts in today");
        check(((List<?>) field(tomorrow, "plan")).equals(DayPlanStore.empty()), "new tomorrow is a separate day");
        check(!oldEditor.isShowing(), "midnight dismisses the previous day's editor");
        check(TaskStore.getAll(context).stream().filter(task -> task.id == first).count() == 1, "rollover does not duplicate tasks");
        check(DayPlanStore.read(context, "2031-01-01").equals(upcoming), "rollover preserves stored assignments");
        PlannerView reopened = new PlannerView(activity, 0, () -> now);
        check(((List<?>) field(reopened, "plan")).equals(upcoming), "reopening after midnight shows the prepared plan");
        now.add(Calendar.DAY_OF_YEAR, 2);
        today.refresh();
        check(((List<?>) field(today, "plan")).equals(DayPlanStore.empty()), "skipped days do not carry old tasks forward");
        context.getSharedPreferences("day_boards", 0).edit().remove("2031-01-01").apply();
    }
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private void pointer(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        getUiAutomation().injectInputEvent(event, true); event.recycle();
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = findText(((ViewGroup) view).getChildAt(i), text);
            if (result != null) return result;
        }
        return null;
    }
    private static View findDescriptionPrefix(View view, String prefix) {
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = findDescriptionPrefix(((ViewGroup) view).getChildAt(i), prefix);
            if (result != null) return result;
        }
        return null;
    }
    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText result = findInput(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private static View findTag(ViewGroup group, long id) {
        for (int i = 0; i < group.getChildCount(); i++) if (Long.valueOf(id).equals(group.getChildAt(i).getTag())) return group.getChildAt(i);
        throw new AssertionError("Missing task " + id);
    }
}
