package com.sejio.calorapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The week or month seen from afar: counters, habits, tasks and money. Pure Java so it can be unit
 * tested; ReviewView reads the rows and draws the result. Days are epoch days (see Ledger) and close at
 * 03:00 like the rest of the app. The counter, habit and task rules mirror review() in
 * backend/src/domain.ts, so the phone offline and an agent online tell the same story.
 */
final class Review {
    static final int WEEK = 0, MONTH = 1;
    /** Same cutoff as CalorieStore.DAY_START_HOUR and the backend. */
    static final int DAY_START_HOUR = 3;
    private static final Locale SPANISH = new Locale("es", "ES");

    private Review() {}

    /** {first, last} day of the week (Monday to Sunday) or month containing `day`, moved `offset` periods. */
    static int[] range(int day, int period, int offset) {
        if (period == WEEK) {
            int monday = day - Math.floorMod(day + 3, 7); // 1970-01-01 was a Thursday.
            int start = monday + 7 * offset;
            return new int[]{start, start + 6};
        }
        int[] c = Ledger.civil(day);
        int start = Budget.addMonths(Ledger.epochDay(c[0], c[1], 1), offset);
        return new int[]{start, Budget.addMonths(start, 1) - 1};
    }

    static final class HabitLine {
        final String name;
        final int weeklyGoal, target, done;
        HabitLine(String name, int weeklyGoal, int target, int done) {
            this.name = name; this.weeklyGoal = weeklyGoal; this.target = target; this.done = done;
        }
        boolean met() { return done >= target; }
    }

    static final class Summary {
        int period, start, end, today;
        int calorieGoal, proteinGoal, cigaretteGoal;
        /** Totals per day of the period, index 0 = start: {calories, protein, cigarettes}. */
        int[][] days;
        int daysLogged, daysWithinCalories, daysReachingProtein;
        /** Averages over the days with something logged; -1 when nothing was logged. */
        int averageCalories = -1, averageProtein = -1;
        double cigarettesPerDay = -1, previousCigarettesPerDay = -1;
        final List<HabitLine> habits = new ArrayList<>();
        int tasksDone;
        final List<String> pending = new ArrayList<>();
        int pendingCount;

        int length() { return end - start + 1; }
        boolean current() { return today >= start && today <= end; }
        /** Days of the period that have started, so a Tuesday is not judged as a whole week. */
        int elapsed() { return current() ? today - start + 1 : today < start ? 0 : length(); }
        /** Finished days (today excluded while it is still running) with nothing logged. */
        int daysMissing() {
            int finished = current() ? today - start : elapsed(), missing = 0;
            for (int i = 0; i < finished && i < days.length; i++)
                if (days[i][0] <= 0 && days[i][1] <= 0 && days[i][2] <= 0) missing++;
            return missing;
        }
        boolean proteinLogged() {
            for (int[] day : days) if (day[1] > 0) return true;
            return false;
        }
        int habitsMet() {
            int count = 0;
            for (HabitLine habit : habits) if (habit.met()) count++;
            return count;
        }
    }

    /** Totals keyed by "YYYY-MM-DD|kind" (kind: calories, protein, cigarettes). */
    static void counters(Summary summary, Map<String, Integer> totals, Map<String, Integer> previousTotals, int previousStart, int previousEnd) {
        summary.days = new int[summary.length()][3];
        String[] kinds = {"calories", "protein", "cigarettes"};
        long calories = 0, protein = 0, cigarettes = 0;
        for (int i = 0; i < summary.days.length; i++) {
            String day = Ledger.iso(summary.start + i);
            for (int k = 0; k < 3; k++) {
                Integer value = totals.get(day + "|" + kinds[k]);
                summary.days[i][k] = value == null ? 0 : value;
            }
            int[] row = summary.days[i];
            if (row[0] <= 0 && row[1] <= 0 && row[2] <= 0) continue;
            summary.daysLogged++;
            calories += row[0]; protein += row[1]; cigarettes += row[2];
            if (row[0] > 0 && row[0] <= summary.calorieGoal) summary.daysWithinCalories++;
            if (row[1] >= summary.proteinGoal) summary.daysReachingProtein++;
        }
        if (summary.daysLogged > 0) {
            summary.averageCalories = Math.round((float) calories / summary.daysLogged);
            summary.averageProtein = Math.round((float) protein / summary.daysLogged);
            summary.cigarettesPerDay = (double) cigarettes / summary.daysLogged;
        }
        int logged = 0;
        long previous = 0;
        for (int day = previousStart; day <= previousEnd; day++) {
            String iso = Ledger.iso(day);
            int c = value(previousTotals, iso + "|calories"), p = value(previousTotals, iso + "|protein"), g = value(previousTotals, iso + "|cigarettes");
            if (c <= 0 && p <= 0 && g <= 0) continue;
            logged++;
            previous += g;
        }
        if (logged > 0) summary.previousCigarettesPerDay = (double) previous / logged;
    }

    /**
     * Habits against their weekly goal scaled to the length of the period, as in the backend. Lines
     * come back weakest first so what needs attention is on top.
     */
    static void habits(Summary summary, List<String[]> habits, List<String[]> checks) {
        // habits: {id, name, weekly_goal}; checks: {habit_id, day}.
        for (String[] habit : habits) {
            int goal;
            try { goal = Math.max(1, Integer.parseInt(habit[2])); } catch (RuntimeException invalid) { goal = 1; }
            Set<String> days = new HashSet<>();
            for (String[] check : checks) if (check[0].equals(habit[0])) days.add(check[1]);
            int target = Math.max(1, Math.round(goal * summary.length() / 7f));
            summary.habits.add(new HabitLine(habit[1], goal, target, days.size()));
        }
        Collections.sort(summary.habits, (a, b) -> Float.compare((float) a.done / a.target, (float) b.done / b.target));
    }

    static String rangeLabel(int start, int end, int period) {
        int[] a = Ledger.civil(start), b = Ledger.civil(end);
        if (period == MONTH) return MONTHS[a[1] - 1] + " " + a[0];
        if (a[1] == b[1]) return a[2] + "–" + b[2] + " " + SHORT[b[1] - 1];
        return a[2] + " " + SHORT[a[1] - 1] + " – " + b[2] + " " + SHORT[b[1] - 1];
    }

    static String title(int period, int offset) {
        if (period == WEEK) return offset == 0 ? "Esta semana" : offset == -1 ? "La semana pasada" : "Hace " + -offset + " semanas";
        return offset == 0 ? "Este mes" : offset == -1 ? "El mes pasado" : "Hace " + -offset + " meses";
    }

    static String oneDecimal(double value) {
        return String.format(SPANISH, "%.1f", value);
    }

    private static int value(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }

    private static final String[] MONTHS = {"Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
            "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};
    private static final String[] SHORT = {"ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"};

    // ------------------------------------------------------------ money (stays on the phone)

    static final class Money {
        /** Free spending inside the review period. */
        long freeSpent;
        /** Open payroll cycle; -1 values when there is no open cycle. */
        long available = -1, dailyAllowance = -1, savingsAtClose = -1, savingsTarget = -1;
        int daysToPayday = -1;
        /** Trade Republic: last value and the market change (value minus cost) across the period. */
        long portfolioValue = -1, portfolioChange;
        boolean portfolioChangeKnown;
    }

    static Money money(Budget.Snapshot snapshot, Portfolio.View portfolio, int start, int end) {
        Money money = new Money();
        if (snapshot != null) {
            for (int i = snapshot.first(); i <= snapshot.last(); i++) {
                Budget.Period period = snapshot.periods.get(i);
                if (period.end <= start || period.start > end) continue;
                Budget.Cycle cycle = Budget.cycle(snapshot, i);
                for (int day = Math.max(start, period.start); day <= Math.min(end, period.end - 1); day++)
                    money.freeSpent += cycle.daily[day - period.start];
                if (cycle.current) fill(money, cycle);
            }
            if (money.available < 0 && snapshot.last() >= snapshot.first()) {
                Budget.Cycle last = Budget.cycle(snapshot, snapshot.last());
                if (last.current) fill(money, last);
            }
        }
        if (portfolio != null && !portfolio.empty() && !portfolio.history.isEmpty()) {
            Portfolio.Point latest = portfolio.history.get(portfolio.history.size() - 1);
            money.portfolioValue = latest.value;
            Portfolio.Point before = null;
            long from = startMillis(start);
            for (Portfolio.Point point : portfolio.history) if (point.at < from) before = point;
            if (before != null && before.cost >= 0 && latest.cost >= 0) {
                money.portfolioChange = (latest.value - latest.cost) - (before.value - before.cost);
                money.portfolioChangeKnown = true;
            }
        }
        return money;
    }

    private static void fill(Money money, Budget.Cycle cycle) {
        money.available = cycle.available();
        money.dailyAllowance = cycle.dailyAllowance();
        money.daysToPayday = cycle.left();
        money.savingsAtClose = cycle.savingsAtClose();
        money.savingsTarget = cycle.savingsTarget;
    }

    /** Epoch milliseconds when `day` starts in the phone's time zone, at the 03:00 cutoff. */
    static long startMillis(int day) {
        int[] c = Ledger.civil(day);
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(c[0], c[1] - 1, c[2], DAY_START_HOUR, 0, 0);
        return calendar.getTimeInMillis();
    }

}
