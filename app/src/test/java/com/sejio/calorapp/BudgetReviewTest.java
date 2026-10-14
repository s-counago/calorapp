package com.sejio.calorapp;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class BudgetReviewTest {
    private final int today = Ledger.parseIso("2026-10-12");
    private final List<Ledger.Txn> txns = Arrays.asList(
            new Ledger.Txn("first", "abanca", "account", "Cuenta", today - 1, today - 1, 30000, "CUENTA PROPIA", Ledger.Kind.TRANSFER, "otros"),
            new Ledger.Txn("second", "abanca", "account", "Cuenta", today - 2, today - 2, 18000, "CUENTA PROPIA", Ledger.Kind.TRANSFER, "otros"));

    private Budget.Snapshot snapshot(Budget.Settings settings) { return Budget.snapshot(txns, settings, today); }

    @Test public void rememberedTransferConfirmsExistingAnswersWithoutChangingMoney() throws Exception {
        Budget.Settings settings = new Budget.Settings();
        Budget.Snapshot before = snapshot(settings);
        assertEquals(2, Budget.review(before).size());
        settings.merchant.put("CUENTA PROPIA", "transfer");
        Budget.Snapshot after = snapshot(Budget.Settings.fromJson(new JSONObject(settings.toJson().toString())));
        assertTrue(Budget.review(after).isEmpty());
        assertEquals(Budget.cycle(before, before.last()).available(), Budget.cycle(after, after.last()).available());
        for (Budget.Entry entry : after.entries) {
            assertEquals(Ledger.Kind.TRANSFER, entry.kind);
            assertEquals("merchant", entry.taught);
            assertFalse(entry.counts());
        }
    }

    @Test public void unsafeMerchantRulesStillCannotReclassifyTransfers() {
        for (String code : new String[]{"refund", "income", "cat:personas", "unexpected", "saving"}) {
            Budget.Settings settings = new Budget.Settings();
            settings.merchant.put("CUENTA PROPIA", code);
            Budget.Snapshot snapshot = snapshot(settings);
            assertEquals(2, Budget.review(snapshot).size());
            assertEquals(0, Budget.cycle(snapshot, snapshot.last()).available());
        }
    }

    @Test public void explicitAnswersPersistOnlyForTheChosenMovementAndCanBeUndone() throws Exception {
        for (String code : new String[]{"transfer", "income", "refund", "cat:otros"}) {
            Budget.Settings settings = new Budget.Settings();
            String before = settings.toJson().toString();
            settings.txn.put("first", code);
            Budget.Snapshot answered = snapshot(Budget.Settings.fromJson(new JSONObject(settings.toJson().toString())));
            assertEquals(1, Budget.review(answered).size());
            assertEquals("second", Budget.review(answered).get(0).id());
            assertEquals("txn", answered.entries.get(0).taught);
            assertEquals(2, Budget.review(snapshot(Budget.Settings.fromJson(new JSONObject(before)))).size());
        }
    }
}
