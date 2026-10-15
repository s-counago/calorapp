package com.sejio.calorapp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hoy seen from afar: the week or the month with health, habits, tasks and money in one column. It
 * lives inside TodayView behind the Día · Semana · Mes selector (feature review_zoom) and never
 * blocks anything: it only reads.
 */
final class ReviewView extends LinearLayout {
    private final Activity activity;
    private final TodayView.Host host;
    private final TextView eyebrow, title;
    private final ImageButton next;
    private final LinearLayout health, habits, tasks, money;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int period = Review.WEEK, offset;
    private long generation;

    ReviewView(Activity activity, TodayView.Host host) {
        super(activity);
        this.activity = activity;
        this.host = host;
        setOrientation(VERTICAL);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(PausaUi.iconButton(activity, "back", "Periodo anterior", PausaUi.INK, () -> move(-1)),
                new LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column();
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        eyebrow = PausaUi.eyebrow(activity, "", PausaUi.MUTED);
        eyebrow.setGravity(Gravity.CENTER);
        titles.addView(eyebrow, new LayoutParams(-1, -2));
        title = PausaUi.editorial(activity, "", 30);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(6), 0, 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        titles.addView(title, new LayoutParams(-1, -2));
        header.addView(titles, new LayoutParams(0, -2, 1));
        next = PausaUi.iconButton(activity, "chevron", "Periodo siguiente", PausaUi.INK, () -> move(1));
        header.addView(next, new LayoutParams(dp(48), dp(48)));
        addView(header, spaced(18, 18));

        health = card("Salud", "flame", PausaUi.TERRACOTTA, null);
        habits = card("Hábitos", "habit", PausaUi.SAGE, () -> host.open(3));
        tasks = card("Tareas", "list", PausaUi.GREEN, () -> host.open(2));
        money = card("Dinero", "wallet", PausaUi.GREEN, () -> host.open(7));
    }

    void show(int period) {
        if (this.period != period) offset = 0;
        this.period = period;
        refresh();
    }

    private void move(int delta) {
        if (offset + delta > 0) return;
        offset += delta;
        refresh();
    }

    void refresh() {
        int today = Ledger.parseIso(CalorieStore.today());
        int[] range = Review.range(today, period, offset);
        int[] previous = Review.range(today, period, offset - 1);
        eyebrow.setText(Review.rangeLabel(range[0], range[1], period).toUpperCase(PausaUi.SPANISH));
        title.setText(Review.title(period, offset));
        next.setAlpha(offset < 0 ? 1f : .3f);
        next.setEnabled(offset < 0);

        Review.Summary summary = new Review.Summary();
        summary.period = period; summary.start = range[0]; summary.end = range[1]; summary.today = today;
        summary.calorieGoal = CalorieStore.getCalorieLimit(activity);
        summary.proteinGoal = CalorieStore.getProteinGoal(activity);
        summary.cigaretteGoal = CalorieStore.getCigaretteGoal(activity);
        Review.counters(summary, totals(range[0], range[1]), totals(previous[0], previous[1]), previous[0], previous[1]);
        PausaDb db = PausaDb.get(activity);
        List<String[]> habitRows = new ArrayList<>(), checkRows = new ArrayList<>();
        for (Map<String, Object> row : db.rows("SELECT id, name, weekly_goal FROM habits WHERE deleted = 0 AND archived = 0 ORDER BY position, local_id"))
            habitRows.add(new String[]{String.valueOf(row.get("id")), String.valueOf(row.get("name")), String.valueOf(row.get("weekly_goal"))});
        for (Map<String, Object> row : db.rows("SELECT habit_id, day FROM habit_checks WHERE deleted = 0 AND day BETWEEN ? AND ?",
                Ledger.iso(range[0]), Ledger.iso(range[1])))
            checkRows.add(new String[]{String.valueOf(row.get("habit_id")), String.valueOf(row.get("day"))});
        Review.habits(summary, habitRows, checkRows);
        for (Map<String, Object> row : db.rows("SELECT COUNT(*) AS n FROM tasks WHERE deleted = 0 AND status = 'done' AND completed_at >= ? AND completed_at < ?",
                String.valueOf(Review.startMillis(range[0])), String.valueOf(Review.startMillis(range[1] + 1))))
            summary.tasksDone = ((Number) row.get("n")).intValue();
        String end = Ledger.iso(range[1]);
        for (Map<String, Object> row : db.rows("SELECT title FROM tasks WHERE deleted = 0 AND status = 'todo' AND following = 0 "
                + "AND ((due_date IS NOT NULL AND due_date <= ?) OR (plan_date IS NOT NULL AND plan_date <= ?)) "
                + "ORDER BY COALESCE(due_date, plan_date), created_at", end, end)) {
            summary.pendingCount++;
            if (summary.pending.size() < 3) summary.pending.add(String.valueOf(row.get("title")));
        }

        renderHealth(summary);
        renderHabits(summary);
        renderTasks(summary);
        loadMoney(range[0], range[1]);
    }

    private Map<String, Integer> totals(int start, int end) {
        Map<String, Integer> totals = new HashMap<>();
        for (Map<String, Object> row : PausaDb.get(activity).rows(
                "SELECT day, kind, SUM(amount) AS total FROM metric_entries WHERE deleted = 0 AND day BETWEEN ? AND ? GROUP BY day, kind",
                Ledger.iso(start), Ledger.iso(end)))
            totals.put(row.get("day") + "|" + row.get("kind"), (int) Math.round(((Number) row.get("total")).doubleValue()));
        return totals;
    }

    // ------------------------------------------------------------ sections

    private void renderHealth(Review.Summary s) {
        clear(health);
        if (s.daysLogged == 0) { line(health, s.elapsed() == 0 ? "Aún no ha empezado." : "Nada apuntado en este periodo.", PausaUi.MUTED); return; }
        DayBars bars = new DayBars(activity, s);
        LayoutParams params = new LayoutParams(-1, dp(96));
        params.topMargin = dp(4); params.bottomMargin = dp(10);
        health.addView(bars, params);
        line(health, s.daysWithinCalories + " de " + s.daysLogged + (s.daysLogged == 1 ? " día" : " días")
                + " dentro del límite · media " + PausaUi.number(s.averageCalories) + " kcal", PausaUi.INK);
        if (s.proteinLogged())
            line(health, "Proteína media " + s.averageProtein + " g de " + s.proteinGoal + " · "
                    + s.daysReachingProtein + (s.daysReachingProtein == 1 ? " día" : " días") + " con objetivo", PausaUi.INK);
        else line(health, "Proteína sin apuntar", PausaUi.MUTED);
        String cigarettes = "Cigarros " + Review.oneDecimal(s.cigarettesPerDay) + " al día (objetivo " + s.cigaretteGoal + ")";
        if (s.previousCigarettesPerDay >= 0) {
            double delta = s.cigarettesPerDay - s.previousCigarettesPerDay;
            if (Math.abs(delta) >= .05) cigarettes += " · " + Review.oneDecimal(Math.abs(delta)) + (delta < 0 ? " menos" : " más") + " que antes";
        }
        line(health, cigarettes, s.cigarettesPerDay > s.cigaretteGoal ? PausaUi.TERRACOTTA : PausaUi.INK);
        int missing = s.daysMissing();
        if (missing > 0) line(health, missing + (missing == 1 ? " día" : " días") + " sin apuntar", PausaUi.MUTED);
    }

    private void renderHabits(Review.Summary s) {
        clear(habits);
        if (s.habits.isEmpty()) { line(habits, "Sin hábitos todavía.", PausaUi.MUTED); return; }
        line(habits, s.habitsMet() + " de " + s.habits.size() + " cumplidos" + (s.current() ? " por ahora" : ""), PausaUi.INK);
        for (Review.HabitLine habit : s.habits) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);
            TextView name = PausaUi.text(activity, habit.name, 14, PausaUi.INK, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LayoutParams(0, -2, 1));
            row.addView(PausaUi.text(activity, habit.done + "/" + habit.target, 13, habit.met() ? PausaUi.SAGE : PausaUi.MUTED, true),
                    new LayoutParams(-2, -2));
            habits.addView(row, new LayoutParams(-1, -2));
            LayoutParams barParams = new LayoutParams(-1, dp(6));
            barParams.topMargin = dp(4);
            habits.addView(new ThinBar(activity, habit.done, habit.target), barParams);
        }
    }

    private void renderTasks(Review.Summary s) {
        clear(tasks);
        line(tasks, s.tasksDone + (s.tasksDone == 1 ? " hecha" : " hechas"), PausaUi.INK);
        if (s.pendingCount == 0) { line(tasks, "Nada pendiente con fecha.", PausaUi.MUTED); return; }
        line(tasks, s.pendingCount + (s.pendingCount == 1 ? " pendiente" : " pendientes") + " con fecha o plan hasta el "
                + (s.period == Review.WEEK ? "domingo" : "fin de mes"), PausaUi.TERRACOTTA);
        for (String pending : s.pending) line(tasks, "· " + pending, PausaUi.MUTED);
    }

    private void loadMoney(int start, int end) {
        long version = ++generation;
        clear(money);
        line(money, "Leyendo movimientos…", PausaUi.MUTED);
        Context app = activity.getApplicationContext();
        worker.execute(() -> {
            Review.Money result = null;
            try {
                Budget.Snapshot snapshot = null;
                List<Ledger.Txn> txns = Ledger.fromDatabase(BudgetView.source.movements(app), TimeZone.getDefault());
                if (!txns.isEmpty()) snapshot = Budget.snapshot(txns, BudgetStore.load(app), BudgetView.today());
                result = Review.money(snapshot, Portfolio.from(BudgetView.source.portfolios(app)), start, end);
            } catch (Exception error) {
                PausaLog.w("review", "money unavailable", error);
            }
            final Review.Money done = result;
            post(() -> { if (version == generation) renderMoney(done); });
        });
    }

    private void renderMoney(Review.Money m) {
        clear(money);
        if (m == null) { line(money, "No se pudieron leer los movimientos.", PausaUi.MUTED); return; }
        line(money, "Gasto libre en el periodo " + Budget.money(m.freeSpent), PausaUi.INK);
        if (m.available >= 0) {
            line(money, "Disponible " + Budget.money(m.available) + " · " + Budget.money(m.dailyAllowance) + " al día hasta la nómina ("
                    + m.daysToPayday + (m.daysToPayday == 1 ? " día)" : " días)"), m.available < 0 ? PausaUi.TERRACOTTA : PausaUi.INK);
            if (m.savingsTarget > 0)
                line(money, "Ahorro previsto " + Budget.money(m.savingsAtClose) + " de " + Budget.money(m.savingsTarget),
                        m.savingsAtClose < m.savingsTarget ? PausaUi.TERRACOTTA : PausaUi.SAGE);
        }
        if (m.portfolioValue >= 0) {
            String text = "Cartera " + Budget.money(m.portfolioValue);
            if (m.portfolioChangeKnown) text += " · " + (m.portfolioChange >= 0 ? "+" : "−") + Budget.money(Math.abs(m.portfolioChange)) + " de mercado";
            line(money, text, PausaUi.INK);
        }
    }

    // ------------------------------------------------------------ helpers

    private LinearLayout card(String label, String icon, int color, Runnable open) {
        LinearLayout card = column();
        card.setBackground(open == null ? PausaUi.card(activity) : PausaUi.cardRipple(activity));
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        TextView heading = PausaUi.text(activity, label, 14, PausaUi.INK, true);
        heading.setCompoundDrawables(new PausaUi.Symbol(activity, icon, color, 18), null,
                open == null ? null : new PausaUi.Symbol(activity, "chevron", PausaUi.MUTED, 16), null);
        heading.setCompoundDrawablePadding(dp(8));
        card.addView(heading, new LayoutParams(-1, -2));
        LinearLayout body = column();
        body.setPadding(0, dp(6), 0, 0);
        card.addView(body, new LayoutParams(-1, -2));
        if (open != null) {
            card.setOnClickListener(v -> open.run());
            card.setContentDescription(label + ". Abrir");
        }
        addView(card, spaced(0, 12));
        return body;
    }

    private void line(LinearLayout parent, String text, int color) {
        TextView view = PausaUi.text(activity, text, 14, color, false);
        view.setPadding(0, dp(3), 0, dp(3));
        parent.addView(view, new LayoutParams(-1, -2));
    }

    private static void clear(LinearLayout body) { body.removeAllViews(); }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(VERTICAL);
        return column;
    }

    private LayoutParams spaced(int top, int bottom) {
        LayoutParams params = new LayoutParams(-1, -2);
        params.topMargin = dp(top); params.bottomMargin = dp(bottom);
        return params;
    }

    private int dp(int value) { return PausaUi.dp(activity, value); }

    /** A quiet progress line for habits: sage when met, no handle, nothing to drag. */
    static final class ThinBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float ratio;

        ThinBar(Context c, int value, int goal) {
            super(c);
            ratio = goal <= 0 ? 0 : Math.min(1f, value / (float) goal);
            track.setColor(PausaUi.NEUTRAL);
            fill.setColor(value >= goal ? PausaUi.SAGE : 0xFF8FA883);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            if (ratio <= 0) return;
            rect.set(0, 0, Math.max(getHeight(), getWidth() * ratio), getHeight());
            canvas.drawRoundRect(rect, r, r, fill);
        }
    }

    /** Calories per day against the limit: sage under it, terracotta over it, a dashed line at the limit. */
    static final class DayBars extends View {
        private final Review.Summary summary;
        private final Paint under = new Paint(Paint.ANTI_ALIAS_FLAG), over = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG), limit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private static final String[] WEEKDAYS = {"L", "M", "X", "J", "V", "S", "D"};

        DayBars(Context c, Review.Summary summary) {
            super(c);
            this.summary = summary;
            under.setColor(PausaUi.SAGE);
            over.setColor(PausaUi.TERRACOTTA);
            empty.setColor(PausaUi.NEUTRAL);
            limit.setColor(PausaUi.MUTED);
            limit.setStyle(Paint.Style.STROKE);
            limit.setStrokeWidth(PausaUi.dp(c, 1));
            limit.setPathEffect(new DashPathEffect(new float[]{PausaUi.dp(c, 4), PausaUi.dp(c, 4)}, 0));
            label.setColor(PausaUi.MUTED);
            label.setTextSize(PausaUi.dp(c, 11));
            label.setTextAlign(Paint.Align.CENTER);
            setContentDescription("Calorías por día frente al límite de " + summary.calorieGoal + " kcal");
        }

        @Override protected void onDraw(Canvas canvas) {
            int count = summary.days.length;
            boolean week = summary.period == Review.WEEK;
            float labels = PausaUi.dp(getContext(), 16);
            float height = getHeight() - labels, width = getWidth();
            float slot = width / count, gap = Math.max(1, slot * (week ? .3f : .2f));
            int max = summary.calorieGoal;
            for (int[] day : summary.days) max = Math.max(max, day[0]);
            float scale = height / (max * 1.05f);
            float radius = Math.min(PausaUi.dp(getContext(), 4), (slot - gap) / 2);
            for (int i = 0; i < count; i++) {
                int calories = summary.days[i][0];
                float left = i * slot + gap / 2, right = (i + 1) * slot - gap / 2;
                if (week || i % 7 == 0)
                    canvas.drawText(week ? WEEKDAYS[i] : String.valueOf(i + 1), (left + right) / 2, getHeight() - PausaUi.dp(getContext(), 2), label);
                if (summary.start + i > summary.today) continue; // Days still to come stay blank.
                float top = calories > 0 ? height - calories * scale : height - PausaUi.dp(getContext(), 3);
                rect.set(left, top, right, height);
                canvas.drawRoundRect(rect, radius, radius, calories <= 0 ? empty : calories > summary.calorieGoal ? over : under);
            }
            float y = height - summary.calorieGoal * scale;
            canvas.drawLine(0, y, width, y, limit);
        }
    }
}
