package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Payday-to-payday budget. What must be paid (fixed lines) is reserved first; whatever else leaves
 * the accounts comes out of the free money. Transfers between own accounts never count.
 */
final class Budget {
    static final int LATE = 0, SOON = 1, PARTIAL = 2, UPCOMING = 3, PAID = 4, SKIPPED = 5, MISSED = 6;

    // ------------------------------------------------------------ settings

    static final class Rule {
        String id, name, symbol;
        final List<String> keywords = new ArrayList<>();
        long expected, min = -1, max = -1;
        int day = 1;
        boolean invest, learn = true, enabled = true;

        Rule(String id, String name, String symbol, long expected, int day, String... keywords) {
            this.id = id; this.name = name; this.symbol = symbol; this.expected = expected; this.day = day;
            Collections.addAll(this.keywords, keywords);
        }

        Rule range(long min, long max) { this.min = min; this.max = max; return this; }
        Rule investing() { invest = true; return this; }

        boolean matches(Ledger.Txn txn, Ledger.Kind kind) {
            if (!enabled) return false;
            if (invest) return kind == Ledger.Kind.INVEST;
            if (kind != Ledger.Kind.SPEND && kind != Ledger.Kind.REFUND) return false;
            long abs = Math.abs(txn.cents);
            if ((min >= 0 && abs < min) || (max >= 0 && abs > max)) return false;
            String hay = " " + txn.haystack() + " ";
            for (String keyword : keywords) {
                String key = Ledger.key(keyword);
                if (!key.isEmpty() && hay.contains(" " + key)) return true;
            }
            return false;
        }

        JSONObject toJson() throws Exception {
            JSONArray words = new JSONArray();
            for (String keyword : keywords) words.put(keyword);
            return new JSONObject().put("id", id).put("name", name).put("symbol", symbol).put("keywords", words)
                    .put("expected", expected).put("day", day).put("min", min).put("max", max)
                    .put("invest", invest).put("learn", learn).put("enabled", enabled);
        }

        static Rule fromJson(JSONObject json) throws Exception {
            Rule rule = new Rule(json.getString("id"), json.getString("name"), json.optString("symbol", "spark"),
                    json.optLong("expected"), json.optInt("day", 1));
            JSONArray words = json.optJSONArray("keywords");
            if (words != null) for (int i = 0; i < words.length(); i++) rule.keywords.add(words.getString(i));
            rule.min = json.optLong("min", -1); rule.max = json.optLong("max", -1);
            rule.invest = json.optBoolean("invest"); rule.learn = json.optBoolean("learn", true);
            rule.enabled = json.optBoolean("enabled", true);
            return rule;
        }
    }

    /**
     * Defaults learned from four months of the owner's statements (June–October 2026). Every one can be
     * edited or removed in the app; amounts and days keep adapting to what is actually charged.
     */
    static List<Rule> defaultRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("alquiler", "Alquiler y casa", "home", 25_000, 5, "ALQUILER"));
        rules.add(new Rule("pareja", "Cuenta de pareja", "heart", 50_000, 1, "CUENTA PAREJA"));
        rules.add(new Rule("prestamo", "Préstamo", "doc", 11_824, 1, "CARGO PRESTAMO"));
        rules.add(new Rule("tarjeta", "Cuotas de la tarjeta", "card", 8_896, 1, "AMORTIZACION DEUDA"));
        rules.add(new Rule("inversion", "Plan de inversión", "seed", 7_200, 16).investing());
        rules.add(new Rule("gimnasio", "Gimnasio", "dumbbell", 2_990, 5, "EVOFIT", "GIMNASIO"));
        rules.add(new Rule("movil", "Móvil", "phone", 1_720, 8, "SIMYO"));
        rules.add(new Rule("netflix", "Netflix", "play", 1_499, 10, "NETFLIX"));
        rules.add(new Rule("seguro", "Seguro de la tarjeta", "shield", 980, 19, "SEGURO PROT"));
        rules.add(new Rule("google", "Google Play", "spark", 649, 24, "GOOGLE").range(550, 750));
        rules.add(new Rule("obsidian", "Obsidian", "note", 630, 1, "OBSIDIAN"));
        rules.add(new Rule("prime", "Amazon Prime", "box", 499, 25, "AMAZON PRIME"));
        return rules;
    }

    /**
     * What the owner taught the app. Overrides are codes: {@code cat:<id>} (free spending of a category),
     * {@code rule:<id>} (a fixed line), {@code transfer} (doesn't count), {@code income} and {@code refund}.
     */
    static final class Settings {
        final List<Rule> rules = new ArrayList<>();
        final Map<String, String> txn = new HashMap<>(), merchant = new HashMap<>();
        final Set<String> skipped = new HashSet<>(), dismissed = new HashSet<>();

        Rule rule(String id) {
            for (Rule rule : rules) if (rule.id.equals(id)) return rule;
            return null;
        }

        JSONObject toJson() throws Exception {
            JSONArray list = new JSONArray();
            for (Rule rule : rules) list.put(rule.toJson());
            return new JSONObject().put("version", 1).put("rules", list).put("txn", new JSONObject(txn))
                    .put("merchant", new JSONObject(merchant)).put("skipped", new JSONArray(skipped)).put("dismissed", new JSONArray(dismissed));
        }

        static Settings fromJson(JSONObject json) throws Exception {
            Settings settings = new Settings();
            JSONArray rules = json.optJSONArray("rules");
            if (rules != null) for (int i = 0; i < rules.length(); i++) settings.rules.add(Rule.fromJson(rules.getJSONObject(i)));
            copy(json.optJSONObject("txn"), settings.txn);
            copy(json.optJSONObject("merchant"), settings.merchant);
            copy(json.optJSONArray("skipped"), settings.skipped);
            copy(json.optJSONArray("dismissed"), settings.dismissed);
            return settings;
        }

        static Settings defaults() {
            Settings settings = new Settings();
            settings.rules.addAll(defaultRules());
            return settings;
        }

        private static void copy(JSONObject from, Map<String, String> to) throws Exception {
            if (from == null) return;
            JSONArray names = from.names();
            if (names != null) for (int i = 0; i < names.length(); i++) to.put(names.getString(i), from.getString(names.getString(i)));
        }

        private static void copy(JSONArray from, Set<String> to) throws Exception {
            if (from != null) for (int i = 0; i < from.length(); i++) to.add(from.getString(i));
        }
    }

    // ------------------------------------------------------------ resolution

    /** A movement as the budget sees it after rules and the owner's corrections. */
    static final class Entry {
        final Ledger.Txn txn;
        Ledger.Kind kind;
        String category;
        Rule rule;
        /** Where the decision came from: "" (automatic), "txn" (this movement) or "merchant" (remembered). */
        String taught = "";

        Entry(Ledger.Txn txn) { this.txn = txn; kind = txn.kind; category = txn.category; }

        boolean fixed() { return rule != null; }
        boolean free() { return rule == null && (kind == Ledger.Kind.SPEND || kind == Ledger.Kind.REFUND); }
        boolean counts() { return fixed() || free() || kind == Ledger.Kind.INCOME; }
    }

    static List<Entry> resolve(List<Ledger.Txn> txns, Settings settings) {
        List<Entry> entries = new ArrayList<>();
        for (Ledger.Txn txn : txns) {
            Entry entry = new Entry(txn);
            String code = settings.txn.get(txn.id);
            if (code != null) entry.taught = "txn";
            else if (!txn.duplicate && txn.kind != Ledger.Kind.INCOME) {
                code = settings.merchant.get(txn.merchantKey);
                if (code != null) entry.taught = "merchant";
            }
            if (code == null || !apply(entry, code, settings)) {
                entry.taught = "";
                for (Rule rule : settings.rules) if (rule.matches(txn, entry.kind)) { entry.rule = rule; break; }
            }
            entries.add(entry);
        }
        return entries;
    }

    private static boolean apply(Entry entry, String code, Settings settings) {
        long cents = entry.txn.cents;
        if (code.startsWith("cat:")) {
            entry.kind = cents < 0 ? Ledger.Kind.SPEND : Ledger.Kind.REFUND;
            entry.category = code.substring(4);
            return true;
        }
        if (code.startsWith("rule:")) {
            Rule rule = settings.rule(code.substring(5));
            if (rule == null || !rule.enabled) return false;
            entry.rule = rule;
            if (!rule.invest) entry.kind = cents < 0 ? Ledger.Kind.SPEND : Ledger.Kind.REFUND;
            else if (entry.kind != Ledger.Kind.INVEST) entry.kind = cents < 0 ? Ledger.Kind.INVEST : Ledger.Kind.REFUND;
            return true;
        }
        switch (code) {
            case "transfer": entry.kind = Ledger.Kind.TRANSFER; return true;
            case "income": entry.kind = Ledger.Kind.INCOME; return true;
            case "refund": entry.kind = Ledger.Kind.REFUND; return true;
            default: return false;
        }
    }

    // ------------------------------------------------------------ cycles

    static final class Period {
        final int start, end;
        final boolean partial, projected;
        Period(int start, int end, boolean partial, boolean projected) {
            this.start = start; this.end = end; this.partial = partial; this.projected = projected;
        }
        int days() { return end - start; }
        boolean contains(int day) { return day >= start && day < end; }
    }

    /**
     * Each cycle starts the day the payroll arrives and ends the day before the next one. The open cycle
     * ends at the predicted next payday. Without payroll in the data, calendar months are used instead.
     */
    static List<Period> periods(List<Entry> entries, int today) {
        List<Integer> paydays = new ArrayList<>();
        int earliest = today;
        for (Entry entry : entries) {
            earliest = Math.min(earliest, entry.txn.day);
            if (entry.kind == Ledger.Kind.INCOME && entry.txn.salary) paydays.add(entry.txn.day);
        }
        Collections.sort(paydays);
        List<Integer> starts = new ArrayList<>();
        for (int day : paydays) if (starts.isEmpty() || day - starts.get(starts.size() - 1) > 10) starts.add(day);
        List<Period> result = new ArrayList<>();
        if (starts.isEmpty()) {
            int[] c = Ledger.civil(earliest);
            int month = Ledger.epochDay(c[0], c[1], 1);
            while (month <= today) {
                int[] m = Ledger.civil(month);
                int next = Ledger.epochDay(m[1] == 12 ? m[0] + 1 : m[0], m[1] == 12 ? 1 : m[1] + 1, 1);
                result.add(new Period(month, next, false, next > today));
                month = next;
            }
            return result;
        }
        if (earliest < starts.get(0)) result.add(new Period(Math.max(earliest, starts.get(0) - 31), starts.get(0), true, false));
        for (int i = 0; i < starts.size(); i++) {
            boolean last = i == starts.size() - 1;
            int end = last ? Math.max(nextPayday(starts.get(i)), today + 1) : starts.get(i + 1);
            result.add(new Period(starts.get(i), end, false, last));
        }
        return result;
    }

    /** Month-end payers are paid on the last working day; others on the same date, moved back from weekends. */
    static int nextPayday(int payday) {
        int[] c = Ledger.civil(payday);
        int year = c[1] == 12 ? c[0] + 1 : c[0], month = c[1] == 12 ? 1 : c[1] + 1;
        int length = Ledger.monthLength(year, month);
        int day = Ledger.epochDay(year, month, c[2] >= 25 ? length : Math.min(c[2], length));
        while (Ledger.weekday(day) >= 5) day--;
        return day;
    }

    // ------------------------------------------------------------ the month at a glance

    static final class Line {
        final Rule rule;
        final List<Entry> entries = new ArrayList<>();
        long paid, expected;
        int status, due, paidDay = -1;
        /** Paid amount in earlier full cycles, oldest first. */
        long[] history = new long[0];

        Line(Rule rule) { this.rule = rule; }

        long committed() {
            switch (status) {
                case PAID: return paid;
                case PARTIAL: return Math.max(paid, expected);
                case SKIPPED: case MISSED: return paid;
                default: return expected;
            }
        }
        boolean done() { return status == PAID || status == SKIPPED || status == MISSED; }
    }

    static final class Slice {
        final Ledger.Category category;
        final List<Entry> entries = new ArrayList<>();
        long cents;
        Slice(Ledger.Category category) { this.category = category; }
    }

    static final class Habit {
        final String merchant;
        final int count;
        final long cents;
        Habit(String merchant, int count, long cents) { this.merchant = merchant; this.count = count; this.cents = cents; }
    }

    static final class Cycle {
        Period period;
        int index, count, today;
        boolean current, salaryEstimated;
        long salary, otherIncome, fixedPaid, fixedPending, freeSpent, invested, previousAtSameDay = -1, averageDaily = -1;
        final List<Line> lines = new ArrayList<>();
        final List<Slice> slices = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        long[] daily;
        Habit habit;

        long income() { return salary + otherIncome; }
        long fixed() { return fixedPaid + fixedPending; }
        /** Theoretical free money: what remains of the income once every fixed line is reserved. */
        long freeBudget() { return income() - fixed(); }
        long available() { return freeBudget() - freeSpent; }
        int elapsed() { return current ? Math.max(1, Math.min(period.days(), today - period.start + 1)) : period.days(); }
        int left() { return Math.max(0, period.days() - elapsed()); }
        /** Free spending that would be on pace by today. */
        long paceTarget() { return Math.max(0, freeBudget()) * elapsed() / Math.max(1, period.days()); }
        long dailyAllowance() { return left() == 0 ? 0 : Math.max(0, available()) / left(); }
        /** Free money left at the end of the cycle at the current spending rate. */
        long projection() {
            long rate = elapsed() >= 4 || averageDaily < 0 ? freeSpent / Math.max(1, elapsed()) : averageDaily;
            return available() - rate * left();
        }
        int paidLines() {
            int count = 0;
            for (Line line : lines) if (line.status == PAID) count++;
            return count;
        }
        int activeLines() {
            int count = 0;
            for (Line line : lines) if (line.status != SKIPPED) count++;
            return count;
        }
    }

    static final class Snapshot {
        final List<Entry> entries;
        final List<Period> periods;
        final Settings settings;
        final int today;
        Snapshot(List<Entry> entries, List<Period> periods, Settings settings, int today) {
            this.entries = entries; this.periods = periods; this.settings = settings; this.today = today;
        }
        /** Navigable cycles: the leading partial one only exists to learn from. */
        int first() { return !periods.isEmpty() && periods.get(0).partial ? 1 : 0; }
        int last() { return periods.size() - 1; }
    }

    static Snapshot snapshot(List<Ledger.Txn> txns, Settings settings, int today) {
        List<Entry> entries = resolve(txns, settings);
        return new Snapshot(entries, periods(entries, today), settings, today);
    }

    static Cycle cycle(Snapshot snapshot, int index) {
        Cycle cycle = new Cycle();
        Period period = snapshot.periods.get(index);
        cycle.period = period; cycle.index = index; cycle.count = snapshot.periods.size();
        cycle.today = snapshot.today;
        cycle.current = period.contains(snapshot.today) && index == snapshot.last();
        cycle.daily = new long[period.days()];
        for (Entry entry : snapshot.entries) if (period.contains(entry.txn.day)) cycle.entries.add(entry);

        // Income.
        for (Entry entry : cycle.entries) {
            if (entry.kind != Ledger.Kind.INCOME) continue;
            if (entry.txn.salary) cycle.salary += entry.txn.cents; else cycle.otherIncome += entry.txn.cents;
        }
        if (cycle.salary == 0 && cycle.current) {
            List<Long> salaries = new ArrayList<>();
            for (Entry entry : snapshot.entries) if (entry.kind == Ledger.Kind.INCOME && entry.txn.salary) salaries.add(entry.txn.cents);
            if (!salaries.isEmpty()) { cycle.salary = median(salaries); cycle.salaryEstimated = true; }
        }

        // Fixed lines.
        for (Rule rule : snapshot.settings.rules) {
            if (!rule.enabled) continue;
            Line line = new Line(rule);
            for (Entry entry : cycle.entries) {
                if (entry.rule != rule) continue;
                line.entries.add(entry);
                line.paid -= entry.txn.cents;
                if (entry.txn.cents < 0 && (line.paidDay < 0 || entry.txn.day < line.paidDay)) line.paidDay = entry.txn.day;
            }
            learn(snapshot, index, line);
            boolean skipped = snapshot.settings.skipped.contains(skipKey(rule, period));
            if (line.paid > 0) line.status = rule.invest && cycle.current && line.paid * 100 < line.expected * 85 ? PARTIAL : PAID;
            else if (skipped) line.status = SKIPPED;
            else if (!cycle.current) line.status = period.end <= snapshot.today ? MISSED : UPCOMING;
            else if (snapshot.today > line.due + 3) line.status = LATE;
            else if (line.due - snapshot.today <= 3) line.status = SOON;
            else line.status = UPCOMING;
            // A line never matched in this cycle nor before is not shown as missed in old cycles.
            if (line.status == MISSED && line.history.length == 0) continue;
            cycle.lines.add(line);
            cycle.fixedPaid += Math.max(0, line.paid);
            cycle.fixedPending += Math.max(0, line.committed() - Math.max(0, line.paid));
        }
        Collections.sort(cycle.lines, (a, b) -> a.status != b.status ? Integer.compare(a.status, b.status)
                : a.done() ? Integer.compare(a.paidDay, b.paidDay) : Integer.compare(a.due, b.due));

        // Free money.
        Map<String, Slice> slices = new LinkedHashMap<>();
        Map<String, long[]> merchants = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Entry entry : cycle.entries) {
            if (entry.kind == Ledger.Kind.INVEST && entry.rule == null) cycle.invested -= entry.txn.cents;
            if (!entry.free()) continue;
            long spend = -entry.txn.cents;
            cycle.freeSpent += spend;
            cycle.daily[entry.txn.day - period.start] += spend;
            Slice slice = slices.get(entry.category);
            if (slice == null) { slice = new Slice(Ledger.category(entry.category)); slices.put(entry.category, slice); }
            slice.cents += spend; slice.entries.add(entry);
            if (spend > 0) {
                long[] stat = merchants.get(entry.txn.merchantKey);
                if (stat == null) { stat = new long[2]; merchants.put(entry.txn.merchantKey, stat); names.put(entry.txn.merchantKey, entry.txn.merchant); }
                stat[0]++; stat[1] += spend;
            }
        }
        cycle.slices.addAll(slices.values());
        Collections.sort(cycle.slices, (a, b) -> Long.compare(b.cents, a.cents));
        String best = null;
        for (Map.Entry<String, long[]> stat : merchants.entrySet()) {
            long[] value = stat.getValue();
            if (value[0] < 3) continue;
            if (best == null || value[0] > merchants.get(best)[0] || (value[0] == merchants.get(best)[0] && value[1] > merchants.get(best)[1]))
                best = stat.getKey();
        }
        if (best != null) cycle.habit = new Habit(names.get(best), (int) merchants.get(best)[0], merchants.get(best)[1]);

        // Comparison with the previous full cycle at the same point, and its daily rhythm.
        if (index - 1 >= snapshot.first() && index - 1 >= 0) {
            Period previous = snapshot.periods.get(index - 1);
            if (!previous.partial) {
                long sameDay = 0, total = 0;
                for (Entry entry : snapshot.entries) {
                    if (!entry.free() || !previous.contains(entry.txn.day)) continue;
                    total -= entry.txn.cents;
                    if (entry.txn.day - previous.start < cycle.elapsed()) sameDay -= entry.txn.cents;
                }
                cycle.previousAtSameDay = sameDay;
                cycle.averageDaily = total / Math.max(1, previous.days());
            }
        }
        return cycle;
    }

    /** Expected amount and day come from earlier full cycles; configured values fill the gaps. */
    private static void learn(Snapshot snapshot, int index, Line line) {
        Period period = snapshot.periods.get(index);
        List<Long> amounts = new ArrayList<>(), offsets = new ArrayList<>();
        List<Long> history = new ArrayList<>();
        for (int i = Math.max(0, index - 6); i < index; i++) {
            Period past = snapshot.periods.get(i);
            if (past.partial) continue;
            long paid = 0; int first = -1;
            for (Entry entry : snapshot.entries) {
                if (entry.rule != line.rule || !past.contains(entry.txn.day)) continue;
                paid -= entry.txn.cents;
                if (entry.txn.cents < 0 && (first < 0 || entry.txn.day < first)) first = entry.txn.day;
            }
            history.add(paid);
            if (paid > 0) { amounts.add(paid); offsets.add((long) (first - past.start)); }
        }
        line.history = new long[history.size()];
        for (int i = 0; i < history.size(); i++) line.history[i] = history.get(i);
        List<Long> recent = amounts.subList(Math.max(0, amounts.size() - 3), amounts.size());
        line.expected = line.rule.learn && !recent.isEmpty() ? median(recent) : line.rule.expected;
        if (!offsets.isEmpty()) line.due = period.start + (int) (long) median(offsets.subList(Math.max(0, offsets.size() - 4), offsets.size()));
        else line.due = dayOfMonthIn(period, line.rule.day);
        line.due = Math.max(period.start, Math.min(period.end - 1, line.due));
    }

    static int dayOfMonthIn(Period period, int dayOfMonth) {
        for (int day = period.start; day < period.end; day++) {
            int[] c = Ledger.civil(day);
            if (c[2] == Math.min(dayOfMonth, Ledger.monthLength(c[0], c[1]))) return day;
        }
        return period.start;
    }

    static String skipKey(Rule rule, Period period) { return rule.id + "@" + Ledger.iso(period.start); }

    // ------------------------------------------------------------ suggestions

    static final class Suggestion {
        final String key, name, category;
        final long amount;
        final int dayOfMonth, cycles;
        Suggestion(String key, String name, String category, long amount, int dayOfMonth, int cycles) {
            this.key = key; this.name = name; this.category = category; this.amount = amount; this.dayOfMonth = dayOfMonth; this.cycles = cycles;
        }
        Rule toRule() {
            Rule rule = new Rule("auto-" + Integer.toHexString(key.hashCode()), name, Ledger.category(category).symbol, amount, dayOfMonth, key);
            rule.range(amount * 85 / 100, amount * 115 / 100);
            return rule;
        }
    }

    /**
     * Free spending that behaves like a bill: the same merchant, about the same amount, once per cycle,
     * in at least three cycles (or two at the same amount and date) and seen recently.
     */
    static List<Suggestion> suggestions(Snapshot snapshot) {
        Map<String, List<Entry>> byMerchant = new LinkedHashMap<>();
        for (Entry entry : snapshot.entries) {
            if (!entry.free() || entry.kind != Ledger.Kind.SPEND || !entry.taught.isEmpty()) continue;
            if (-entry.txn.cents < 100 || snapshot.settings.dismissed.contains(entry.txn.merchantKey)) continue;
            List<Entry> list = byMerchant.get(entry.txn.merchantKey);
            if (list == null) { list = new ArrayList<>(); byMerchant.put(entry.txn.merchantKey, list); }
            list.add(entry);
        }
        List<Suggestion> result = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> group : byMerchant.entrySet()) {
            // Places visited often (the café, the supermarket) are habits, not bills.
            if (EVERYDAY.contains(group.getValue().get(0).category)) continue;
            Suggestion best = null;
            for (Entry seed : group.getValue()) {
                long amount = -seed.txn.cents, tolerance = Math.max(50, amount * 8 / 100);
                List<Entry> members = new ArrayList<>();
                Set<Integer> cycles = new HashSet<>();
                int latest = Integer.MIN_VALUE;
                for (Entry other : group.getValue()) {
                    if (Math.abs(-other.txn.cents - amount) > tolerance) continue;
                    members.add(other); latest = Math.max(latest, other.txn.day);
                    cycles.add(periodOf(snapshot, other.txn.day));
                }
                if (members.size() * 2 > cycles.size() * 3 || snapshot.today - latest > 62) continue;
                List<Long> amounts = new ArrayList<>(), days = new ArrayList<>();
                for (Entry member : members) { amounts.add(-member.txn.cents); days.add((long) Ledger.civil(member.txn.day)[2]); }
                long typical = median(amounts);
                boolean steady = true;
                for (Entry member : members) {
                    if (Math.abs(-member.txn.cents - typical) * 50 > typical
                            || Math.abs(Ledger.civil(member.txn.day)[2] - median(days)) > 3) steady = false;
                }
                if (cycles.size() < 3 && !(cycles.size() == 2 && steady)) continue;
                if (best == null || cycles.size() > best.cycles)
                    best = new Suggestion(group.getKey(), seed.txn.merchant, seed.category, typical, (int) (long) median(days), cycles.size());
            }
            if (best != null) result.add(best);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.amount, a.amount));
        return result;
    }

    private static final Set<String> EVERYDAY = new HashSet<>(java.util.Arrays.asList("cafe", "tabaco", "super", "comer", "personas", "efectivo"));

    private static int periodOf(Snapshot snapshot, int day) {
        for (int i = 0; i < snapshot.periods.size(); i++) if (snapshot.periods.get(i).contains(day)) return i;
        return -1;
    }

    // ------------------------------------------------------------ formatting

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n == 0 ? 0 : n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** Spanish money: "1.408 €", "6,80 €", "−83 €". Cents are shown only where they matter. */
    static String money(long cents) {
        long abs = Math.abs(cents);
        return money(cents, abs % 100 != 0 && abs < 100_000);
    }

    static String money(long cents, boolean decimals) {
        long abs = Math.abs(cents);
        long units = decimals ? abs / 100 : (abs + 50) / 100;
        StringBuilder digits = new StringBuilder(Long.toString(units));
        for (int i = digits.length() - 3; i > 0; i -= 3) digits.insert(i, '.');
        if (decimals) digits.append(',').append(abs % 100 < 10 ? "0" : "").append(abs % 100);
        return (cents < 0 && (decimals ? abs : (abs + 50) / 100 * 100) > 0 ? "−" : "") + digits + " €";
    }

    static final String[] MONTHS = {"ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic"};
    static final String[] WEEKDAYS = {"lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"};

    static String date(int day) {
        int[] c = Ledger.civil(day);
        return c[2] + " " + MONTHS[c[1] - 1];
    }

    private Budget() { }
}
