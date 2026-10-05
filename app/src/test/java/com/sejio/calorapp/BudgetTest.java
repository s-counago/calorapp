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
        Budget.Line rent = line(cycle, "alquiler"), couple = line(cycle, "pareja"), netflix = line(cycle, "netflix"), invest = line(cycle, "inversion");
        assertEquals(Budget.PAID, rent.status);
        assertEquals(2853, rent.paid);
        assertEquals(24262, rent.expected); // Median of the last two rents.
        assertEquals(Budget.PAID, couple.status);
        assertEquals(Budget.UPCOMING, netflix.status);
        assertEquals(1499, netflix.expected);
        assertEquals(day("2026-10-10"), netflix.due); // Learned: ten days after payday.
        assertEquals(Budget.PARTIAL, invest.status);
        assertEquals(Budget.PAID, line(cycle, "gimnasio").status);
        // Transfers to Trade Republic, card repayments and inbound transfers are not spending.
        assertEquals(770 + 540, cycle.freeSpent);
        assertNotNull(cycle.habit);
        assertEquals(3, cycle.habit.count);
        assertEquals(cycle.income() - cycle.fixed() - cycle.freeSpent, cycle.available());
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

    private static Budget.Line line(Budget.Cycle cycle, String id) {
        for (Budget.Line line : cycle.lines) if (line.rule.id.equals(id)) return line;
        throw new AssertionError("Missing line " + id);
    }
}
