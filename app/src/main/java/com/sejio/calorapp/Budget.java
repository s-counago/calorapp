package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Payday-to-payday budget with four buckets. Fixed (obligatory and recurring) and savings are reserved
 * first; unexpected obligations come out of a cushion while it lasts; everything else is free money.
 * Transfers between own accounts never count.
 */
final class Budget {
    static final int LATE = 0, SOON = 1, PARTIAL = 2, UPCOMING = 3, RESERVED = 4, PAID = 5, SKIPPED = 6, MISSED = 7;
    static final int VERSION = 2;

    // ------------------------------------------------------------ settings

    static final class Rule {
        String id, name, symbol;
        final List<String> keywords = new ArrayList<>();
        long expected, min = -1, max = -1;
        /** Day of the month and months between charges (1 monthly, 3 quarterly, 12 yearly). */
        int day = 1, frequency = 1;
        boolean invest, learn = true, enabled = true;

        Rule(String id, String name, String symbol, long expected, int day, String... keywords) {
            this.id = id; this.name = name; this.symbol = symbol; this.expected = expected; this.day = day;
            Collections.addAll(this.keywords, keywords);
        }

        Rule range(long min, long max) { this.min = min; this.max = max; return this; }
        Rule every(int months) { frequency = Math.max(1, months); return this; }

        boolean matches(Entry entry) {
            if (!enabled) return false;
            if (invest) return entry.kind == Ledger.Kind.INVEST;
            if (entry.kind != Ledger.Kind.SPEND && entry.kind != Ledger.Kind.REFUND) return false;
            long abs = Math.abs(entry.txn.cents);
            if ((min >= 0 && abs < min) || (max >= 0 && abs > max)) return false;
            String hay = " " + entry.txn.haystack() + " " + entry.merchantKey + " ";
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
                    .put("expected", expected).put("day", day).put("frequency", frequency).put("min", min).put("max", max)
                    .put("invest", invest).put("learn", learn).put("enabled", enabled);
        }

        static Rule fromJson(JSONObject json) throws Exception {
            Rule rule = new Rule(json.getString("id"), json.getString("name"), json.optString("symbol", "spark"),
                    json.optLong("expected"), json.optInt("day", 1));
            JSONArray words = json.optJSONArray("keywords");
            if (words != null) for (int i = 0; i < words.length(); i++) rule.keywords.add(words.getString(i));
            rule.min = json.optLong("min", -1); rule.max = json.optLong("max", -1);
            rule.frequency = Math.max(1, json.optInt("frequency", 1));
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
        rules.add(new Rule("gimnasio", "Gimnasio", "dumbbell", 2_990, 5, "EVOFIT", "GIMNASIO"));
        rules.add(new Rule("movil", "Móvil", "phone", 1_720, 8, "SIMYO"));
        rules.add(new Rule("netflix", "Netflix", "play", 1_499, 10, "NETFLIX"));
        rules.add(new Rule("seguro", "Seguro de la tarjeta", "shield", 980, 19, "SEGURO PROT"));
        rules.add(spotify());
        rules.add(new Rule("obsidian", "Obsidian", "note", 630, 1, "OBSIDIAN"));
        rules.add(new Rule("prime", "Amazon Prime", "box", 499, 25, "AMAZON PRIME"));
        rules.add(cig());
        return rules;
    }

    /** Charged through Google Play, hence the amount range: other Google charges stay free spending. */
    private static Rule spotify() { return new Rule("google", "Spotify", "music", 649, 24, "GOOGLE").range(550, 750); }
    private static Rule cig() { return new Rule("cig", "Cuota CIG", "people", 2_019, 5, "INTERSINDICAL", "CUOTA CIG").every(3); }

    /**
     * What the owner taught the app. Overrides are codes: {@code cat:<id>} (free spending of a category),
     * {@code rule:<id>} (a fixed line), {@code unexpected}, {@code transfer} (doesn't count), {@code income}
     * and {@code refund}. Merchants have a canonical key (aliases) and an optional display name.
     */
    static final class Settings {
        final List<Rule> rules = new ArrayList<>();
        final Map<String, String> txn = new HashMap<>(), merchant = new HashMap<>(), alias = new HashMap<>();
        final Map<String, String> names = new HashMap<>(), notes = new HashMap<>();
        /** Amount the owner expects for one fixed line in one cycle, keyed like {@link #skipKey}. */
        final Map<String, Long> planned = new HashMap<>();
        final Set<String> skipped = new HashSet<>(), dismissed = new HashSet<>(), reviewed = new HashSet<>(), separate = new HashSet<>();
        /** Savings goal per cycle and the cushion for unexpected costs, set on a day. Cents; day -1 = not set. */
        long goal, cushion, cushionGoal;
        int cushionDay = -1;
        /** Money the owner put into the cushion: {day, cents}. It counts as savings in its cycle. */
        final List<long[]> cushionMoves = new ArrayList<>();

        /** The day the cushion's history starts: when its balance was set, or its first contribution. */
        int cushionStart() {
            if (cushionDay >= 0) return cushionDay;
            int first = -1;
            for (long[] move : cushionMoves) if (first < 0 || move[0] < first) first = (int) move[0];
            return first;
        }

        Rule rule(String id) {
            for (Rule rule : rules) if (rule.id.equals(id)) return rule;
            return null;
        }

        String canonical(String key) {
            String target = alias.get(key);
            return target == null ? key : target;
        }

        /** Every variant of {@code other} now reads as {@code into}; its name and memory follow. */
        void merge(String into, String other) {
            into = canonical(into); other = canonical(other);
            if (into.equals(other)) return;
            for (Map.Entry<String, String> entry : new ArrayList<>(alias.entrySet()))
                if (entry.getValue().equals(other)) alias.put(entry.getKey(), into);
            alias.put(other, into);
            names.remove(other);
            String memory = merchant.remove(other);
            if (memory != null && !merchant.containsKey(into)) merchant.put(into, memory);
        }

        void split(String key) { alias.remove(key); }

        JSONObject toJson() throws Exception {
            JSONArray list = new JSONArray();
            for (Rule rule : rules) list.put(rule.toJson());
            return new JSONObject().put("version", VERSION).put("rules", list).put("txn", new JSONObject(txn))
                    .put("merchant", new JSONObject(merchant)).put("alias", new JSONObject(alias)).put("names", new JSONObject(names))
                    .put("notes", new JSONObject(notes)).put("skipped", new JSONArray(skipped)).put("dismissed", new JSONArray(dismissed))
                    .put("reviewed", new JSONArray(reviewed)).put("separate", new JSONArray(separate)).put("planned", new JSONObject(planned))
                    .put("goal", goal).put("cushion", cushion).put("cushionDay", cushionDay).put("cushionGoal", cushionGoal)
                    .put("cushionMoves", moves());
        }

        private JSONArray moves() throws Exception {
            JSONArray list = new JSONArray();
            for (long[] move : cushionMoves) list.put(new JSONArray().put(move[0]).put(move[1]));
            return list;
        }

        static Settings fromJson(JSONObject json) throws Exception {
            Settings settings = new Settings();
            JSONArray rules = json.optJSONArray("rules");
            if (rules != null) for (int i = 0; i < rules.length(); i++) settings.rules.add(Rule.fromJson(rules.getJSONObject(i)));
            copy(json.optJSONObject("txn"), settings.txn);
            copy(json.optJSONObject("merchant"), settings.merchant);
            copy(json.optJSONObject("alias"), settings.alias);
            copy(json.optJSONObject("names"), settings.names);
            copy(json.optJSONObject("notes"), settings.notes);
            copy(json.optJSONArray("skipped"), settings.skipped);
            copy(json.optJSONArray("dismissed"), settings.dismissed);
            copy(json.optJSONArray("reviewed"), settings.reviewed);
            copy(json.optJSONArray("separate"), settings.separate);
            JSONObject planned = json.optJSONObject("planned");
            if (planned != null && planned.names() != null)
                for (int i = 0; i < planned.names().length(); i++) settings.planned.put(planned.names().getString(i), planned.getLong(planned.names().getString(i)));
            settings.goal = json.optLong("goal"); settings.cushion = json.optLong("cushion");
            settings.cushionDay = json.optInt("cushionDay", -1);
            settings.cushionGoal = json.optLong("cushionGoal");
            JSONArray moves = json.optJSONArray("cushionMoves");
            if (moves != null) for (int i = 0; i < moves.length(); i++)
                settings.cushionMoves.add(new long[]{moves.getJSONArray(i).getLong(0), moves.getJSONArray(i).getLong(1)});
            if (json.optInt("version", 1) < 2) settings.upgradeToTwo();
            return settings;
        }

        /** Version 2: Google Play 6,49 € is Spotify, investing is savings rather than fixed, and the CIG fee is quarterly. */
        private void upgradeToTwo() {
            Rule google = rule("google");
            if (google != null && google.name.equals("Google Play")) { google.name = "Spotify"; google.symbol = "music"; }
            for (Rule rule : new ArrayList<>(rules)) if (rule.invest && rule.id.equals("inversion")) rules.remove(rule);
            for (Map.Entry<String, String> entry : new ArrayList<>(txn.entrySet())) if (entry.getValue().equals("rule:inversion")) txn.remove(entry.getKey());
            if (rule("cig") == null) rules.add(cig());
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
        String category, merchant, merchantKey, note;
        Rule rule;
        boolean unexpected;
        /** Where the decision came from: "" (automatic), "txn" (this movement) or "merchant" (remembered). */
        String taught = "";

        Entry(Ledger.Txn txn) {
            this.txn = txn; kind = txn.kind; category = txn.category; merchant = txn.merchant; merchantKey = txn.merchantKey;
        }

        boolean fixed() { return rule != null; }
        boolean free() { return rule == null && !unexpected && (kind == Ledger.Kind.SPEND || kind == Ledger.Kind.REFUND); }
        boolean saving() { return rule == null && kind == Ledger.Kind.INVEST; }
        boolean counts() { return fixed() || free() || unexpected || saving() || kind == Ledger.Kind.INCOME; }
        String label() { return txn.salary ? "Nómina" : merchant; }
    }

    static List<Entry> resolve(List<Ledger.Txn> txns, Settings settings) {
        Map<String, String> readable = new HashMap<>();
        for (Ledger.Txn txn : txns) if (!readable.containsKey(txn.merchantKey)) readable.put(txn.merchantKey, txn.merchant);
        List<Entry> entries = new ArrayList<>();
        for (Ledger.Txn txn : txns) {
            Entry entry = new Entry(txn);
            entry.merchantKey = settings.canonical(txn.merchantKey);
            String name = settings.names.get(entry.merchantKey);
            entry.merchant = name != null ? name : readable.containsKey(entry.merchantKey) ? readable.get(entry.merchantKey) : txn.merchant;
            entry.note = settings.notes.get(txn.id);
            String code = settings.txn.get(txn.id);
            if (code != null) entry.taught = "txn";
            else if (!txn.duplicate && txn.kind != Ledger.Kind.INCOME && !txn.reward) {
                code = settings.merchant.get(entry.merchantKey);
                if (code == null) code = settings.merchant.get(txn.merchantKey);
                if (code != null) entry.taught = "merchant";
            }
            if (code == null || !apply(entry, code, settings)) {
                entry.taught = "";
                for (Rule rule : settings.rules) if (rule.matches(entry)) { entry.rule = rule; break; }
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
            case "unexpected": entry.kind = cents < 0 ? Ledger.Kind.SPEND : Ledger.Kind.REFUND; entry.unexpected = true; return true;
            case "saving": entry.kind = Ledger.Kind.INVEST; return true;
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
                int next = addMonths(month, 1);
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

    /** Same day of the month {@code months} later, clamped to the month's length. */
    static int addMonths(int day, int months) {
        int[] c = Ledger.civil(day);
        int total = c[0] * 12 + (c[1] - 1) + months, year = total / 12, month = total % 12 + 1;
        return Ledger.epochDay(year, month, Math.min(c[2], Ledger.monthLength(year, month)));
    }

    // ------------------------------------------------------------ the month at a glance

    static final class Line {
        final Rule rule;
        final List<Entry> entries = new ArrayList<>();
        long paid, expected;
        int status, due, paidDay = -1, lastPaidDay = -1;
        /** expected was set by the owner for this cycle rather than learned. */
        boolean planned;
        /** Paid amount in earlier full cycles, oldest first. */
        long[] history = new long[0];

        Line(Rule rule) { this.rule = rule; }

        /** What this cycle sets aside: a share of a quarterly or yearly charge, or the monthly amount. */
        long reserve() { return rule.frequency > 1 ? expected / rule.frequency : expected; }

        long committed() {
            if (rule.frequency > 1) return status == SKIPPED ? 0 : reserve();
            switch (status) {
                case PAID: return paid;
                case PARTIAL: return Math.max(paid, expected);
                case SKIPPED: case MISSED: return paid;
                default: return expected;
            }
        }
        boolean done() { return status >= RESERVED; }
    }

    static final class Slice {
        final Ledger.Category category;
        final List<Entry> entries = new ArrayList<>();
        long cents;
        Slice(Ledger.Category category) { this.category = category; }
    }

    static final class Habit {
        final String merchant, key;
        final int count;
        final long cents;
        Habit(String merchant, String key, int count, long cents) { this.merchant = merchant; this.key = key; this.count = count; this.cents = cents; }
    }

    static final class Cycle {
        Period period;
        int index, count, today;
        boolean current, salaryEstimated;
        long salary, otherIncome, fixedPaid, fixedPending, freeSpent, previousAtSameDay = -1, averageDaily = -1;
        /** Savings: what the owner put aside (TR purchases minus saveback), the gift and the target for the cycle. */
        long saved, gift, savingsTarget, invested, toCushion;
        /** Unexpected obligations and how much of them the cushion absorbed; cushion balance -1 when not set. */
        long unexpected, covered, cushionStart = -1;
        final List<Line> lines = new ArrayList<>();
        final List<Slice> slices = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>(), unexpectedEntries = new ArrayList<>(), savingEntries = new ArrayList<>();
        long[] daily;
        Habit habit;

        long income() { return salary + otherIncome; }
        long fixed() { return fixedPaid + fixedPending; }
        /** The open cycle keeps the savings target apart; closed cycles count what was actually saved. */
        long savingsReserve() { return current ? Math.max(saved, savingsTarget) : saved; }
        /** Theoretical free money: what remains of the income once fixed lines and savings are reserved. */
        long freeBudget() { return income() - fixed() - savingsReserve(); }
        long uncovered() { return unexpected - covered; }
        long available() { return freeBudget() - freeSpent - uncovered(); }
        long cushionLeft() { return cushionStart < 0 ? -1 : cushionStart - covered; }
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
        /**
         * Savings when the cycle closes: what was put aside plus the free money left, or minus what was
         * overspent. For the open cycle, at the current spending rate.
         */
        long savingsAtClose() {
            return Math.max(0, current ? savingsReserve() + projection() : saved + available());
        }
        /** What the open cycle would miss of its savings target at the current rate. */
        long shortfall() { return Math.max(0, savingsTarget - savingsAtClose()); }
        int paidLines() {
            int count = 0;
            for (Line line : lines) if (line.status == PAID || line.status == RESERVED) count++;
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
        int periodOf(int day) {
            for (int i = 0; i < periods.size(); i++) if (periods.get(i).contains(day)) return i;
            return -1;
        }
    }

    static Snapshot snapshot(List<Ledger.Txn> txns, Settings settings, int today) {
        List<Entry> entries = resolve(txns, settings);
        return new Snapshot(entries, periods(entries, today), settings, today);
    }

    static Cycle cycle(Snapshot snapshot, int index) {
        Cycle cycle = new Cycle();
        Settings settings = snapshot.settings;
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
        for (Rule rule : settings.rules) {
            if (!rule.enabled) continue;
            Line line = new Line(rule);
            for (Entry entry : cycle.entries) {
                if (entry.rule != rule) continue;
                line.entries.add(entry);
                line.paid -= entry.txn.cents;
                if (entry.txn.cents < 0 && (line.paidDay < 0 || entry.txn.day < line.paidDay)) line.paidDay = entry.txn.day;
            }
            learn(snapshot, index, line);
            Long plan = settings.planned.get(skipKey(rule, period));
            if (plan != null) { line.expected = plan; line.planned = true; }
            boolean skipped = settings.skipped.contains(skipKey(rule, period));
            // A quarterly or yearly charge is only expected in the cycle its next date falls in; elsewhere it is being set aside.
            boolean dueHere = rule.frequency == 1 || (line.lastPaidDay >= 0 && line.due < period.end);
            // Short of a planned amount, an open cycle keeps the rest reserved: "Llevas 500 de 550".
            boolean shortOfPlan = line.planned && line.paid * 100 < line.expected * 98;
            if (line.paid > 0) line.status = cycle.current && (shortOfPlan || rule.invest && line.paid * 100 < line.expected * 85) ? PARTIAL : PAID;
            else if (skipped) line.status = SKIPPED;
            else if (!dueHere) line.status = RESERVED;
            else if (!cycle.current) line.status = period.end <= snapshot.today ? MISSED : UPCOMING;
            else if (snapshot.today > line.due + 3) line.status = LATE;
            else if (line.due - snapshot.today <= 3) line.status = SOON;
            else line.status = UPCOMING;
            // A line never matched in this cycle nor before is not shown as missed in old cycles.
            if (line.status == MISSED && line.history.length == 0) continue;
            cycle.lines.add(line);
            if (rule.frequency > 1) { cycle.fixedPaid += line.committed(); continue; }
            cycle.fixedPaid += Math.max(0, line.paid);
            cycle.fixedPending += Math.max(0, line.committed() - Math.max(0, line.paid));
        }
        Collections.sort(cycle.lines, (a, b) -> a.status != b.status ? Integer.compare(a.status, b.status)
                : a.status == PAID ? Integer.compare(a.paidDay, b.paidDay) : Integer.compare(a.due, b.due));

        // Savings and unexpected costs.
        for (Entry entry : cycle.entries) {
            if (entry.saving()) { cycle.saved -= entry.txn.cents; cycle.savingEntries.add(entry); }
            if (entry.txn.reward && entry.kind == Ledger.Kind.IGNORED) cycle.gift += entry.txn.cents;
            if (entry.unexpected) { cycle.unexpected -= entry.txn.cents; cycle.unexpectedEntries.add(entry); }
        }
        cycle.saved = Math.max(0, cycle.saved - cycle.gift);
        cycle.invested = cycle.saved;
        for (long[] move : settings.cushionMoves) if (period.contains((int) move[0])) cycle.toCushion += move[1];
        cycle.saved += cycle.toCushion;
        if (settings.goal > 0) cycle.savingsTarget = settings.goal;
        else {
            List<Long> past = new ArrayList<>();
            for (int i = Math.max(snapshot.first(), index - 3); i < index; i++) {
                long saved = 0, gift = 0;
                for (Entry entry : snapshot.entries) {
                    if (!snapshot.periods.get(i).contains(entry.txn.day)) continue;
                    if (entry.saving()) saved -= entry.txn.cents;
                    if (entry.txn.reward && entry.kind == Ledger.Kind.IGNORED) gift += entry.txn.cents;
                }
                if (saved - gift > 0) past.add(saved - gift);
            }
            cycle.savingsTarget = median(past);
        }
        int cushionFrom = settings.cushionStart();
        if (cushionFrom >= 0 && cushionFrom < period.end) {
            long balance = settings.cushionDay >= 0 ? settings.cushion : 0, here = 0;
            for (long[] move : settings.cushionMoves) if (move[0] >= cushionFrom && move[0] < period.end) balance += move[1];
            for (Entry entry : snapshot.entries) {
                if (!entry.unexpected || entry.txn.day < cushionFrom) continue;
                if (entry.txn.day < period.start) balance += entry.txn.cents;
                else if (period.contains(entry.txn.day)) here -= entry.txn.cents;
            }
            cycle.cushionStart = Math.max(0, balance);
            cycle.covered = Math.max(0, Math.min(here, cycle.cushionStart));
        }

        // Free money.
        Map<String, Slice> slices = new LinkedHashMap<>();
        Map<String, long[]> merchants = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Entry entry : cycle.entries) {
            if (!entry.free()) continue;
            long spend = -entry.txn.cents;
            cycle.freeSpent += spend;
            cycle.daily[entry.txn.day - period.start] += spend;
            Slice slice = slices.get(entry.category);
            if (slice == null) { slice = new Slice(Ledger.category(entry.category)); slices.put(entry.category, slice); }
            slice.cents += spend; slice.entries.add(entry);
            if (spend > 0) {
                long[] stat = merchants.get(entry.merchantKey);
                if (stat == null) { stat = new long[2]; merchants.put(entry.merchantKey, stat); names.put(entry.merchantKey, entry.merchant); }
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
        if (best != null) cycle.habit = new Habit(names.get(best), best, (int) merchants.get(best)[0], merchants.get(best)[1]);

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
        int window = line.rule.frequency > 1 ? 13 : 6;
        List<Long> amounts = new ArrayList<>(), offsets = new ArrayList<>();
        List<Long> history = new ArrayList<>();
        for (int i = Math.max(0, index - window); i < index; i++) {
            Period past = snapshot.periods.get(i);
            long paid = 0; int first = -1;
            for (Entry entry : snapshot.entries) {
                if (entry.rule != line.rule || !past.contains(entry.txn.day)) continue;
                paid -= entry.txn.cents;
                if (entry.txn.cents < 0 && (first < 0 || entry.txn.day < first)) first = entry.txn.day;
            }
            if (first >= 0) line.lastPaidDay = first;
            if (past.partial && line.rule.frequency == 1) continue;
            history.add(paid);
            if (paid > 0) { amounts.add(paid); if (!past.partial) offsets.add((long) (first - past.start)); }
        }
        line.history = new long[history.size()];
        for (int i = 0; i < history.size(); i++) line.history[i] = history.get(i);
        List<Long> recent = amounts.subList(Math.max(0, amounts.size() - 3), amounts.size());
        line.expected = line.rule.learn && !recent.isEmpty() ? median(recent) : line.rule.expected;
        if (line.rule.frequency > 1 && line.lastPaidDay >= 0) {
            int next = line.lastPaidDay;
            while (next < period.start) next = addMonths(next, line.rule.frequency);
            line.due = next;
            return;
        }
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

    /** Distinct amounts paid for a line in earlier cycles, most recent first: quick choices when planning. */
    static List<Long> usualAmounts(Line line) {
        List<Long> result = new ArrayList<>();
        for (int i = line.history.length - 1; i >= 0 && result.size() < 4; i--)
            if (line.history[i] > 0 && !result.contains(line.history[i])) result.add(line.history[i]);
        return result;
    }

    static String skipKey(Rule rule, Period period) { return rule.id + "@" + Ledger.iso(period.start); }

    /** Savings at the close of each cycle up to {@code index}, oldest first; the open one is projected. */
    static long[] savingsHistory(Snapshot snapshot, int index, int count) {
        int from = Math.max(snapshot.first(), index - count + 1);
        long[] result = new long[index - from + 1];
        for (int i = from; i <= index; i++) result[i - from] = cycle(snapshot, i).savingsAtClose();
        return result;
    }

    /** Every movement of the cushion, newest first: {day, cents} with contributions positive and imprevistos negative. */
    static List<long[]> cushionLedger(Snapshot snapshot) {
        List<long[]> result = new ArrayList<>();
        int from = snapshot.settings.cushionStart();
        if (from < 0) return result;
        for (long[] move : snapshot.settings.cushionMoves) if (move[0] >= from) result.add(new long[]{move[0], move[1]});
        for (Entry entry : snapshot.entries) if (entry.unexpected && entry.txn.day >= from) result.add(new long[]{entry.txn.day, entry.txn.cents});
        Collections.sort(result, (a, b) -> Long.compare(b[0], a[0]));
        return result;
    }

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

    private static final Set<String> EVERYDAY = new HashSet<>(Arrays.asList("cafe", "tabaco", "super", "comer", "personas", "efectivo"));

    /**
     * Free spending that behaves like a bill: the same merchant, about the same amount, once per cycle,
     * in at least three cycles (or two at the same amount and date) and seen recently.
     */
    static List<Suggestion> suggestions(Snapshot snapshot) {
        Map<String, List<Entry>> byMerchant = new LinkedHashMap<>();
        for (Entry entry : snapshot.entries) {
            if (!entry.free() || entry.kind != Ledger.Kind.SPEND || !entry.taught.isEmpty()) continue;
            if (-entry.txn.cents < 100 || snapshot.settings.dismissed.contains(entry.merchantKey)) continue;
            List<Entry> list = byMerchant.get(entry.merchantKey);
            if (list == null) { list = new ArrayList<>(); byMerchant.put(entry.merchantKey, list); }
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
                    cycles.add(snapshot.periodOf(other.txn.day));
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
                    best = new Suggestion(group.getKey(), seed.merchant, seed.category, typical, (int) (long) median(days), cycles.size());
            }
            if (best != null) result.add(best);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.amount, a.amount));
        return result;
    }

    // ------------------------------------------------------------ merchants

    static final class Merchant {
        final String key, name;
        final List<Entry> entries = new ArrayList<>();
        final Set<String> variants = new HashSet<>();
        long spent;
        String category = "otros";
        double similarity;
        Merchant(String key, String name) { this.key = key; this.name = name; }
        int count() { return entries.size(); }
    }

    /** One record per canonical merchant, with every bank spelling that was merged into it. */
    static Map<String, Merchant> merchants(Snapshot snapshot) {
        Map<String, Merchant> result = new LinkedHashMap<>();
        for (Entry entry : snapshot.entries) {
            if (entry.txn.salary || entry.txn.duplicate) continue;
            Merchant merchant = result.get(entry.merchantKey);
            if (merchant == null) { merchant = new Merchant(entry.merchantKey, entry.merchant); result.put(entry.merchantKey, merchant); }
            merchant.entries.add(entry);
            merchant.variants.add(entry.txn.merchantKey);
            if (entry.counts() && entry.kind != Ledger.Kind.INCOME) merchant.spent -= entry.txn.cents;
            if (entry.free()) merchant.category = entry.category;
        }
        return result;
    }

    /** Other merchants that look like the same place, most alike first. */
    static List<Merchant> lookalikes(Snapshot snapshot, String key, double threshold) {
        Map<String, Merchant> all = merchants(snapshot);
        Merchant self = all.get(key);
        List<Merchant> result = new ArrayList<>();
        for (Merchant other : all.values()) {
            if (other.key.equals(key)) continue;
            other.similarity = Math.max(Ledger.similarity(key, other.key),
                    self == null ? 0 : Ledger.similarity(Ledger.key(self.name), Ledger.key(other.name)));
            if (other.similarity >= threshold) result.add(other);
        }
        Collections.sort(result, (a, b) -> Double.compare(b.similarity, a.similarity));
        return result.subList(0, Math.min(8, result.size()));
    }

    // ------------------------------------------------------------ review inbox

    static final int REVIEW_INFLOW = 0, REVIEW_BIG = 1, REVIEW_UNKNOWN = 2, REVIEW_RECURRING = 3, REVIEW_MERGE = 4;

    static final class Review {
        final int type;
        final Entry entry;
        final Suggestion suggestion;
        final Merchant a, b;
        Review(int type, Entry entry, Suggestion suggestion, Merchant a, Merchant b) {
            this.type = type; this.entry = entry; this.suggestion = suggestion; this.a = a; this.b = b;
        }
        String id() {
            return entry != null ? entry.txn.id : suggestion != null ? "rec:" + suggestion.key : mergeKey(a.key, b.key);
        }
    }

    static String mergeKey(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }

    /**
     * Only what the app is unsure about, from the last 45 days: unexplained money coming in, large one-off
     * charges (perhaps unexpected costs), spending it could not place, bills hidden in free spending and
     * merchants that look like the same place. Teaching or confirming an item removes it for good.
     */
    static List<Review> review(Snapshot snapshot) {
        Settings settings = snapshot.settings;
        List<Review> result = new ArrayList<>();
        Map<String, Merchant> merchants = merchants(snapshot);
        for (Entry entry : snapshot.entries) {
            if (entry.txn.day < snapshot.today - 45 || !entry.taught.isEmpty() || entry.txn.duplicate || settings.reviewed.contains(entry.txn.id)) continue;
            // Card-side repayments are always the other leg of an account payment; never ask about them.
            if (entry.kind == Ledger.Kind.TRANSFER && entry.txn.cents >= 5_000 && !entry.txn.salary && !entry.txn.source.equals("card"))
                result.add(new Review(REVIEW_INFLOW, entry, null, null, null));
            else if (entry.free() && entry.txn.cents <= -6_000 && merchants.get(entry.merchantKey).count() == 1)
                result.add(new Review(REVIEW_BIG, entry, null, null, null));
            else if (entry.free() && entry.txn.cents < 0 && entry.category.equals("otros"))
                result.add(new Review(REVIEW_UNKNOWN, entry, null, null, null));
        }
        Collections.sort(result, (x, y) -> x.type != y.type ? Integer.compare(x.type, y.type) : Integer.compare(y.entry.txn.day, x.entry.txn.day));
        for (Suggestion suggestion : suggestions(snapshot)) result.add(new Review(REVIEW_RECURRING, null, suggestion, null, null));
        List<Merchant> list = new ArrayList<>(merchants.values());
        int merges = 0;
        for (int i = 0; i < list.size() && merges < 5; i++) {
            for (int j = i + 1; j < list.size() && merges < 5; j++) {
                Merchant a = list.get(i), b = list.get(j);
                if (!a.category.equals(b.category) || settings.separate.contains(mergeKey(a.key, b.key))) continue;
                if (Ledger.similarity(a.key, b.key) < .6) continue;
                Merchant into = a.count() >= b.count() ? a : b, other = into == a ? b : a;
                result.add(new Review(REVIEW_MERGE, null, null, into, other));
                merges++;
            }
        }
        return result;
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

    static String frequency(int months) {
        return months == 12 ? "Anual" : months == 6 ? "Semestral" : months == 3 ? "Trimestral" : months == 2 ? "Bimestral" : "Mensual";
    }

    private Budget() { }
}
