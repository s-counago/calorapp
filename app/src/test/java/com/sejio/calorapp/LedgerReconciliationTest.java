package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import java.util.TimeZone;
import static org.junit.Assert.*;

public final class LedgerReconciliationTest {
    private final JSONArray rows = new JSONArray();

    private void movement(String id, String bank, String source, String date, String valueDate, String amount, String description) throws Exception {
        rows.put(new JSONObject().put("id", id).put("bank", bank).put("productType", source)
                .put("data", new JSONObject().put("operationDate", date).put("valueDate", valueDate).put("description", description)
                        .put("amount", new JSONObject().put("amount", amount).put("currency", "EUR"))));
    }

    private void card(String id, String date, String amount, String description) throws Exception {
        movement(id, "abanca", "card", date, date, amount, description);
    }

    private void account(String id, String valueDate, String amount, String description) throws Exception {
        movement(id, "abanca", "account", "2026-10-07", valueDate, amount, description);
    }

    private List<Ledger.Txn> ledger() { return Ledger.fromDatabase(rows, TimeZone.getTimeZone("Europe/Madrid")); }

    private int duplicates(List<Ledger.Txn> txns) {
        int count = 0;
        for (Ledger.Txn txn : txns) if (txn.duplicate) count++;
        return count;
    }

    @Test public void htmlDebitAndTerminalPurchasesMatchByValueDateAndMerchant() throws Exception {
        card("c1", "2026-10-02", "-6.47", "OBSIDIAN");
        account("a1", "2026-10-02", "-6.47", "OBSIDIAN OAKVILLE 123456 Fecha operación: 07/10/2026 0:07:51 Tipo: OPERACIONES TARJETA DEBITO");
        card("c2", "2026-10-01", "-0.07", "Google CLOUD Dv3TVB");
        account("a2", "2026-10-01", "-0.07", "Google CLOUD Dv3TVB Dublin 123456 Fecha operación: 06/10/2026 0:29:49 Tipo: OPERACIONES TARJETA DEBITO");
        card("c3", "2026-10-05", "-6.80", "TIENDA LOCAL\\A CORU");
        account("a3", "2026-10-05", "-6.80", "TIENDA LOCAL\\A CORUÑA\\ES123456 Fecha operación: 05/10/2026 8:33:55 Tipo: OPERACIONES T.P.V.");
        assertEquals(3, duplicates(ledger()));
    }

    @Test public void equalAmountDoesNotJoinDifferentMerchantsOrBanks() throws Exception {
        card("c1", "2026-10-02", "-10", "TIENDA UNO");
        account("a1", "2026-10-02", "-10", "123456789012 TIENDA DOS");
        movement("c2", "other_bank", "card", "2026-10-02", "2026-10-02", "-10", "TIENDA DOS");
        assertEquals(0, duplicates(ledger()));
    }

    @Test public void repeatedPurchasesArePairedOneToOne() throws Exception {
        card("c1", "2026-10-02", "-10", "TIENDA UNO");
        card("c2", "2026-10-02", "-10", "TIENDA UNO");
        for (int i = 0; i < 3; i++) account("a" + i, "2026-10-02", "-10", "123456789012 TIENDA UNO");
        assertEquals(2, duplicates(ledger()));
        long spent = 0;
        for (Ledger.Txn txn : ledger()) if (txn.kind == Ledger.Kind.SPEND) spent -= txn.cents;
        assertEquals(3000, spent);
    }

    @Test public void refundsAlsoCountOnce() throws Exception {
        card("c1", "2026-10-02", "10", "TIENDA UNO");
        account("a1", "2026-10-02", "10", "123456789012 TIENDA UNO");
        assertEquals(1, duplicates(ledger()));
        int refunds = 0;
        for (Ledger.Txn txn : ledger()) if (txn.kind == Ledger.Kind.REFUND) refunds++;
        assertEquals(1, refunds);
    }

    @Test public void unmarkedOrDistantAccountMovementsRemainSeparate() throws Exception {
        card("c1", "2026-10-02", "-10", "TIENDA UNO");
        account("a1", "2026-10-02", "-10", "TIENDA UNO");
        account("a2", "2026-10-06", "-10", "123456789012 TIENDA UNO");
        assertEquals(0, duplicates(ledger()));
    }

    @Test public void closestDatesTakePriorityRegardlessOfRowOrder() throws Exception {
        card("c1", "2026-10-01", "-10", "TIENDA UNO");
        card("c2", "2026-10-04", "-10", "TIENDA UNO");
        account("a1", "2026-10-04", "-10", "123456789012 TIENDA UNO");
        account("a2", "2026-09-30", "-10", "123456789012 TIENDA UNO");
        assertEquals(2, duplicates(ledger()));
    }

    @Test public void classifyingAnAccountCopyDoesNotCountThePurchaseTwice() throws Exception {
        card("c1", "2026-10-02", "-10", "TIENDA UNO");
        account("a1", "2026-10-02", "-10", "123456789012 TIENDA UNO");
        Budget.Settings settings = new Budget.Settings();
        settings.txn.put("a1", "cat:compras");
        settings.movementNames.put("a1", "Nombre guardado");
        long spent = 0;
        for (Budget.Entry entry : Budget.resolve(ledger(), settings)) {
            if (entry.free()) spent -= entry.txn.cents;
            if (entry.txn.id.equals("a1")) assertEquals("Nombre guardado", entry.label());
        }
        assertEquals(1000, spent);
        assertEquals("cat:compras", settings.txn.get("a1"));
    }
}
