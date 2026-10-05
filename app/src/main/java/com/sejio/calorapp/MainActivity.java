package com.sejio.calorapp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;

/**
 * App shell. Content scrolls beneath a floating navigation; a contextual action button sits beside it.
 * Sections: 0 Hoy, 1 Viajes, 2 Lista, 3 Hábitos, 4 Mañana, 5 Semana, 6 Hoy (tablero), 7 Banca.
 */
public class MainActivity extends Activity implements TodayView.Host {
    private static final int REQUEST_NOTIFICATIONS = 42;
    /** Order of the Tareas segments and the section each one opens. */
    private static final int[] TASK_SECTIONS = {2, 6, 4, 5};

    private TodayView counterView;
    private TicketPlannerView ticketPlannerView;
    private TaskListView taskListView;
    private HabitListView habitListView;
    private PlannerView plannerView;
    private PlannerView todayPlannerView;
    private WeeklyPlannerView weeklyPlannerView;
    private BankingView bankingView;
    private View[] sections;
    private PausaNavigation navigation;
    private PausaUi.Segmented taskTabs;
    private LinearLayout taskHeader;
    private LinearLayout navRow;
    private ImageButton fab;
    private View scrim;
    private FrameLayout content;
    private FrameLayout snackHost;
    private Switch notificationSwitch;
    private String fabIcon = "";
    private int currentSection = -1;
    private boolean keyboardVisible;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 26 ? Color.TRANSPARENT : PausaUi.GREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(createAppView());
        showSection(state == null ? 0 : state.getInt("section", 0));
        TaskResetScheduler.scheduleNext(this);
        syncNotificationState();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("section", currentSection); super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume(); TaskResetScheduler.scheduleNext(this); syncNotificationState();
        if (counterView != null) counterView.refresh();
        if (taskListView != null) taskListView.refresh();
        if (habitListView != null) habitListView.refresh();
        if (plannerView != null) plannerView.refresh();
        if (todayPlannerView != null) todayPlannerView.refresh();
        if (weeklyPlannerView != null) weeklyPlannerView.refresh();
        if (currentSection == 7 && bankingView != null) bankingView.refresh();
    }

    private View createAppView() {
        final boolean compactNavigation = getResources().getConfiguration().screenWidthDp < 380;
        final int navigationMargin = dp(compactNavigation ? 8 : 16);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(PausaUi.atmosphere());
        // Floating elements cast soft shadows past their own bounds; never cut them into boxes.
        root.setClipChildren(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        taskHeader = new LinearLayout(this);
        taskHeader.setOrientation(LinearLayout.VERTICAL);
        taskHeader.setPadding(dp(20), dp(10), dp(20), dp(6));
        taskTabs = new PausaUi.Segmented(this, new String[]{"Lista", "Hoy", "Mañana", "Semana"},
                index -> showSection(TASK_SECTIONS[index]));
        taskHeader.addView(taskTabs, new LinearLayout.LayoutParams(-1, -2));
        column.addView(taskHeader, new LinearLayout.LayoutParams(-1, -2));
        content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        counterView = new TodayView(this, this);
        ticketPlannerView = new TicketPlannerView(this);
        taskListView = new TaskListView(this);
        habitListView = new HabitListView(this);
        plannerView = new PlannerView(this);
        weeklyPlannerView = new WeeklyPlannerView(this);
        todayPlannerView = new PlannerView(this, 0);
        bankingView = new BankingView(this);
        sections = new View[]{counterView, ticketPlannerView, taskListView, habitListView, plannerView, weeklyPlannerView, todayPlannerView, bankingView};
        for (View section : sections) {
            section.setVisibility(View.GONE);
            content.addView(section, new FrameLayout.LayoutParams(-1, -1));
        }

        // A soft fade so content dissolves before reaching the floating bar.
        scrim = new View(this);
        scrim.setBackground(PausaUi.fade(PausaUi.CREAM_DEEP));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, dp(120), Gravity.BOTTOM));

        navRow = new LinearLayout(this);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setClipToPadding(false);
        navRow.setClipChildren(false);
        navigation = new PausaNavigation(this, this::showSection);
        navRow.addView(navigation, new LinearLayout.LayoutParams(0, dp(68), 1));
        fab = new ImageButton(this);
        fab.setBackground(PausaUi.ripple(this, PausaUi.TERRACOTTA, 34));
        fab.setElevation(dp(10));
        fab.setScaleType(ImageView.ScaleType.CENTER);
        fab.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.button_press));
        fab.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); primaryAction(); });
        LinearLayout.LayoutParams fabParams = new LinearLayout.LayoutParams(dp(compactNavigation ? 48 : 68), dp(compactNavigation ? 48 : 68));
        fabParams.leftMargin = dp(compactNavigation ? 6 : 10);
        navRow.addView(fab, fabParams);
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        navParams.leftMargin = navigationMargin; navParams.rightMargin = navigationMargin;
        root.addView(navRow, navParams);

        snackHost = new FrameLayout(this);
        snackHost.setTag(PausaUi.SNACK_HOST);
        snackHost.setClipToPadding(false);
        root.addView(snackHost, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft(), right = insets.getSystemWindowInsetRight();
            int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
            int ime;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
                keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                keyboardVisible = bottom > dp(160);
                ime = keyboardVisible ? bottom : 0;
                if (keyboardVisible) bottom = 0;
            }
            column.setPadding(left, top, right, keyboardVisible ? ime : 0);
            FrameLayout.LayoutParams np = (FrameLayout.LayoutParams) navRow.getLayoutParams();
            np.bottomMargin = bottom + dp(12);
            np.leftMargin = left + navigationMargin; np.rightMargin = right + navigationMargin;
            navRow.setLayoutParams(np);
            navRow.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
            scrim.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
            scrim.getLayoutParams().height = bottom + dp(132);
            snackHost.setPadding(left, 0, right, keyboardVisible ? ime + dp(12) : bottom + dp(92));
            applyClearance(keyboardVisible ? dp(24) : bottom + dp(108));
            return insets;
        });
        root.requestApplyInsets();
        return root;
    }

    /** Every scrolling section ends with enough room to lift its last control above the navigation. */
    private void applyClearance(int clearance) {
        List<View> scrollers = new ArrayList<>();
        collect(content, scrollers);
        for (View scroller : scrollers) {
            scroller.setPadding(scroller.getPaddingLeft(), scroller.getPaddingTop(), scroller.getPaddingRight(), clearance);
            if (scroller instanceof ViewGroup) ((ViewGroup) scroller).setClipToPadding(false);
        }
    }

    private static void collect(View view, List<View> out) {
        if (PausaUi.SCROLL_TAG.equals(view.getTag())) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static boolean isTaskSection(int section) { return section == 2 || (section >= 4 && section <= 6); }

    private void showSection(int section) {
        section = Math.max(0, Math.min(sections.length - 1, section));
        if (section == currentSection) return;
        int old = currentSection; currentSection = section;
        View focus = getCurrentFocus();
        if (focus != null) {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
        for (View page : sections) {
            page.animate().cancel(); page.setTranslationX(0); page.setTranslationY(0); page.setAlpha(1);
            page.setVisibility(View.GONE);
        }
        if (section == 0) counterView.refresh();
        if (section == 2) taskListView.refresh();
        if (section == 3) habitListView.refresh();
        if (section == 4) plannerView.refresh();
        if (section == 5) weeklyPlannerView.refresh();
        if (section == 6) todayPlannerView.refresh();
        if (section == 7) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            bankingView.refresh();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        View incoming = sections[section];
        incoming.setVisibility(View.VISIBLE);
        navigation.select(section, old != -1);
        boolean tasks = isTaskSection(section);
        boolean headerWasVisible = taskHeader.getVisibility() == View.VISIBLE;
        taskHeader.setVisibility(tasks ? View.VISIBLE : View.GONE);
        if (tasks) {
            for (int i = 0; i < TASK_SECTIONS.length; i++) if (TASK_SECTIONS[i] == section) taskTabs.select(i, headerWasVisible);
        }
        updateFab(section, old != -1);
        if (old != -1 && PausaUi.motion(this)) {
            boolean sameTab = PausaNavigation.tabFor(old) == PausaNavigation.tabFor(section);
            if (sameTab) {
                // Moving between segments slides sideways, in the direction of travel.
                int from = indexOf(old), to = indexOf(section);
                incoming.setTranslationX(dp(to > from ? 28 : -28));
                incoming.setAlpha(0f);
                incoming.animate().translationX(0).alpha(1).setDuration(300).setInterpolator(PausaUi.EASE).start();
            } else {
                // Changing destination rises softly into place.
                incoming.setTranslationY(dp(18));
                incoming.setAlpha(0f);
                incoming.animate().translationY(0).alpha(1).setDuration(340).setInterpolator(PausaUi.EASE).start();
                if (section == 0) counterView.animateIn();
            }
        }
    }

    private static int indexOf(int section) {
        for (int i = 0; i < TASK_SECTIONS.length; i++) if (TASK_SECTIONS[i] == section) return i;
        return 0;
    }

    private void updateFab(int section, boolean animate) {
        fab.setVisibility(section == 7 ? View.GONE : View.VISIBLE);
        String icon = section == 1 ? "ticket" : "plus";
        String label = section == 1 ? "Revisar y formalizar" : section == 3 ? "Nuevo hábito"
                : section == 6 ? "Añadir al plan de hoy" : section == 4 ? "Añadir al plan de mañana" : "Nueva tarea";
        fab.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= 26) fab.setTooltipText(label);
        if (icon.equals(fabIcon)) {
            if (animate && PausaUi.motion(this)) {
                fab.animate().cancel(); fab.setRotation(-90);
                fab.animate().rotation(0).setDuration(420).setInterpolator(PausaUi.SPRING).start();
            }
            return;
        }
        fabIcon = icon;
        fab.setImageDrawable(new PausaUi.Symbol(this, icon, PausaUi.SURFACE, 26).stroke(2.2f));
        if (animate && PausaUi.motion(this)) {
            fab.animate().cancel();
            fab.setScaleX(.4f); fab.setScaleY(.4f); fab.setRotation(-120);
            fab.animate().scaleX(1).scaleY(1).rotation(0).setDuration(460).setInterpolator(PausaUi.SPRING).start();
        }
    }

    private void primaryAction() {
        switch (currentSection) {
            case 7: break; // Banking has explicit per-bank actions.
            case 1: ticketPlannerView.review(); break;
            case 2: taskListView.focusComposer(); break;
            case 3: habitListView.createHabit(); break;
            case 4: plannerView.openPicker(0); break;
            case 6: todayPlannerView.openPicker(PausaUi.currentFranja()); break;
            case 5: TaskSheets.capture(this, -1, PausaUi.currentFranja(), this::refreshTasks); break;
            default: capture();
        }
    }

    private void refreshTasks() {
        counterView.refresh();
        taskListView.refresh();
        todayPlannerView.refresh();
        plannerView.refresh();
        weeklyPlannerView.refresh();
    }

    // ------------------------------------------------------------ TodayView.Host

    @Override public void open(int section) { showSection(section); }

    @Override public void capture() { TaskSheets.capture(this, 0, PausaUi.currentFranja(), this::refreshTasks); }

    @Override public void settings() {
        PausaUi.Sheet sheet = new PausaUi.Sheet(this, "Ajustes");
        LinearLayout notificationRow = new LinearLayout(this);
        notificationRow.setGravity(Gravity.CENTER_VERTICAL);
        notificationRow.setMinimumHeight(dp(64));
        notificationRow.setPadding(dp(16), dp(6), dp(8), dp(6));
        notificationRow.setBackground(PausaUi.surface(this, PausaUi.CREAM, 18));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(PausaUi.text(this, "Notificación fija", 15, PausaUi.INK, false));
        TextView detail = PausaUi.text(this, "Contadores con botones rápidos en la barra", 12, PausaUi.MUTED, false);
        detail.setPadding(0, dp(3), 0, 0);
        labels.addView(detail);
        notificationRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        notificationSwitch = new Switch(this);
        notificationSwitch.setContentDescription("Notificación fija");
        notificationSwitch.setMinHeight(dp(48));
        notificationSwitch.setChecked(CalorieStore.isNotificationEnabled(this));
        notificationSwitch.setOnCheckedChangeListener((v, checked) -> {
            CalorieStore.setNotificationEnabled(this, checked);
            if (checked) requestNotificationPermissionIfNeeded(); else NotificationHelper.cancel(this);
        });
        notificationRow.addView(notificationSwitch);
        notificationRow.setOnClickListener(v -> notificationSwitch.toggle());
        sheet.add(notificationRow, 8);
        sheet.add(counterView.settingRow("Límite de calorías", PausaUi.number(CalorieStore.getCalorieLimit(this)) + " kcal",
                () -> { sheet.dismiss(); counterView.editCalorieLimit(); }), 8);
        sheet.add(counterView.settingRow("Objetivo de cigarros", CalorieStore.getCigaretteGoal(this) + " al día",
                () -> { sheet.dismiss(); counterView.editGoal(); }), 8);
        sheet.add(counterView.settingRow("Gráficas de hábitos", HabitStore.graphWeeks(this) + " semanas",
                () -> { sheet.dismiss(); habitListView.chooseGraphWeeks(); }), 18);
        TextView signature = PausaUi.text(this, "Sejio · Pausa " + versionName() + "\nPequeños pasos. Una vida tuya.", 12, PausaUi.MUTED, false);
        signature.setGravity(Gravity.CENTER);
        signature.setLineSpacing(0, 1.2f);
        signature.setCompoundDrawables(null, new PausaUi.Symbol(this, "sunrise", PausaUi.SUN, 22), null, null);
        signature.setCompoundDrawablePadding(dp(8));
        sheet.add(signature, 6);
        sheet.dialog.setOnDismissListener(d -> notificationSwitch = null);
        sheet.show();
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    @Override public void onBackPressed() {
        if (currentSection != 0) showSection(0); else super.onBackPressed();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        else NotificationHelper.show(this);
    }

    private void syncNotificationState() {
        if (CalorieStore.isNotificationEnabled(this)) requestNotificationPermissionIfNeeded(); else NotificationHelper.cancel(this);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) NotificationHelper.show(this);
            else {
                CalorieStore.setNotificationEnabled(this, false);
                if (notificationSwitch != null) notificationSwitch.setChecked(false);
            }
        }
    }

    private int dp(int n) { return PausaUi.dp(this, n); }
}
