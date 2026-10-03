package com.sejio.calorapp;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Run on a disposable emulator. Exercises the Amanecer shell with real touches and captures visual states. */
public final class PausaUiSmokeTest extends Instrumentation {
    private static final String[] LABELS = {"Diario", "Viajes", "Lista", "Hábitos", "Mañana", "Semana", "Plan de hoy"};
    private Activity activity;
    private int checks;
    private File output;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context c = getTargetContext();
            output = new File(c.getExternalFilesDir(null), "pausa-review"); output.mkdirs();
            activity = startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            capture("01-hoy");
            PausaNavigation nav = (PausaNavigation) field(activity, "navigation");
            View fab = (View) field(activity, "fab");
            runOnMainSync(() -> {
                check(nav.getHeight() >= PausaUi.dp(activity, 48), "navigation has comfortable touch targets");
                check(nav.getWidth() < activity.getWindow().getDecorView().getWidth(), "navigation floats with side clearance");
                check(fab.getWidth() >= PausaUi.dp(activity, 48), "action button is a comfortable target");
            });
            for (int i = 0; i < LABELS.length; i++) {
                select(LABELS[i]);
                final int expected = i;
                runOnMainSync(() -> {
                    check(((Integer) field(activity, "currentSection")) == expected, "touch navigates to " + LABELS[expected]);
                    String parent = expected == 1 ? "Viajes" : expected == 3 ? "Hábitos" : expected == 0 ? "Hoy" : "Tareas";
                    check(findDescription(nav, parent).isSelected(), "selected tab " + parent);
                    View[] pages = (View[]) field(activity, "sections");
                    int visible = 0; for (View page : pages) if (page.getVisibility() == View.VISIBLE) visible++;
                    check(visible == 1, "exactly one page visible");
                    boolean tasks = expected == 2 || expected >= 4;
                    check(((View) field(activity, "taskHeader")).getVisibility() == (tasks ? View.VISIBLE : View.GONE),
                            "task segments only inside Tareas");
                });
                capture("tab-" + i);
            }
            // Quick counters with real touches, then restore fixture values.
            select("Diario");
            View counter = (View) field(activity, "counterView");
            int before = CalorieStore.get(c), proteinBefore = CalorieStore.getProtein(c);
            tap(findDescription(counter, "Añadir 100 calorías"));
            check(CalorieStore.get(c) == before + 100, "calorie action");
            tap(findDescription(counter, "Añadir 25 g de proteína"));
            check(CalorieStore.getProtein(c) == proteinBefore + 25, "protein action");
            capture("02-diary");
            ScrollView scroll = (ScrollView) counter;
            runOnMainSync(() -> scroll.fullScroll(View.FOCUS_DOWN)); settle();
            View last = (View) field(counter, "tomorrowLine");
            check(bottom(last) <= top(nav), "last dashboard control clears the floating navigation");
            capture("03-diary-bottom");
            runOnMainSync(() -> {
                CalorieStore.add(c, before - CalorieStore.get(c));
                CalorieStore.addProtein(c, proteinBefore - CalorieStore.getProtein(c));
            });
            // Settings sheet holds the sticky notification switch.
            runOnMainSync(() -> scroll.fullScroll(View.FOCUS_UP)); settle();
            tap(findDescription(counter, "Ajustes"));
            check(field(activity, "notificationSwitch") != null, "settings sheet opens with the notification switch");
            capture("03b-settings");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle();
            check(field(activity, "notificationSwitch") == null, "back closes settings");
            // Drafts, keyboard safety, and return to the list.
            select("Lista");
            EditText input = (EditText) field(field(activity, "taskListView"), "taskInput");
            tap(input);
            runOnMainSync(() -> {
                input.setText("Una tarea larga para comprobar el teclado y la conservación del borrador");
                input.requestFocus();
                ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            });
            settle(); capture("04-keyboard");
            if (Build.VERSION.SDK_INT >= 30) {
                check(!nav.isShown(), "navigation hides while the keyboard is open");
                android.graphics.Insets ime = activity.getWindow().getDecorView().getRootWindowInsets().getInsets(WindowInsets.Type.ime());
                check(bottom(input) < activity.getWindow().getDecorView().getHeight() - ime.bottom, "input clears keyboard");
            }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle();
            check(nav.isShown(), "navigation returns after keyboard closes");
            select("Semana"); select("Lista");
            check(input.length() > 0, "draft survives tab changes");
            runOnMainSync(() -> input.setText(""));
            // Agenda: choose another day and open a half-hour block.
            select("Semana");
            WeeklyPlannerView week = (WeeklyPlannerView) field(activity, "weeklyPlannerView");
            List<View> days = new ArrayList<>();
            findAll(week, "Ver ", days);
            check(days.size() == 7, "seven days in the strip");
            tap(days.get(1)); settle(); capture("05-week-day");
            check(days.size() == 7 && findDescriptionPrefix(week, "Bloque ") != null, "agenda shows half-hour blocks");
            tap(findDescriptionPrefix(week, "Bloque ")); settle();
            Dialog slot = (Dialog) field(week, "sheet");
            check(slot != null && slot.isShowing(), "block opens its sheet");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle();
            check(field(week, "sheet") == null, "back closes the block sheet");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle();
            check(((Integer) field(activity, "currentSection")) == 0, "back returns to today");
            // Sheet drag: short drags cancel, long drags dismiss, back closes.
            select("Mañana");
            PlannerView planner = (PlannerView) field(activity, "plannerView");
            tap(findDescriptionPrefix(planner, "Añadir tareas:")); settle();
            Dialog sheet = (Dialog) field(planner, "sheet"); capture("06-sheet");
            View handle = findDescription(sheet.getWindow().getDecorView(), "Desliza hacia abajo para cerrar");
            swipe(handle, 30); settle(); check(sheet.isShowing(), "short sheet drag cancels");
            swipe(handle, 130); settle(); check(!sheet.isShowing(), "downward drag dismisses sheet");
            tap(findDescriptionPrefix(planner, "Añadir tareas:")); settle();
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle();
            check(field(planner, "sheet") == null, "back dismisses sheet");
            // The action button follows the section.
            select("Hábitos");
            check("Nuevo hábito".contentEquals(fab.getContentDescription()), "action creates habits in Hábitos");
            select("Viajes");
            check("Revisar y formalizar".contentEquals(fab.getContentDescription()), "action reviews trips in Viajes");
            select("Lista");
            check("Nueva tarea".contentEquals(fab.getContentDescription()), "action adds tasks in the list");
            // Fast navigation cannot leave two screens or a partially animated page.
            runOnMainSync(() -> {
                for (String label : new String[]{"Diario", "Semana", "Mañana", "Viajes", "Lista"}) selectDirect(label);
            }); settle();
            check(((Integer) field(activity, "currentSection")) == 2, "rapid navigation settles on last tab");
            runOnMainSync(() -> {
                View[] pages = (View[]) field(activity, "sections");
                for (View page : pages) if (page.getVisibility() == View.VISIBLE) check(page.getAlpha() == 1f, "visible page is fully opaque");
            });
            capture("07-tasks-final");
            result.putString("stream", "PASS: " + checks + " UI checks. Captures: " + output);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static int segment(String label) {
        return label.equals("Lista") ? 0 : label.equals("Plan de hoy") ? 1 : label.equals("Mañana") ? 2 : label.equals("Semana") ? 3 : -1;
    }
    private void select(String label) throws Exception {
        View nav = (View) field(activity, "navigation");
        int segment = segment(label);
        if (segment >= 0) {
            tap(findDescription(nav, "Tareas"));
            tap(((PausaUi.Segmented) field(activity, "taskTabs")).item(segment));
        } else tap(findDescription(nav, label.equals("Diario") ? "Hoy" : label));
        settle();
    }
    private void selectDirect(String label) {
        View nav = (View) field(activity, "navigation");
        int segment = segment(label);
        if (segment >= 0) {
            findDescription(nav, "Tareas").performClick();
            ((PausaUi.Segmented) field(activity, "taskTabs")).item(segment).performClick();
        } else findDescription(nav, label.equals("Diario") ? "Hoy" : label).performClick();
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(650); }
    private void tap(View view) {
        if (view == null) throw new AssertionError("Missing touch target");
        runOnMainSync(() -> view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true));
        waitForIdleSync();
        int[] p = new int[2]; view.getLocationOnScreen(p);
        float x = p[0] + view.getWidth() / 2f, y = p[1] + view.getHeight() / 2f;
        long t = SystemClock.uptimeMillis(); pointer(t, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(120); pointer(t, MotionEvent.ACTION_UP, x, y); settle();
    }
    private void swipe(View view, int dp) {
        int[] p = new int[2]; view.getLocationOnScreen(p);
        float x = p[0] + view.getWidth() / 2f, y = p[1] + view.getHeight() / 2f;
        long t = SystemClock.uptimeMillis(); pointer(t, MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 12; i++) {
            pointer(t, MotionEvent.ACTION_MOVE, x, y + PausaUi.dp(activity, dp) * i / 12f); SystemClock.sleep(16);
        }
        pointer(t, MotionEvent.ACTION_UP, x, y + PausaUi.dp(activity, dp));
    }
    private void pointer(long t, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0);
        getUiAutomation().injectInputEvent(e, true); e.recycle();
    }
    private void capture(String name) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) {
            Bitmap bitmap = getUiAutomation().takeScreenshot();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle();
        }
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static int top(View view) { int[] p = new int[2]; view.getLocationOnScreen(p); return p[1]; }
    private static int bottom(View view) { return top(view) + view.getHeight(); }
    private static Object field(Object o, String name) {
        try { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static View findDescription(View v, String text) { return find(v, text, 0); }
    private static View findDescriptionPrefix(View v, String text) { return find(v, text, 1); }
    private static View find(View v, String text, int mode) {
        CharSequence value = mode < 2 ? v.getContentDescription() : v instanceof TextView ? ((TextView) v).getText() : null;
        if (v.isShown() && value != null && (mode % 2 == 0 ? value.toString().equals(text) : value.toString().startsWith(text))) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View found = find(((ViewGroup) v).getChildAt(i), text, mode); if (found != null) return found;
        }
        return null;
    }
    private static void findAll(View v, String prefix, List<View> out) {
        CharSequence value = v.getContentDescription();
        if (value != null && value.toString().startsWith(prefix)) out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) findAll(((ViewGroup) v).getChildAt(i), prefix, out);
    }
}
