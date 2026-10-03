package com.sejio.calorapp;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Weekly recurring habits: one daily mark, an escalating streak reward and score history. */
final class HabitListView extends LinearLayout {
    private static final int[] GRAPH_WINDOWS = {10, 20, 26, 52, 104};
    private static final String[] LETTERS = {"L", "M", "X", "J", "V", "S", "D"};
    private final TextView scoreText;
    private final TextView goalText;
    private final TextView weekText;
    private final FrameLayout goalRing;
    private final Meters.Ring ring;
    private final LinearLayout weekStrip;
    private final LinearLayout habitRows;
    private final TextView graphWeeksButton;
    private int lastScore = -1;
    private String displayedDay;
    private final Runnable dayBoundary = new Runnable() {
        @Override public void run() {
            if (!HabitStore.today().equals(displayedDay)) refresh();
            postDelayed(this, 30_000);
        }
    };

    HabitListView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        ScrollView scroll = new PausaUi.Scroll(context);
        scroll.setTag(PausaUi.SCROLL_TAG);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(10), dp(20), 0);
        content.addView(PausaUi.editorial(context, "Hábitos", 30), full());
        TextView tagline = PausaUi.text(context, "Pequeños pasos que se quedan contigo", 13, PausaUi.MUTED, false);
        tagline.setPadding(0, dp(6), 0, dp(18));
        content.addView(tagline, full());

        LinearLayout hero = column();
        hero.setBackground(PausaUi.surface(context, PausaUi.NIGHT, 28));
        hero.setPadding(dp(20), dp(18), dp(18), dp(16));
        hero.setElevation(dp(2));
        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout scoreColumn = column();
        scoreColumn.addView(PausaUi.eyebrow(context, "Puntos totales", PausaUi.ON_NIGHT_MUTED), wrap());
        scoreText = PausaUi.editorial(context, "0", 52);
        scoreText.setTextColor(PausaUi.ON_NIGHT);
        LayoutParams scoreParams = wrap();
        scoreParams.topMargin = dp(4);
        scoreColumn.addView(scoreText, scoreParams);
        weekText = PausaUi.text(context, "", 12, PausaUi.ON_NIGHT_MUTED, false);
        LayoutParams weekParams = wrap();
        weekParams.topMargin = dp(6);
        scoreColumn.addView(weekText, weekParams);
        top.addView(scoreColumn, new LayoutParams(0, -2, 1));
        goalRing = new FrameLayout(context);
        ring = new Meters.Ring(context);
        goalRing.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout ringText = column();
        ringText.setGravity(Gravity.CENTER);
        goalText = PausaUi.editorial(context, "0/0", 20);
        goalText.setTextColor(PausaUi.ON_NIGHT);
        goalText.setGravity(Gravity.CENTER);
        ringText.addView(goalText, wrap());
        TextView minimums = PausaUi.text(context, "mínimos", 10, PausaUi.ON_NIGHT_MUTED, true);
        minimums.setGravity(Gravity.CENTER);
        ringText.addView(minimums, wrap());
        goalRing.addView(ringText, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        goalRing.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        top.addView(goalRing, new LayoutParams(dp(88), dp(88)));
        hero.addView(top, full());
        weekStrip = new LinearLayout(context);
        LayoutParams stripParams = full();
        stripParams.topMargin = dp(16);
        hero.addView(weekStrip, stripParams);
        content.addView(hero, fullWithBottom(22));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(PausaUi.eyebrow(context, "Tus hábitos", PausaUi.MUTED), new LayoutParams(0, -2, 1));
        graphWeeksButton = PausaUi.chip(context, "", false, this::chooseGraphWeeks);
        graphWeeksButton.setTextSize(13);
        graphWeeksButton.setCompoundDrawables(null, null, new PausaUi.Symbol(context, "down", PausaUi.GREEN, 16), null);
        graphWeeksButton.setCompoundDrawablePadding(dp(6));
        graphWeeksButton.setContentDescription("Elegir número de semanas del gráfico");
        toolbar.addView(graphWeeksButton, new LayoutParams(-2, -2));
        content.addView(toolbar, fullWithBottom(10));

        habitRows = column();
        content.addView(habitRows, full());
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));
        refresh();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(dayBoundary, 30_000);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(dayBoundary);
        super.onDetachedFromWindow();
    }

    void createHabit() { showHabitDialog(null); }

    void refresh() {
        displayedDay = HabitStore.today();
        weekText.setText(getContext().getString(R.string.habit_week_range, weekRange()));
        graphWeeksButton.setText(getContext().getString(R.string.habit_chart_weeks, HabitStore.graphWeeks(getContext())));
        List<HabitStore.Habit> habits = HabitStore.getAll(getContext());
        int score = HabitStore.totalPoints(habits);
        if (lastScore >= 0 && lastScore != score) { PausaUi.countTo(scoreText, lastScore, score, ""); PausaUi.pop(scoreText); }
        else scoreText.setText(PausaUi.number(score));
        lastScore = score;
        int achieved = HabitStore.achievedThisWeek(habits);
        goalText.setText(achieved + "/" + habits.size());
        ring.set(achieved, habits.size());
        goalRing.setContentDescription(getContext().getString(R.string.habit_goal_summary, achieved, habits.size()));
        buildWeekStrip(habits);
        habitRows.removeAllViews();
        for (HabitStore.Habit habit : habits) habitRows.addView(createHabitCard(habit), fullWithBottom(12));
        if (habits.isEmpty()) addEmptyState();
    }

    private void buildWeekStrip(List<HabitStore.Habit> habits) {
        weekStrip.removeAllViews();
        String start = HabitStore.weekStart(HabitStore.today());
        int today = HabitStore.daysElapsedThisWeek() - 1;
        for (int i = 0; i < 7; i++) {
            String day = HabitStore.addDays(start, i);
            int done = 0;
            for (HabitStore.Habit habit : habits) if (habit.completedDays.contains(day)) done++;
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView letter = PausaUi.text(getContext(), LETTERS[i], 11, i == today ? PausaUi.ON_NIGHT : PausaUi.ON_NIGHT_MUTED, i == today);
            letter.setGravity(Gravity.CENTER);
            cell.addView(letter, wrap());
            View dot = new View(getContext());
            int color = done > 0 ? PausaUi.SUN : i > today ? 0x1FF8F5ED : 0x40F8F5ED;
            dot.setBackground(PausaUi.surface(getContext(), color, 5));
            LayoutParams dp = new LayoutParams(done > 1 ? dp(16) : dp(8), dp(8));
            dp.topMargin = dp(6);
            cell.addView(dot, dp);
            if (i == today) {
                View underline = new View(getContext());
                underline.setBackground(PausaUi.surface(getContext(), PausaUi.ON_NIGHT, 1));
                LayoutParams up = new LayoutParams(dp(14), dp(2));
                up.topMargin = dp(5);
                cell.addView(underline, up);
            }
            weekStrip.addView(cell, new LayoutParams(0, -2, 1));
        }
    }

    private View createHabitCard(HabitStore.Habit habit) {
        Context c = getContext();
        LinearLayout card = column();
        card.setPadding(dp(18), dp(10), dp(8), dp(14));
        card.setBackground(PausaUi.card(c));
        int completed = HabitStore.completedThisWeek(habit);
        boolean met = completed >= habit.weeklyGoal;

        LinearLayout titleRow = new LinearLayout(c);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = PausaUi.editorial(c, habit.name, 19);
        name.setMaxLines(2);
        name.setLineSpacing(0, 1.08f);
        name.setCompoundDrawables(null, null, new PausaUi.Symbol(c, "edit", 0x80626960, 14), null);
        name.setCompoundDrawablePadding(dp(8));
        name.setPadding(0, dp(12), dp(8), dp(12));
        name.setBackground(PausaUi.ripple(c, android.graphics.Color.TRANSPARENT, 12));
        name.setContentDescription("Editar hábito: " + habit.name);
        name.setOnClickListener(v -> showHabitDialog(habit));
        titleRow.addView(name, new LayoutParams(0, -2, 1));
        PausaUi.Check mark = new PausaUi.Check(c, PausaUi.SAGE);
        mark.setDiameter(34);
        mark.setChecked(HabitStore.isDoneToday(habit));
        mark.setContentDescription(habit.name + ", marcar para hoy");
        mark.setListener(checked -> onMark(habit, checked));
        titleRow.addView(mark, new LayoutParams(dp(60), dp(60)));
        card.addView(titleRow, full());

        LinearLayout weekRow = new LinearLayout(c);
        weekRow.setGravity(Gravity.CENTER_VERTICAL);
        Meters.Week week = new Meters.Week(c);
        week.set(habit);
        weekRow.addView(week, new LayoutParams(-2, -2));
        View spacer = new View(c);
        weekRow.addView(spacer, new LayoutParams(0, 1, 1));
        TextView progressText = PausaUi.text(c, completed + " / " + habit.weeklyGoal + " esta semana", 13,
                met ? PausaUi.SAGE : PausaUi.GREEN, true);
        progressText.setPadding(dp(8), 0, dp(10), 0);
        weekRow.addView(progressText, new LayoutParams(-2, -2));
        card.addView(weekRow, full());

        int streak = HabitStore.currentStreak(habit);
        String reward = met ? "extra +" + HabitStore.nextReward(habit) : "al mínimo +" + HabitStore.nextReward(habit);
        String streakLabel = streak == 0 ? "sin racha" : "racha " + streak + " sem";
        int lastPenalty = HabitStore.previousWeekPenalty(habit);
        String penalty = lastPenalty > 0 ? " · −" + lastPenalty + " últ. semana" : "";
        TextView detailText = PausaUi.text(c, streakLabel + " · " + reward + penalty, 12, PausaUi.MUTED, false);
        if (streak > 0) {
            detailText.setCompoundDrawables(new PausaUi.Symbol(c, "flame", PausaUi.TERRACOTTA, 14), null, null, null);
            detailText.setCompoundDrawablePadding(dp(4));
        }
        LayoutParams detailParams = full();
        detailParams.topMargin = dp(10);
        card.addView(detailText, detailParams);

        LinearLayout chartRow = new LinearLayout(c);
        chartRow.setGravity(Gravity.CENTER_VERTICAL);
        HabitSparklineView chart = new HabitSparklineView(c);
        chart.setValues(HabitStore.sparkline(habit, HabitStore.graphWeeks(c)));
        chart.setContentDescription("Abrir evolución de puntos de " + habit.name);
        chart.setBackground(PausaUi.ripple(c, android.graphics.Color.TRANSPARENT, 12));
        chart.setOnClickListener(view -> showExpandedChart(habit));
        chartRow.addView(chart, new LayoutParams(0, dp(56), 1));
        LinearLayout pointsColumn = column();
        pointsColumn.setGravity(Gravity.END);
        pointsColumn.setPadding(dp(14), 0, dp(10), 0);
        int points = HabitStore.totalPoints(habit);
        TextView pointsValue = PausaUi.editorial(c, PausaUi.number(points), 22);
        pointsValue.setTextColor(PausaUi.SAGE);
        pointsColumn.addView(pointsValue, wrap());
        pointsColumn.addView(PausaUi.text(c, points == 1 ? "punto" : "puntos", 11, PausaUi.MUTED, false), wrap());
        chartRow.addView(pointsColumn, new LayoutParams(-2, -2));
        LayoutParams chartParams = full();
        chartParams.topMargin = dp(6);
        card.addView(chartRow, chartParams);
        return card;
    }

    private void onMark(HabitStore.Habit habit, boolean checked) {
        Context c = getContext();
        HabitStore.ToggleResult result = HabitStore.setDoneToday(c, habit.id, checked);
        PausaUi.snack(c, message(result), "Deshacer", () -> {
            HabitStore.setDoneToday(c, habit.id, !checked);
            refresh();
        });
        postDelayed(this::refresh, PausaUi.motion(c) ? 480 : 0);
    }

    private String message(HabitStore.ToggleResult result) {
        if (!result.checked) {
            return result.pointChange < 0 ? "Marca deshecha · " + result.pointChange + " puntos" : "Marca de hoy deshecha";
        } else if (result.minimumReached) {
            return "¡Mínimo cumplido! +" + result.pointChange + " puntos";
        } else if (result.pointChange > 0) {
            return "Repetición extra · +" + result.pointChange + (result.pointChange == 1 ? " punto" : " puntos");
        }
        return result.completed + " / " + result.goal + " esta semana";
    }

    private void addEmptyState() {
        LinearLayout empty = column();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(24), dp(28), dp(24), dp(24));
        empty.setBackground(PausaUi.dashed(getContext(), 0xFFCFC9BC, 24));
        TextView icon = PausaUi.text(getContext(), "", 16, PausaUi.GREEN, false);
        icon.setCompoundDrawables(null, new PausaUi.Symbol(getContext(), "habit", PausaUi.GREEN, 36), null, null);
        empty.addView(icon, wrap());
        TextView title = PausaUi.editorial(getContext(), "Empieza una curva nueva", 20);
        title.setGravity(Gravity.CENTER);
        LayoutParams titleParams = full();
        titleParams.topMargin = dp(12);
        empty.addView(title, titleParams);
        TextView description = PausaUi.text(getContext(),
                "Crea algo que quieras repetir.\nHoy la gráfica será plana; tu constancia la hará crecer.",
                14, PausaUi.MUTED, false);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.15f);
        LayoutParams descriptionParams = full();
        descriptionParams.topMargin = dp(8);
        descriptionParams.bottomMargin = dp(16);
        empty.addView(description, descriptionParams);
        Button create = PausaUi.action(getContext(), "Crear un hábito", true, () -> showHabitDialog(null));
        create.setContentDescription("Crear un hábito semanal");
        empty.addView(create, new LayoutParams(-2, dp(52)));
        habitRows.addView(empty, full());
    }

    private void showHabitDialog(HabitStore.Habit habit) {
        Context c = getContext();
        boolean editing = habit != null;
        String editedWeek = HabitStore.weekStart(HabitStore.today());
        int[] goal = { editing ? habit.weeklyGoal : 2 };
        int[] completed = { editing ? HabitStore.completedThisWeek(habit) : 0 };
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, editing ? "Editar hábito" : "Nuevo hábito");

        EditText name = new EditText(c);
        PausaUi.input(name);
        name.setHint("Ej. Llamar a mis abuelas");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (editing) {
            name.setText(habit.name);
            name.setSelection(name.length());
        }
        sheet.add(name, 18);

        addCounter(sheet.body, "Mínimo por semana", goal, 1, 7);
        if (editing) {
            addCounter(sheet.body, "Días hechos esta semana", completed, 0, HabitStore.daysElapsedThisWeek());
            TextView correctionHint = PausaUi.text(c,
                    "Corrige las marcas que olvidaste en los días transcurridos. Guardar ajusta los puntos de esta semana.",
                    12, PausaUi.MUTED, false);
            correctionHint.setLineSpacing(0, 1.15f);
            sheet.add(correctionHint, 14);
        }

        TextView rules = PausaUi.text(c,
                "Al cumplir: +5 puntos la primera semana. Cada semana seguida crece un 15 %, redondeando hacia arriba. "
                        + "Las marcas extra suman el 20 %. Si no llegas al mínimo, el marcador baja un 15 %.",
                12, PausaUi.MUTED, false);
        rules.setLineSpacing(0, 1.18f);
        rules.setVisibility(GONE);
        TextView rulesToggle = PausaUi.text(c, "¿Cómo se puntúa?", 13, PausaUi.GREEN, true);
        rulesToggle.setCompoundDrawables(new PausaUi.Symbol(c, "info", PausaUi.GREEN, 16), null, null, null);
        rulesToggle.setCompoundDrawablePadding(dp(8));
        rulesToggle.setMinHeight(dp(44));
        rulesToggle.setGravity(Gravity.CENTER_VERTICAL);
        rulesToggle.setOnClickListener(v -> {
            boolean show = rules.getVisibility() != VISIBLE;
            rules.setVisibility(show ? VISIBLE : GONE);
            if (show) PausaUi.rise(rules, 0);
        });
        sheet.add(rulesToggle, 2);
        sheet.add(rules, 6);

        Runnable save = () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                name.setError("Ponle un nombre al hábito");
                return;
            }
            if (editing && !editedWeek.equals(HabitStore.weekStart(HabitStore.today()))) {
                sheet.dismiss();
                refresh();
                PausaUi.snack(c, "La semana ha cambiado. Reabre el editor para corregirla.", null, null);
                return;
            }
            if (editing) HabitStore.update(c, habit.id, value, goal[0], completed[0]);
            else HabitStore.add(c, value, goal[0]);
            sheet.dismiss();
            refresh();
            if (!editing) PausaUi.snack(c, "«" + value + "» empieza hoy", null, null);
        };
        Button delete = null;
        if (editing) {
            boolean[] armed = {false};
            Button button = PausaUi.quiet(c, "Eliminar", PausaUi.TERRACOTTA, () -> { });
            button.setOnClickListener(v -> {
                if (!armed[0]) {
                    armed[0] = true;
                    button.setText("¿Seguro? Toca otra vez");
                    PausaUi.pop(button);
                    button.postDelayed(() -> { armed[0] = false; button.setText("Eliminar"); }, 3500);
                    return;
                }
                HabitStore.delete(c, habit.id);
                sheet.dismiss();
                refresh();
                PausaUi.snack(c, "Se borraron «" + habit.name + "», su historial y sus puntos", null, null);
            });
            delete = button;
        }
        sheet.footer(delete, PausaUi.action(c, editing ? "Guardar" : "Crear", true, save));
        if (sheet.dialog.getWindow() != null) {
            sheet.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | (editing ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    : WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE));
        }
        sheet.show();
        if (!editing) name.requestFocus();
    }

    private void addCounter(LinearLayout panel, String label, int[] count, int minimum, int maximum) {
        Context c = getContext();
        panel.addView(PausaUi.text(c, label, 13, PausaUi.MUTED, true), fullWithBottom(8));
        LinearLayout picker = new LinearLayout(c);
        picker.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton minus = PausaUi.iconButton(c, "minus", "Reducir " + label.toLowerCase(Locale.ROOT), PausaUi.GREEN, () -> {});
        ImageButton plus = PausaUi.iconButton(c, "plus", "Aumentar " + label.toLowerCase(Locale.ROOT), PausaUi.GREEN, () -> {});
        TextView amount = PausaUi.editorial(c, "", 22);
        amount.setGravity(Gravity.CENTER);
        Runnable renderAmount = () -> {
            amount.setText(getResources().getQuantityString(R.plurals.habit_weekly_days, count[0], count[0]));
            minus.setEnabled(count[0] > minimum);
            minus.setAlpha(count[0] > minimum ? 1f : .35f);
            plus.setEnabled(count[0] < maximum);
            plus.setAlpha(count[0] < maximum ? 1f : .35f);
        };
        minus.setOnClickListener(view -> {
            if (count[0] > minimum) count[0]--;
            renderAmount.run();
            PausaUi.pop(amount);
        });
        plus.setOnClickListener(view -> {
            if (count[0] < maximum) count[0]++;
            renderAmount.run();
            PausaUi.pop(amount);
        });
        picker.addView(minus, new LayoutParams(dp(52), dp(52)));
        picker.addView(amount, new LayoutParams(0, dp(52), 1));
        picker.addView(plus, new LayoutParams(dp(52), dp(52)));
        picker.setBackground(PausaUi.surface(c, PausaUi.NEUTRAL, 18));
        panel.addView(picker, fullWithBottom(14));
        renderAmount.run();
    }

    void chooseGraphWeeks() {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Semanas del gráfico");
        sheet.subtitle("Cuánta historia muestran las curvas de puntos.");
        int current = HabitStore.graphWeeks(c);
        for (int weeks : GRAPH_WINDOWS) {
            boolean selected = weeks == current;
            TextView option = PausaUi.text(c, c.getString(R.string.habit_chart_weeks, weeks), 16,
                    selected ? PausaUi.GREEN : PausaUi.INK, selected);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setMinHeight(dp(52));
            option.setPadding(dp(12), 0, dp(12), 0);
            option.setBackground(PausaUi.ripple(c, selected ? PausaUi.SAGE_SOFT : android.graphics.Color.TRANSPARENT, 14));
            if (selected) option.setCompoundDrawables(null, null, new PausaUi.Symbol(c, "check", PausaUi.GREEN, 20), null);
            option.setOnClickListener(v -> {
                HabitStore.setGraphWeeks(c, weeks);
                sheet.dismiss();
                refresh();
            });
            sheet.add(option, 4);
        }
        sheet.show();
    }

    private void showExpandedChart(HabitStore.Habit habit) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, habit.name);
        HabitSparklineView chart = new HabitSparklineView(c);
        TextView range = PausaUi.text(c, "", 12, PausaUi.MUTED, false);
        range.setGravity(Gravity.CENTER);
        TextView[] chips = new TextView[GRAPH_WINDOWS.length];
        Runnable render = () -> {
            int weeks = HabitStore.graphWeeks(c);
            chart.setValues(HabitStore.sparkline(habit, weeks));
            range.setText("Hace " + (weeks - 1) + " semanas  →  esta semana");
            for (int i = 0; i < chips.length; i++) PausaUi.setChip(chips[i], GRAPH_WINDOWS[i] == weeks);
        };
        sheet.add(chart, 8);
        chart.getLayoutParams().height = dp(200);
        sheet.add(range, 16);
        TextView points = PausaUi.editorial(c, PausaUi.number(HabitStore.totalPoints(habit)) + " puntos actuales", 20);
        points.setTextColor(PausaUi.SAGE);
        points.setGravity(Gravity.CENTER);
        sheet.add(points, 18);
        sheet.add(PausaUi.eyebrow(c, "Semanas", PausaUi.MUTED), 8);
        LinearLayout row = new LinearLayout(c);
        for (int i = 0; i < GRAPH_WINDOWS.length; i++) {
            final int weeks = GRAPH_WINDOWS[i];
            chips[i] = PausaUi.chip(c, String.valueOf(weeks), false, () -> {
                HabitStore.setGraphWeeks(c, weeks);
                render.run();
                refresh();
            });
            chips[i].setPadding(0, 0, 0, 0);
            chips[i].setContentDescription(weeks + " semanas en el gráfico");
            LayoutParams p = new LayoutParams(0, -2, 1);
            if (i > 0) p.leftMargin = dp(6);
            row.addView(chips[i], p);
        }
        sheet.add(row, 4);
        render.run();
        sheet.footer(null, PausaUi.action(c, "Cerrar", true, sheet::dismiss));
        sheet.show();
    }

    private String weekRange() {
        Calendar start = Calendar.getInstance();
        int offset = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        start.add(Calendar.DAY_OF_YEAR, -offset);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_YEAR, 6);
        Locale spanish = PausaUi.SPANISH;
        if (start.get(Calendar.MONTH) == end.get(Calendar.MONTH)) {
            return "Semana " + new SimpleDateFormat("d", spanish).format(start.getTime())
                    + "–" + new SimpleDateFormat("d 'de' MMMM", spanish).format(end.getTime());
        }
        return "Semana " + new SimpleDateFormat("d MMM", spanish).format(start.getTime())
                + " – " + new SimpleDateFormat("d MMM", spanish).format(end.getTime());
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(VERTICAL);
        return layout;
    }

    private LayoutParams full() { return new LayoutParams(-1, -2); }
    private LayoutParams wrap() { return new LayoutParams(-2, -2); }
    private LayoutParams fullWithBottom(int bottom) {
        LayoutParams params = full();
        params.bottomMargin = dp(bottom);
        return params;
    }
    private int dp(float value) { return PausaUi.dp(getContext(), value); }
}
