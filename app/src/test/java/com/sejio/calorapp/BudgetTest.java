package com.sejio.calorapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.TimeZone;

public final class BudgetTest {
    private static final TimeZone MADRID = TimeZone.getTimeZone("Europe/Madrid");
    private final JSONArray rows = new JSONArray();
    private int ids;

    private void account(String date, String amount, String description) throws Exception {
        rows.put(row("abanca", "account", new JSONObject().put("operationDate", date).put("valueDate", date)
                .put("description", description).put("amount", money(amount))));
    }

    private void accountValued(String date, String value, String amount, String description) throws Exception {
        rows.put(row("abanca", "account", new JSONObject().put("operationDate", date).put("valueDate", value)
                .put("description", description).put("amount", money(amount))));
    }

    private void card(String date, String type, String amount, String description) throws Exception {
        rows.put(row("abanca", "card", new JSONObject().put("operationDate", date).put("operationType", type)
                .put("situation", "Liquidado").put("description", description).put("amount", money(amount))));
    }

    private void trade(String instant, String eventType, String amount, String title) throws Exception {
        rows.put(row("trade_republic", "investment_account", new JSONObject().put("bankId", "tr-" + ids).put("occurredAt", instant)
                .put("eventType", eventType).put("status", "EXECUTED").put("description", title)
                .put("amount", new JSONObject().put("amount", amount).put("currency", "EUR"))));
    }

    private JSONObject row(String bank, String type, JSONObject data) throws Exception {
        return new JSONObject().put("id", "m" + (ids++)).put("bank", bank).put("productType", type).put("productLabel", "").put("data", data);
    }

    private static JSONObject money(String amount) throws Exception {
        return new JSONObject().put("amount", amount).put("currency", "EUR");
    }

    private static int day(String iso) { return Ledger.parseIso(iso); }

    /** Three paydays, the usual bills and a handful of everyday purchases. Today is 2026-10-05. */
    private void typicalMonths() throws Exception {
        for (String payday : new String[]{"2026-07-31", "2026-08-31", "2026-09-30"}) account(payday, "1408.25", "EMPRESA SL NOMINA");
        account("2026-07-10", "-267.68", "ALQUILER, LUZ, INTERNET, JULIO"); // Before the first payroll: learning only.
        account("2026-08-04", "-227.01", "ALQUILER, INTERNET, LUZ AGOSTO");
        account("2026-09-02", "-258.23", "ALQUILER, AGUA, LUZ SEPTIEMBRE");
        account("2026-10-05", "-28.53", "ALQUILER, INTERNET, LUZ OCTUBRE");
        account("2026-08-03", "-500.00", "CUENTA PAREJA");
        account("2026-09-02", "-570.00", "CUENTA PAREJA");
        account("2026-10-02", "-500.00", "CUENTA PAREJA");
        account("2026-10-01", "-118.24", "CARGO PRESTAMO 5059-005951-000 00-");
        account("2026-10-02", "-300.00", "TRADE REPUBLIC");
        account("2026-10-01", "-39.80", "707191708690 AMORTIZACION DEUDA 44070-6904");
        card("2026-09-30", "AMORTIZACION DEUDA", "39.80", "AMORTIZACION DEUDA");
        card("2026-08-31", "INGR. TARJETA", "99.76", "pago tj");
        account("2026-08-31", "-99.76", "pago tj");
        card("2026-10-01", "FRA. VENTA", "-7.70", "ESTANCO CAMI`O NOV\\SANTIA");
        trade("2026-10-02T08:10:34.439Z", "card_successful_transaction", "-1.80", "A CRUCENA");
        trade("2026-10-03T08:10:34.439Z", "card_successful_transaction", "-1.80", "A CRUCENA");
        trade("2026-10-04T08:10:34.439Z", "card_successful_transaction", "-1.80", "A CRUCENA");
        trade("2026-10-04T10:00:00Z", "card_successful_transaction", "-29.90", "EVOFIT SANTIAGO");
        trade("2026-09-10T10:00:00Z", "card_successful_transaction", "-14.99", "NETFLIX.COM");
        trade("2026-08-10T10:00:00Z", "card_successful_transaction", "-14.99", "NETFLIX.COM");
        trade("2026-10-02T09:00:00Z", "PAYMENT_INBOUND", "300.00", "Sergio");
        trade("2026-10-02T09:00:00Z", "SAVINGS_PLAN_EXECUTED", "-8.46", "IBEX 35 EUR (Acc)");
    }

    @Test public void daysRoundTripAndPaydayPrediction() {
        assertEquals("2026-10-05", Ledger.iso(day("2026-10-05")));
        assertEquals(0, Ledger.weekday(day("2026-10-05")));
        assertEquals("2026-10-30", Ledger.iso(Budget.nextPayday(day("2026-09-30"))));
        assertEquals("2026-07-31", Ledger.iso(Budget.nextPayday(day("2026-06-30"))));
        assertEquals("2026-11-13", Ledger.iso(Budget.nextPayday(day("2026-10-15")))); // Sunday 15 moves back to Friday.
        assertEquals(29, Ledger.monthLength(2028, 2));
    }

    @Test public void tradeInstantsBecomeLocalDays() {
        assertEquals(day("2026-06-02"), Ledger.localDay("2026-06-01T23:30:00.123456Z", MADRID));
        assertEquals(day("2026-06-01"), Ledger.localDay("2026-06-01T21:30:00Z", MADRID));
        assertEquals(day("2026-06-01"), Ledger.localDay("2026-06-01T23:30:00+02:00", MADRID));
    }

    @Test public void merchantsReadLikePeopleWriteThem() {
        assertEquals("Estanco Matogrande", Ledger.pretty("ESTANCO MATOGRANDE\\A CORU"));
        assertEquals("Google Cloud", Ledger.pretty("434001330196 GOOGLE*CLOUD V5X543 CC GOOGLE.COM 000000"));
        assertEquals("Netflix", Ledger.pretty("NETFLIX.COM"));
        assertEquals("Bizum · cerveza", Ledger.pretty("PAGO BIZUM - cerveza"));
        assertEquals("Dia", Ledger.pretty("DIA RETAIL 4532"));
        assertEquals("A Crucena", Ledger.pretty("A CRUCENA"));
        assertEquals("cafe", Ledger.guessCategory(Ledger.key("A CRUCENA")));
        assertEquals("tabaco", Ledger.guessCategory(Ledger.key("ESTANCO AURORA")));
        assertEquals("super", Ledger.guessCategory(Ledger.key("MERCADONA MARI")));
    }

    @Test public void moneyIsSpanish() {
        assertEquals("1.408 €", Budget.money(140825));
        assertEquals("6,80 €", Budget.money(680));
        assertEquals("−83 €", Budget.money(-8300));
        assertEquals("1.408,25 €", Budget.money(140825, true));
        assertEquals("0 €", Budget.money(-20, false));
    }

    @Test public void debitChargesAreCountedOnce() throws Exception {
        card("2026-09-12", "FRA. VENTA", "-10.20", "ESTANCO CAMI`O NOV\\SANTIA");
        accountValued("2026-09-14", "2026-09-12", "-10.20", "434001330196 ESTANCO CAMI?O NOV\\SANTIAGO\\ES2609121939");
        List<Ledger.Txn> txns = Ledger.fromDatabase(rows, MADRID);
        int counted = 0;
        for (Ledger.Txn txn : txns) if (txn.kind == Ledger.Kind.SPEND) counted++;
        assertEquals(1, counted);
    }

    @Test public void cyclesFollowThePayroll() throws Exception {
        typicalMonths();
        Budget.Snapshot snapshot = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), Budget.Settings.defaults(), day("2026-10-05"));
        Budget.Period current = snapshot.periods.get(snapshot.last());
        assertEquals(day("2026-09-30"), current.start);
        assertEquals(day("2026-10-30"), current.end);
        assertTrue(snapshot.periods.get(0).partial);
        assertEquals(1, snapshot.first());
    }

    @Test public void fixedLinesReserveMoneyAndFreeSpendingComesOutOfTheRest() throws Exception {
        typicalMonths();
        Budget.Snapshot snapshot = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), Budget.Settings.defaults(), day("2026-10-05"));
        Budget.Cycle cycle = Budget.cycle(snapshot, snapshot.last());
        assertTrue(cycle.current);
        assertEquals(140825, cycle.income());
        Budget.Line rent = line(cycle, "alquiler"), couple = line(cycle, "pareja"), netflix = line(cycle, "netflix");
        assertEquals(Budget.PAID, rent.status);
        assertEquals(2853, rent.paid);
        assertEquals(24262, rent.expected); // Median of the last two rents.
        assertEquals(Budget.PAID, couple.status);
        assertEquals(Budget.UPCOMING, netflix.status);
        assertEquals(1499, netflix.expected);
        assertEquals(day("2026-10-10"), netflix.due); // Learned: ten days after payday.
        assertEquals(846, cycle.saved); // Investing is savings, not a fixed line.
        assertEquals(846, cycle.savingsReserve());
        assertEquals(Budget.PAID, line(cycle, "gimnasio").status);
        // Transfers to Trade Republic, card repayments and inbound transfers are not spending.
        assertEquals(770 + 540, cycle.freeSpent);
        assertNotNull(cycle.habit);
        assertEquals(3, cycle.habit.count);
        assertEquals(cycle.income() - cycle.fixed() - cycle.savingsReserve() - cycle.freeSpent, cycle.available());
        assertTrue(cycle.fixedPending > 0);
    }

    @Test public void lateAndSoonFollowTheLearnedDay() throws Exception {
        typicalMonths();
        Budget.Snapshot later = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), Budget.Settings.defaults(), day("2026-10-15"));
        assertEquals(Budget.LATE, line(Budget.cycle(later, later.last()), "netflix").status);
        Budget.Snapshot close = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), Budget.Settings.defaults(), day("2026-10-08"));
        assertEquals(Budget.SOON, line(Budget.cycle(close, close.last()), "netflix").status);
    }

    @Test public void ownerCorrectionsWin() throws Exception {
        typicalMonths();
        List<Ledger.Txn> txns = Ledger.fromDatabase(rows, MADRID);
        Budget.Settings settings = Budget.Settings.defaults();
        settings.merchant.put("A CRUCENA", "transfer");
        settings.skipped.add("netflix@2026-09-30");
        Ledger.Txn tobacco = null;
        for (Ledger.Txn txn : txns) if (txn.merchant.startsWith("Estanco")) tobacco = txn;
        assertNotNull(tobacco);
        settings.txn.put(tobacco.id, "cat:regalos");
        Budget.Snapshot snapshot = Budget.snapshot(txns, settings, day("2026-10-05"));
        Budget.Cycle cycle = Budget.cycle(snapshot, snapshot.last());
        assertEquals(770, cycle.freeSpent);
        assertEquals("regalos", cycle.slices.get(0).category.id);
        assertEquals(Budget.SKIPPED, line(cycle, "netflix").status);
        assertNull(cycle.habit);
        Budget.Settings copy = Budget.Settings.fromJson(new JSONObject(settings.toJson().toString()));
        assertEquals(settings.rules.size(), copy.rules.size());
        assertEquals("transfer", copy.merchant.get("A CRUCENA"));
        assertTrue(copy.skipped.contains("netflix@2026-09-30"));
        assertEquals(550, copy.rule("google").min);
    }

    @Test public void billsHiddenInEverydaySpendingAreSuggested() throws Exception {
        typicalMonths();
        trade("2026-07-21T10:00:00Z", "card_successful_transaction", "-60.00", "RENFE VIRTUAL INTERNET");
        trade("2026-08-17T10:00:00Z", "card_successful_transaction", "-60.00", "RENFE VIRTUAL INTERNET");
        trade("2026-09-09T10:00:00Z", "card_successful_transaction", "-60.00", "RENFE VIRTUAL INTERNET");
        trade("2026-09-09T11:00:00Z", "card_successful_transaction", "-0.90", "RENFE VIRTUAL INTERNET");
        Budget.Snapshot snapshot = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), Budget.Settings.defaults(), day("2026-10-05"));
        List<Budget.Suggestion> suggestions = Budget.suggestions(snapshot);
        assertEquals(1, suggestions.size());
        assertEquals("Renfe", suggestions.get(0).name);
        assertEquals(6000, suggestions.get(0).amount);
        Budget.Rule rule = suggestions.get(0).toRule();
        assertEquals("train", rule.symbol);
        snapshot.settings.dismissed.add(suggestions.get(0).key);
        assertTrue(Budget.suggestions(snapshot).isEmpty());
    }

    @Test public void withoutPayrollCalendarMonthsAreUsed() throws Exception {
        card("2026-09-12", "FRA. VENTA", "-10.20", "ESTANCO");
        card("2026-10-02", "FRA. VENTA", "-3.00", "CAFE");
        Budget.Snapshot snapshot = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), Budget.Settings.defaults(), day("2026-10-05"));
        assertEquals(2, snapshot.periods.size());
        Budget.Cycle cycle = Budget.cycle(snapshot, snapshot.last());
        assertEquals(day("2026-10-01"), cycle.period.start);
        assertEquals(300, cycle.freeSpent);
        assertFalse(cycle.salaryEstimated);
    }

    @Test public void quarterlyChargesAreSetAsideEveryCycle() throws Exception {
        typicalMonths();
        account("2026-08-05", "-20.19", "00SSAN031813 CONFEDERACION INTERSINDICAL GALEGA");
        Budget.Snapshot snapshot = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), Budget.Settings.defaults(), day("2026-10-05"));
        Budget.Line cig = line(Budget.cycle(snapshot, snapshot.last()), "cig");
        assertEquals(Budget.RESERVED, cig.status);
        assertEquals(2019, cig.expected);
        assertEquals(673, cig.reserve());
        assertEquals(day("2026-11-05"), cig.due);
        Budget.Snapshot november = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), Budget.Settings.defaults(), day("2026-11-10"));
        assertEquals(Budget.LATE, line(Budget.cycle(november, november.last()), "cig").status);
    }

    @Test public void unexpectedCostsComeFromTheCushionFirst() throws Exception {
        typicalMonths();
        account("2026-10-03", "-340.00", "TALLER MECANICO PEREZ");
        List<Ledger.Txn> txns = Ledger.fromDatabase(rows, MADRID);
        Budget.Settings settings = Budget.Settings.defaults();
        Ledger.Txn repair = null;
        for (Ledger.Txn txn : txns) if (txn.merchant.startsWith("Taller")) repair = txn;
        settings.txn.put(repair.id, "unexpected");
        settings.notes.put(repair.id, "Reparación coche");
        Budget.Snapshot first = Budget.snapshot(txns, settings, day("2026-10-05"));
        Budget.Cycle uncovered = Budget.cycle(first, first.last());
        assertEquals(34000, uncovered.unexpected);
        assertEquals(34000, uncovered.uncovered());
        assertEquals(770 + 540, uncovered.freeSpent); // Not mixed with free spending.
        settings.cushion = 50000; settings.cushionDay = day("2026-09-01");
        Budget.Snapshot second = Budget.snapshot(txns, settings, day("2026-10-05"));
        Budget.Cycle covered = Budget.cycle(second, second.last());
        assertEquals(34000, covered.covered);
        assertEquals(16000, covered.cushionLeft());
        assertEquals(uncovered.available() + 34000, covered.available());
        assertEquals("Reparación coche", covered.unexpectedEntries.get(0).note);
    }

    @Test public void aSavingsGoalIsReservedUpFront() throws Exception {
        typicalMonths();
        Budget.Settings settings = Budget.Settings.defaults();
        settings.goal = 15000;
        Budget.Snapshot snapshot = Budget.snapshot(Ledger.fromDatabase(rows, MADRID), settings, day("2026-10-05"));
        Budget.Cycle cycle = Budget.cycle(snapshot, snapshot.last());
        assertEquals(15000, cycle.savingsReserve());
        assertEquals(cycle.income() - cycle.fixed() - 15000, cycle.freeBudget());
        assertTrue(cycle.savingsAtClose() >= 15000);
    }

    @Test public void merchantsCanBeRenamedAndMerged() throws Exception {
        card("2026-09-12", "FRA. VENTA", "-10.20", "ESTANCO CAMI`O NOV\\SANTIA");
        trade("2026-09-14T10:00:00Z", "card_successful_transaction", "-6.80", "ESTANCO CAMINO NOVO");
        trade("2026-09-15T10:00:00Z", "card_successful_transaction", "-6.80", "ESTANCO AURORA");
        List<Ledger.Txn> txns = Ledger.fromDatabase(rows, MADRID);
        Budget.Settings settings = Budget.Settings.defaults();
        Budget.Snapshot before = Budget.snapshot(txns, settings, day("2026-10-05"));
        Budget.Merchant spelled = Budget.merchants(before).get("ESTANCO CAMINO NOV");
        assertNotNull(spelled);
        assertEquals("Estanco Camiño Nov", spelled.name);
        List<Budget.Merchant> alike = Budget.lookalikes(before, "ESTANCO CAMINO NOVO", .35);
        assertEquals("ESTANCO CAMINO NOV", alike.get(0).key);
        boolean merge = false;
        for (Budget.Review item : Budget.review(before)) merge |= item.type == Budget.REVIEW_MERGE;
        assertTrue(merge);
        settings.names.put("ESTANCO CAMINO NOVO", "Estanco Camino Novo");
        settings.merge("ESTANCO CAMINO NOVO", "ESTANCO CAMINO NOV");
        settings.merchant.put("ESTANCO CAMINO NOVO", "cat:regalos");
        Budget.Snapshot after = Budget.snapshot(txns, settings, day("2026-10-05"));
        Budget.Merchant unified = Budget.merchants(after).get("ESTANCO CAMINO NOVO");
        assertEquals(2, unified.count());
        assertEquals(1700, unified.spent);
        for (Budget.Entry entry : after.entries) if (entry.merchantKey.equals("ESTANCO CAMINO NOVO")) {
            assertEquals("Estanco Camino Novo", entry.merchant);
            assertEquals("regalos", entry.category);
        }
        settings.split("ESTANCO CAMINO NOV");
        assertEquals(1, Budget.merchants(Budget.snapshot(txns, settings, day("2026-10-05"))).get("ESTANCO CAMINO NOVO").count());
    }

    @Test public void theInboxOnlyHoldsWhatIsUnclear() throws Exception {
        typicalMonths();
        account("2026-10-03", "-340.00", "TALLER MECANICO PEREZ");
        account("2026-10-03", "-16.00", "RETRO");
        account("2026-10-01", "300.00", "Sergio Enviada desde Revolut");
        List<Ledger.Txn> txns = Ledger.fromDatabase(rows, MADRID);
        Budget.Settings settings = Budget.Settings.defaults();
        List<Budget.Review> items = Budget.review(Budget.snapshot(txns, settings, day("2026-10-05")));
        assertEquals(Budget.REVIEW_INFLOW, items.get(0).type);
        int big = 0, unknown = 0;
        for (Budget.Review item : items) { if (item.type == Budget.REVIEW_BIG) big++; if (item.type == Budget.REVIEW_UNKNOWN) unknown++; }
        assertEquals(1, big);
        assertEquals(1, unknown);
        for (Budget.Review item : items) if (item.entry != null) settings.reviewed.add(item.entry.txn.id);
        for (Budget.Review item : Budget.review(Budget.snapshot(txns, settings, day("2026-10-05")))) assertNull(item.entry);
    }

    @Test public void versionOneSettingsUpgrade() throws Exception {
        JSONObject old = new JSONObject().put("version", 1).put("rules", new JSONArray()
                .put(new JSONObject().put("id", "google").put("name", "Google Play").put("symbol", "spark").put("expected", 649).put("min", 550).put("max", 750))
                .put(new JSONObject().put("id", "inversion").put("name", "Plan de inversión").put("invest", true).put("expected", 7200)))
                .put("txn", new JSONObject().put("x", "rule:inversion").put("y", "cat:cafe"));
        Budget.Settings settings = Budget.Settings.fromJson(old);
        assertEquals("Spotify", settings.rule("google").name);
        assertNull(settings.rule("inversion"));
        assertEquals(3, settings.rule("cig").frequency);
        assertFalse(settings.txn.containsKey("x"));
        assertEquals("cat:cafe", settings.txn.get("y"));
        assertEquals(Budget.VERSION, settings.toJson().getInt("version"));
    }

    @Test public void abancaSpellingIsRepaired() {
        assertEquals("ESTANCO CAMIÑO NOV", Ledger.repair("ESTANCO CAMI`O NOV"));
        assertEquals("A CORUÑA", Ledger.repair("A CORU�A"));
        assertEquals("NOMINA 06?2026", Ledger.repair("NOMINA 06?2026"));
    }

    private static Budget.Line line(Budget.Cycle cycle, String id) {
        for (Budget.Line line : cycle.lines) if (line.rule.id.equals(id)) return line;
        throw new AssertionError("Missing line " + id);
    }
}
