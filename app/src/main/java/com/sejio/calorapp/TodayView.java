package com.sejio.calorapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Paint;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The home of the app: a greeting that follows the light, today's counters and today's plan. */
final class TodayView extends PausaUi.Scroll {
    interface Host {
        void open(int section);
        void settings();
        void capture();
    }

    private final Activity activity;
    private final Host host;
    private final LinearLayout root;
    private final TextView eyebrow, greeting;
    private final Meters.SunGauge gauge;
    private final TextView calorieText, calorieOf, calorieStatus, limitChip;
    private final TextView proteinText;
    private final TextView cigaretteText, cigaretteGoal;
    private final Meters.Dots dots;
    private final PausaUi.Check ritual;
    private final TextView ritualText;
    private final LinearLayout dayList;
    private final TextView tomorrowLine;
    private int shownCalories = -1, shownProtein = -1;
    private String shownDay;
    private boolean receiverRegistered;
    private final Runnable dayBoundary = new Runnable() {
        @Override public void run() {
            if (!DayPlanStore.day(0).equals(shownDay)) refresh();
            postDelayed(this, 30_000);
        }
    };
    private final BroadcastReceiver resetReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    TodayView(Activity activity, Host host) {
        super(activity);
        this.activity = activity;
        this.host = host;
        setTag(PausaUi.SCROLL_TAG);
        setFillViewport(true);
        setClipToPadding(false);
        setVerticalScrollBarEnabled(false);
        root = column();
        root.setPadding(dp(20), dp(6), dp(20), 0);
        root.setBackground(new PausaUi.Glow(PausaUi.skyColor()));
        addView(root, new ScrollView.LayoutParams(-1, -2));

        // Brand line with settings.
        LinearLayout brand = new LinearLayout(activity);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView wordmark = PausaUi.editorial(activity, "sejio", 22);
        wordmark.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
        wordmark.setCompoundDrawables(new PausaUi.Symbol(activity, "sunrise", PausaUi.SUN, 22), null, null, null);
        wordmark.setCompoundDrawablePadding(dp(8));
        wordmark.setContentDescription("Sejio");
        brand.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        brand.addView(PausaUi.iconButton(activity, "tune", "Ajustes", PausaUi.INK, host::settings),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(brand, full());

        // Greeting.
        LinearLayout hello = column();
        eyebrow = PausaUi.eyebrow(activity, "", PausaUi.MUTED);
        hello.addView(eyebrow, full());
        greeting = PausaUi.editorial(activity, "", 36);
        greeting.setPadding(0, dp(8), 0, dp(6));
        if (Build.VERSION.SDK_INT >= 28) greeting.setAccessibilityHeading(true);
        hello.addView(greeting, full());
        hello.addView(PausaUi.text(activity, "Cosas pequeñas. Grandes días.", 14, PausaUi.MUTED, false), full());
        root.addView(hello, spaced(20, 22));

        // Calories hero.
        LinearLayout calories = column();
        calories.setBackground(PausaUi.card(activity));
        calories.setPadding(dp(18), dp(14), dp(18), dp(18));
        LinearLayout calHeader = new LinearLayout(activity);
        calHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView calLabel = PausaUi.text(activity, "Calorías", 14, PausaUi.INK, true);
        calLabel.setCompoundDrawables(new PausaUi.Symbol(activity, "flame", PausaUi.TERRACOTTA, 18), null, null, null);
        calLabel.setCompoundDrawablePadding(dp(8));
        calHeader.addView(calLabel, new LinearLayout.LayoutParams(0, -2, 1));
        limitChip = PausaUi.text(activity, "", 12, PausaUi.GREEN, true);
        limitChip.setGravity(Gravity.CENTER);
        limitChip.setMinHeight(dp(36));
        limitChip.setPadding(dp(12), 0, dp(12), 0);
        limitChip.setBackground(PausaUi.ripple(activity, PausaUi.NEUTRAL, 18));
        limitChip.setContentDescription("Editar límite de calorías");
        limitChip.setOnClickListener(v -> editCalorieLimit());
        calHeader.addView(limitChip, new LinearLayout.LayoutParams(-2, -2));
        calories.addView(calHeader, full());
        FrameLayout gaugeFrame = new FrameLayout(activity);
        gauge = new Meters.SunGauge(activity);
        gaugeFrame.addView(gauge, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout center = column();
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        calorieText = PausaUi.editorial(activity, "0", 44);
        calorieText.setGravity(Gravity.CENTER);
        center.addView(calorieText, new LinearLayout.LayoutParams(-2, -2));
        calorieOf = PausaUi.text(activity, "", 12, PausaUi.MUTED, false);
        calorieOf.setPadding(0, dp(4), 0, 0);
        center.addView(calorieOf, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        centerParams.bottomMargin = dp(30);
        gaugeFrame.addView(center, centerParams);
        gaugeFrame.setBackground(PausaUi.ripple(activity, android.graphics.Color.TRANSPARENT, 24));
        gaugeFrame.setOnClickListener(v -> calorieSheet(false));
        LinearLayout.LayoutParams gp = full();
        gp.topMargin = dp(4);
        calories.addView(gaugeFrame, gp);
        calorieStatus = PausaUi.text(activity, "", 13, PausaUi.SAGE, true);
        calorieStatus.setGravity(Gravity.CENTER);
        calorieStatus.setPadding(0, dp(2), 0, dp(14));
        calories.addView(calorieStatus, full());
        LinearLayout calActions = new LinearLayout(activity);
        for (int amount : new int[]{100, 200, 500}) {
            TextView chip = PausaUi.amountChip(activity, "+" + amount, PausaUi.TERRACOTTA, PausaUi.PEACH_SOFT,
                    "Añadir " + amount + " calorías", () -> addCalories(amount));
            weighted(calActions, chip, amount == 100 ? 0 : 8);
        }
        TextView other = PausaUi.amountChip(activity, "Otra", PausaUi.INK, PausaUi.NEUTRAL,
                "Añadir otra cantidad de calorías", () -> calorieSheet(true));
        weighted(calActions, other, 8);
        calories.addView(calActions, full());
        root.addView(calories, spaced(0, 12));

        // Protein strip.
        LinearLayout protein = new LinearLayout(activity);
        protein.setGravity(Gravity.CENTER_VERTICAL);
        protein.setBackground(PausaUi.card(activity));
        protein.setPadding(dp(18), dp(12), dp(12), dp(12));
        LinearLayout proteinLabels = column();
        proteinLabels.setBackground(PausaUi.ripple(activity, android.graphics.Color.TRANSPARENT, 14));
        proteinLabels.setOnClickListener(v -> proteinSheet());
        proteinLabels.setContentDescription("Opciones de proteína");
        TextView proteinLabel = PausaUi.text(activity, "Proteína", 13, PausaUi.MUTED, true);
        proteinLabel.setCompoundDrawables(new PausaUi.Symbol(activity, "protein", PausaUi.SAGE, 16), null, null, null);
        proteinLabel.setCompoundDrawablePadding(dp(6));
        proteinLabel.setSingleLine(true);
        proteinLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        proteinLabels.addView(proteinLabel, full());
        proteinText = PausaUi.editorial(activity, "0 g", 26);
        proteinText.setPadding(0, dp(4), 0, 0);
        proteinLabels.addView(proteinText, full());
        protein.addView(proteinLabels, new LinearLayout.LayoutParams(0, -2, 1));
        for (int grams : new int[]{10, 25, 50}) {
            TextView chip = PausaUi.amountChip(activity, "+" + grams, PausaUi.SAGE, PausaUi.SAGE_SOFT,
                    "Añadir " + grams + " g de proteína", () -> { CalorieStore.addProtein(activity, grams); countersChanged(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(50), dp(48));
            p.leftMargin = dp(5);
            protein.addView(chip, p);
        }
        root.addView(protein, spaced(0, 12));

        // Cigarettes strip.
        LinearLayout cigarettes = column();
        cigarettes.setBackground(PausaUi.card(activity));
        cigarettes.setPadding(dp(18), dp(12), dp(12), dp(14));
        LinearLayout cigRow = new LinearLayout(activity);
        cigRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout cigLabels = column();
        cigLabels.setBackground(PausaUi.ripple(activity, android.graphics.Color.TRANSPARENT, 14));
        cigLabels.setContentDescription("Editar objetivo de cigarros");
        cigLabels.setOnClickListener(v -> editGoal());
        TextView cigLabel = PausaUi.text(activity, "Cigarros", 13, PausaUi.MUTED, true);
        cigLabel.setCompoundDrawables(new PausaUi.Symbol(activity, "cigarette", PausaUi.STONE, 16), null, null, null);
        cigLabel.setCompoundDrawablePadding(dp(6));
        cigLabels.addView(cigLabel, full());
        LinearLayout cigValue = new LinearLayout(activity);
        cigValue.setGravity(Gravity.BOTTOM);
        cigaretteText = PausaUi.editorial(activity, "0", 26);
        cigValue.addView(cigaretteText);
        cigaretteGoal = PausaUi.text(activity, "", 14, PausaUi.MUTED, false);
        cigaretteGoal.setPadding(dp(4), 0, 0, dp(3));
        cigValue.addView(cigaretteGoal);
        LinearLayout.LayoutParams cvp = full();
        cvp.topMargin = dp(4);
        cigLabels.addView(cigValue, cvp);
        cigRow.addView(cigLabels, new LinearLayout.LayoutParams(0, -2, 1));
        for (int delta : new int[]{-1, 1}) {
            android.widget.ImageButton b = PausaUi.iconButton(activity, delta < 0 ? "minus" : "plus",
                    delta < 0 ? "Restar un cigarro" : "Sumar un cigarro", PausaUi.STONE, () -> {
                        CalorieStore.adjustCigaretteCount(activity, delta); countersChanged();
                    });
            b.setBackground(PausaUi.ripple(activity, PausaUi.NEUTRAL, 24));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
            p.leftMargin = dp(8);
            cigRow.addView(b, p);
        }
        cigarettes.addView(cigRow, full());
        dots = new Meters.Dots(activity);
        LinearLayout.LayoutParams dotsParams = full();
        dotsParams.topMargin = dp(10);
        dotsParams.rightMargin = dp(6);
        cigarettes.addView(dots, dotsParams);
        root.addView(cigarettes, spaced(0, 28));

        // Tu día.
        LinearLayout dayHeader = new LinearLayout(activity);
        dayHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView dayTitle = PausaUi.editorial(activity, "Tu día", 24);
        if (Build.VERSION.SDK_INT >= 28) dayTitle.setAccessibilityHeading(true);
        dayHeader.addView(dayTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button organize = PausaUi.quiet(activity, "Organizar", PausaUi.GREEN, () -> host.open(6));
        organize.setCompoundDrawables(null, null, new PausaUi.Symbol(activity, "chevron", PausaUi.GREEN, 16), null);
        organize.setCompoundDrawablePadding(dp(2));
        organize.setContentDescription("Organizar el plan de hoy");
        dayHeader.addView(organize, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(dayHeader, spaced(0, 8));

        LinearLayout ritualRow = new LinearLayout(activity);
        ritualRow.setGravity(Gravity.CENTER_VERTICAL);
        ritualRow.setPadding(dp(4), dp(4), dp(16), dp(4));
        ritualRow.setBackground(PausaUi.ripple(activity, PausaUi.SUN_SOFT, 20));
        ritual = new PausaUi.Check(activity, 0xFFC98A1C);
        ritual.setContentDescription(activity.getString(R.string.notion_priority_review));
        ritual.setListener(done -> { TaskStore.setNotionReviewDone(activity, done); styleRitual(done); });
        ritualRow.addView(ritual, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout ritualLabels = column();
        ritualLabels.addView(PausaUi.eyebrow(activity, "Ritual diario", 0xFF9A6A12), full());
        ritualText = PausaUi.text(activity, activity.getString(R.string.notion_priority_review), 15, PausaUi.INK, false);
        ritualText.setPadding(0, dp(3), 0, 0);
        ritualLabels.addView(ritualText, full());
        ritualRow.addView(ritualLabels, new LinearLayout.LayoutParams(0, -2, 1));
        ritualRow.setOnClickListener(v -> ritual.performClick());
        root.addView(ritualRow, spaced(0, 10));

        dayList = column();
        root.addView(dayList, spaced(0, 10));

        tomorrowLine = PausaUi.text(activity, "", 14, PausaUi.INK, false);
        tomorrowLine.setGravity(Gravity.CENTER_VERTICAL);
        tomorrowLine.setMinHeight(dp(56));
        tomorrowLine.setPadding(dp(16), 0, dp(14), 0);
        tomorrowLine.setBackground(PausaUi.cardRipple(activity));
        tomorrowLine.setCompoundDrawables(new PausaUi.Symbol(activity, "moon", PausaUi.GREEN, 20), null,
                new PausaUi.Symbol(activity, "chevron", PausaUi.MUTED, 18), null);
        tomorrowLine.setCompoundDrawablePadding(dp(12));
        tomorrowLine.setOnClickListener(v -> host.open(4));
        root.addView(tomorrowLine, spaced(0, 8));
        refresh();
    }

    // ------------------------------------------------------------ lifecycle

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Flags are supplied on API 33+ below.
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter filter = new IntentFilter(TaskListView.ACTION_NOTION_REVIEW_RESET);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(resetReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(resetReceiver, filter);
        receiverRegistered = true;
        postDelayed(dayBoundary, 30_000);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(dayBoundary);
        if (receiverRegistered) { activity.unregisterReceiver(resetReceiver); receiverRegistered = false; }
        super.onDetachedFromWindow();
    }

    void animateIn() { PausaUi.stagger(root, 9); }

    // ------------------------------------------------------------ data

    void refresh() {
        shownDay = DayPlanStore.day(0);
        String date = new SimpleDateFormat("EEEE · d 'de' MMMM", PausaUi.SPANISH).format(new Date());
        eyebrow.setText(date.toUpperCase(PausaUi.SPANISH));
        greeting.setText(PausaUi.greeting() + ".");
        root.setBackground(new PausaUi.Glow(PausaUi.skyColor()));
        refreshCounters();
        TaskStore.resetDailyNotionReview(activity);
        boolean reviewed = TaskStore.isNotionReviewDone(activity);
        if (ritual.isChecked() != reviewed) ritual.setChecked(reviewed);
        styleRitual(reviewed);
        refreshDay();
    }

    private void refreshCounters() {
        int calories = CalorieStore.get(activity), limit = CalorieStore.getCalorieLimit(activity);
        PausaUi.countTo(calorieText, shownCalories < 0 ? calories : shownCalories, calories, "");
        shownCalories = calories;
        calorieText.setTextColor(calories > limit ? PausaUi.TERRACOTTA : PausaUi.INK);
        calorieOf.setText("de " + PausaUi.number(limit) + " kcal");
        limitChip.setText("Límite " + PausaUi.number(limit));
        gauge.setProgress(calories, limit);
        int remaining = limit - calories;
        calorieStatus.setText(remaining > 0 ? "Quedan " + PausaUi.number(remaining) + " kcal"
                : remaining == 0 ? "Límite alcanzado" : "Límite superado en " + PausaUi.number(-(long) remaining) + " kcal");
        calorieStatus.setTextColor(remaining <= 0 ? PausaUi.TERRACOTTA : PausaUi.SAGE);
        int protein = CalorieStore.getProtein(activity);
        PausaUi.countTo(proteinText, shownProtein < 0 ? protein : shownProtein, protein, " g");
        shownProtein = protein;
        int count = CalorieStore.getCigaretteCount(activity), goal = CalorieStore.getCigaretteGoal(activity);
        cigaretteText.setText(String.valueOf(count));
        cigaretteText.setTextColor(count > goal ? PausaUi.TERRACOTTA : PausaUi.INK);
        cigaretteGoal.setText("/ " + goal);
        dots.set(count, goal);
        ((View) calorieText.getParent().getParent()).setContentDescription("Calorías: " + PausaUi.number(calories)
                + " de " + PausaUi.number(limit) + ". Abrir opciones");
    }

    private void refreshDay() {
        dayList.removeAllViews();
        Map<Long, TaskStore.Task> tasks = new HashMap<>();
        for (TaskStore.Task task : TaskStore.getAll(activity)) tasks.put(task.id, task);
        List<List<Long>> plan = DayPlanStore.read(activity, DayPlanStore.day(0));
        int now = PausaUi.currentFranja();
        LinearLayout card = column();
        card.setBackground(PausaUi.card(activity));
        card.setPadding(dp(6), dp(6), dp(14), dp(8));
        int total = 0;
        for (int section = 0; section < 3; section++) {
            boolean header = false;
            for (long id : plan.get(section)) {
                TaskStore.Task task = tasks.get(id);
                if (task == null) continue;
                if (!header) {
                    LinearLayout label = new LinearLayout(activity);
                    label.setGravity(Gravity.CENTER_VERTICAL);
                    label.setPadding(dp(12), dp(total == 0 ? 8 : 16), 0, dp(2));
                    TextView name = PausaUi.text(activity, DayPlanStore.LABELS[section], 12, PausaUi.MUTED, true);
                    name.setCompoundDrawables(new PausaUi.Symbol(activity, TaskSheets.FRANJA_ICONS[section],
                            section == now ? PausaUi.TERRACOTTA : PausaUi.MUTED, 16), null, null, null);
                    name.setCompoundDrawablePadding(dp(8));
                    label.addView(name);
                    if (section == now) {
                        TextView pill = PausaUi.eyebrow(activity, "Ahora", PausaUi.TERRACOTTA);
                        pill.setTextSize(9);
                        pill.setBackground(PausaUi.surface(activity, PausaUi.SUN_SOFT, 8));
                        pill.setPadding(dp(6), dp(3), dp(6), dp(3));
                        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
                        pp.leftMargin = dp(8);
                        label.addView(pill, pp);
                    }
                    card.addView(label, full());
                    header = true;
                }
                total++;
                card.addView(taskRow(task), full());
            }
        }
        if (total > 0) {
            dayList.addView(card, full());
        } else {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(20), dp(22), dp(20), dp(20));
            empty.setBackground(PausaUi.dashed(activity, 0xFFCFC9BC, 24));
            ImageView sun = new ImageView(activity);
            sun.setImageDrawable(new PausaUi.Symbol(activity, "sunrise", PausaUi.SUN, 32));
            empty.addView(sun, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView title = PausaUi.editorial(activity, "Tu día está en blanco.", 18);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(10), 0, dp(4));
            empty.addView(title, full());
            TextView hint = PausaUi.text(activity, "Tu día, a tu ritmo. Añade algo pequeño para empezar.", 13, PausaUi.MUTED, false);
            hint.setGravity(Gravity.CENTER);
            empty.addView(hint, full());
            Button plan0 = PausaUi.action(activity, "Planificar hoy", true, host::capture);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(48));
            bp.topMargin = dp(14);
            empty.addView(plan0, bp);
            dayList.addView(empty, full());
        }
        int tomorrow = 0;
        for (List<Long> ids : DayPlanStore.read(activity, DayPlanStore.day(1))) tomorrow += ids.size();
        tomorrowLine.setText(tomorrow == 0 ? "Prepara mañana con calma"
                : "Mañana: " + tomorrow + (tomorrow == 1 ? " tarea preparada" : " tareas preparadas"));
        tomorrowLine.setContentDescription(tomorrowLine.getText() + ". Abrir el plan de mañana");
    }

    private View taskRow(TaskStore.Task task) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setBackground(PausaUi.ripple(activity, android.graphics.Color.TRANSPARENT, 16));
        PausaUi.Check check = new PausaUi.Check(activity, PausaUi.SAGE);
        check.setChecked(task.done);
        check.setContentDescription(task.text);
        TextView text = PausaUi.text(activity, task.text, 15, PausaUi.INK, false);
        text.setLineSpacing(0, 1.1f);
        text.setPadding(0, dp(8), 0, dp(8));
        strike(text, task.done);
        check.setListener(done -> { TaskStore.setDone(activity, task.id, done); strike(text, done); });
        row.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> check.performClick());
        row.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return row;
    }

    private void strike(TextView text, boolean done) {
        text.setPaintFlags(done ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        text.setTextColor(done ? PausaUi.MUTED : PausaUi.INK);
    }

    private void styleRitual(boolean done) { strike(ritualText, done); }

    // ------------------------------------------------------------ counters

    private void addCalories(int amount) { CalorieStore.add(activity, amount); countersChanged(); }

    private void countersChanged() { refreshCounters(); NotificationHelper.show(activity); }

    private void calorieSheet(boolean focusAmount) {
        int calories = CalorieStore.get(activity), limit = CalorieStore.getCalorieLimit(activity);
        PausaUi.Sheet sheet = new PausaUi.Sheet(activity, "Calorías");
        sheet.subtitle(PausaUi.number(calories) + " de " + PausaUi.number(limit) + " kcal hoy");
        EditText amount = amountRow(sheet, "Cantidad en kcal", value -> {
            addCalories(value);
            sheet.dismiss();
            PausaUi.snack(activity, "+" + PausaUi.number(value) + " kcal", "Deshacer", () -> addCalories(-value));
        });
        View limitRow = settingRow("Límite diario", PausaUi.number(limit) + " kcal", () -> { sheet.dismiss(); editCalorieLimit(); });
        sheet.add(limitRow, 4);
        sheet.footer(PausaUi.quiet(activity, "Poner a cero", PausaUi.TERRACOTTA, () -> {
            sheet.dismiss();
            int previous = CalorieStore.get(activity);
            CalorieStore.reset(activity);
            countersChanged();
            PausaUi.snack(activity, "Calorías a cero", "Deshacer", () -> addCalories(previous));
        }), PausaUi.action(activity, "Listo", true, sheet::dismiss));
        if (focusAmount) TaskSheets.showWithKeyboard(sheet); else sheet.show();
        if (focusAmount) amount.requestFocus();
    }

    private void proteinSheet() {
        PausaUi.Sheet sheet = new PausaUi.Sheet(activity, "Proteína");
        sheet.subtitle(CalorieStore.getProtein(activity) + " g registrados hoy");
        amountRow(sheet, "Cantidad en gramos", value -> {
            CalorieStore.addProtein(activity, value);
            countersChanged();
            sheet.dismiss();
            PausaUi.snack(activity, "+" + value + " g de proteína", "Deshacer", () -> {
                CalorieStore.addProtein(activity, -value); countersChanged();
            });
        });
        sheet.footer(PausaUi.quiet(activity, "Poner a cero", PausaUi.TERRACOTTA, () -> {
            sheet.dismiss();
            int previous = CalorieStore.getProtein(activity);
            CalorieStore.resetProtein(activity);
            countersChanged();
            PausaUi.snack(activity, "Proteína a cero", "Deshacer", () -> {
                CalorieStore.addProtein(activity, previous); countersChanged();
            });
        }), PausaUi.action(activity, "Listo", true, sheet::dismiss));
        sheet.show();
    }

    private EditText amountRow(PausaUi.Sheet sheet, String hint, PausaUi.IntResult onAdd) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(activity);
        PausaUi.input(input);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Runnable add = () -> {
            try {
                int value = Integer.parseInt(input.getText().toString().trim());
                if (value <= 0) throw new NumberFormatException();
                onAdd.accept(value);
            } catch (NumberFormatException e) { input.setError("Introduce un número mayor que cero"); }
        };
        Button button = PausaUi.action(activity, "Añadir", true, add);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(52));
        bp.leftMargin = dp(8);
        row.addView(button, bp);
        input.setOnEditorActionListener((v, id, e) -> { if (PausaUi.isSubmit(id, e)) { add.run(); return true; } return false; });
        sheet.add(row, 14);
        return input;
    }

    View settingRow(String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), 0, dp(12), 0);
        row.setBackground(PausaUi.ripple(activity, PausaUi.CREAM, 18));
        row.addView(PausaUi.text(activity, label, 15, PausaUi.INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = PausaUi.text(activity, value, 14, PausaUi.MUTED, true);
        amount.setCompoundDrawables(null, null, new PausaUi.Symbol(activity, "chevron", PausaUi.MUTED, 18), null);
        amount.setCompoundDrawablePadding(dp(6));
        row.addView(amount, new LinearLayout.LayoutParams(-2, -2));
        row.setContentDescription(label + ", " + value);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    void editCalorieLimit() {
        PausaUi.numberSheet(activity, "Límite de calorías", "Kilocalorías al día. El arco se llena al alcanzarlo.",
                CalorieStore.getCalorieLimit(activity), 1, "Introduce un número entero mayor que cero", value -> {
                    CalorieStore.setCalorieLimit(activity, value); countersChanged();
                });
    }

    void editGoal() {
        PausaUi.numberSheet(activity, "Objetivo de cigarros", "Máximo diario. Cada punto de la tira es uno.",
                CalorieStore.getCigaretteGoal(activity), 0, "Introduce un número válido", value -> {
                    CalorieStore.setCigaretteGoal(activity, value); countersChanged();
                });
    }

    // ------------------------------------------------------------ helpers

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams p = full(); p.topMargin = dp(top); p.bottomMargin = dp(bottom); return p;
    }
    private void weighted(LinearLayout row, View v, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.leftMargin = dp(margin); row.addView(v, p);
    }
    private int dp(int n) { return PausaUi.dp(activity, n); }
}
