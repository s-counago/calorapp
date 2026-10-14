package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import java.util.TimeZone;
import static org.junit.Assert.*;

public final class BudgetProjectionTest {
    private final JSONArray rows = new JSONArray();

    private void account(String id, String day, String amount, String description) throws Exception {
        rows.put(new JSONObject().put("id", id).put("bank", "abanca").put("productType", "account")
                .put("data", new JSONObject().put("operationDate", day).put("description", description)
                        .put("amount", new JSONObject().put("amount", amount).put("currency", "EUR"))));
    }

    private Budget.Settings settings() {
        Budget.Settings settings = new Budget.Settings();
        settings.goal = 15000;
        settings.rules.add(new Budget.Rule("fixed", "Fijo", "home", 42700, 1, "RECIBO"));
        return settings;
    }

    private List<Ledger.Txn> ledger() { return Ledger.fromDatabase(rows, TimeZone.getTimeZone("Europe/Madrid")); }

    private Budget.Cycle cycle(Budget.Settings settings, String today) {
        Budget.Snapshot snapshot = Budget.snapshot(ledger(), settings, Ledger.parseIso(today));
        return Budget.cycle(snapshot, snapshot.last());
    }

    @Test public void refundsIncreaseAvailableMoneyOnlyOnceAndDoNotReduceFutureSpendingRate() throws Exception {
        account("salary", "2026-09-30", "1000", "NOMINA");
        account("purchase", "2026-10-01", "-100", "TIENDA");
        account("refund", "2026-10-02", "20", "DEVOLUCION TIENDA");
        Budget.Cycle cycle = cycle(settings(), "2026-10-09");
        assertEquals(42300, cycle.freeBudget());
        assertEquals(34300, cycle.available());
        assertEquals(10000, cycle.freeOutflow);
        assertEquals(14300, cycle.projection());
    }

    @Test public void refundLargerThanSpendingNeverCreatesProjectedFutureIncome() throws Exception {
        account("salary", "2026-09-30", "1000", "NOMINA");
        account("purchase", "2026-10-01", "-100", "TIENDA");
        account("refund", "2026-10-02", "200", "DEVOLUCION TIENDA");
        Budget.Cycle cycle = cycle(settings(), "2026-10-09");
        assertEquals(-10000, cycle.freeSpent);
        assertEquals(52300, cycle.available());
        assertEquals(32300, cycle.projection());
        assertTrue(cycle.projection() <= cycle.available());
    }

    @Test public void rememberedMerchantCannotTurnTransfersIntoRefundsButIndividualCorrectionsStillWork() throws Exception {
        account("salary", "2026-09-30", "1000", "NOMINA");
        account("transfer", "2026-10-01", "300", "CUENTA PROPIA");
        Budget.Settings settings = settings();
        settings.merchant.put("CUENTA PROPIA", "cat:otros");
        assertEquals(42300, cycle(settings, "2026-10-09").available());
        settings.txn.put("transfer", "income");
        assertEquals(72300, cycle(settings, "2026-10-09").available());
    }

    @Test public void earlyCycleRateUsesHistoricalOutflowsWithoutRepeatingRefunds() throws Exception {
        account("previousSalary", "2026-08-31", "1000", "NOMINA");
        account("purchase", "2026-09-01", "-300", "TIENDA");
        account("refund", "2026-09-02", "400", "DEVOLUCION TIENDA");
        account("salary", "2026-09-30", "1000", "NOMINA");
        Budget.Cycle cycle = cycle(new Budget.Settings(), "2026-10-01");
        assertEquals(1000, cycle.averageDaily);
        assertEquals(72000, cycle.projection());
    }
}
