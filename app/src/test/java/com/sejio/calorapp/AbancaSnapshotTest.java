package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Fictional records only. No bank connectivity or uploaded HAR fixtures. */
public class AbancaSnapshotTest {
    private JSONObject money(String amount, String currency) throws Exception {
        return new JSONObject().put("amount", amount).put("currency", currency == null ? JSONObject.NULL : currency);
    }
    private JSONObject account() throws Exception {
        return new JSONObject().put("operationDate", "2026-10-05").put("valueDate", "2026-10-06")
                .put("description", "Compra ficticia").put("amount", money("-12.50", "EUR"))
                .put("balance", money("1234.56", "EUR"));
    }
    private JSONObject capture(String type, JSONObject... records) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject record : records) array.put(record);
        return new JSONObject().put("reader", AbancaSnapshot.READER).put("status", "captured")
                .put("pageType", type).put("partial", true).put("truncated", false).put("omittedRows", 0).put("records", array);
    }
    private void rejects(JSONObject data) throws Exception {
        try { AbancaSnapshot.prepare(data); fail("invalid record accepted"); }
        catch (IllegalArgumentException | org.json.JSONException expected) { }
    }

    @Test public void keepsIdenticalTransactionsAndDistinctDates() throws Exception {
        JSONObject result = AbancaSnapshot.prepare(capture("account", account(), account()));
        assertEquals(2, result.getJSONArray("records").length());
        assertEquals(2, result.getJSONArray("rows").length());
        String row = result.getJSONArray("rows").getString(0);
        assertTrue(row.contains("05/10/2026")); assertTrue(row.contains("Fecha valor: 06/10/2026"));
        assertTrue(row.contains("Importe: -12,50 EUR")); assertTrue(row.contains("Saldo: 1.234,56 EUR"));
        AbancaSnapshot.validate(result);
    }

    @Test public void separatesCardPaymentAndSituationFromAccountDates() throws Exception {
        JSONObject card = new JSONObject().put("operationDate", "2026-10-05").put("description", "Tienda ficticia")
                .put("amount", money("-8.90", "EUR")).put("operationType", "Compra")
                .put("situation", "Pendiente").put("payment", "Próxima liquidación");
        JSONObject result = AbancaSnapshot.prepare(capture("card", card));
        JSONObject record = result.getJSONArray("records").getJSONObject(0);
        assertEquals("Pendiente", record.getString("situation"));
        assertFalse(record.has("valueDate")); assertFalse(record.has("balance"));
        assertTrue(result.getJSONArray("rows").getString(0).contains("Pago: Próxima liquidación"));
    }

    @Test public void distinguishesCardDebtAndLimitWithoutInventingCash() throws Exception {
        JSONObject card = new JSONObject().put("productType", "card").put("label", "Tarjeta ficticia").put("kind", "Crédito")
                .put("limit", money("2000.00", "EUR")).put("balance", money("100.00", "EUR"));
        JSONObject result = AbancaSnapshot.prepare(capture("overview", card));
        String row = result.getJSONArray("rows").getString(0);
        assertTrue(row.contains("Dispuesto: 100,00 EUR")); assertTrue(row.contains("Límite concedido: 2.000,00 EUR"));
        assertFalse(row.contains("disponible"));
    }

    @Test public void exactDecimalsUnknownCurrencyAndNoImplicitConversion() throws Exception {
        JSONObject record = account().put("amount", money("999999999999999999.99", null));
        JSONObject result = AbancaSnapshot.prepare(capture("account", record));
        assertEquals("999999999999999999.99", result.getJSONArray("records").getJSONObject(0)
                .getJSONObject("amount").getString("amount"));
        assertTrue(result.getJSONArray("rows").getString(0).contains("999.999.999.999.999.999,99 (moneda no indicada)"));
    }

    @Test public void dropsUnrecognizedKeysAndCapturedLinks() throws Exception {
        JSONObject record = account().put("href", "https://example.test/?k=FAKE_TOKEN").put("pin_number", "FAKE_PIN");
        record.getJSONObject("amount").put("cookie", "FAKE_COOKIE");
        JSONObject source = capture("account", record).put("html", "FAKE_HTML").put("url", "FAKE_URL");
        String result = AbancaSnapshot.prepare(source).toString();
        for (String value : new String[]{"FAKE_TOKEN", "FAKE_PIN", "FAKE_COOKIE", "FAKE_HTML", "FAKE_URL"})
            assertFalse(result.contains(value));
    }

    @Test public void malformedAmountsDatesAndTypesAreRejected() throws Exception {
        for (String amount : new String[]{"1e3", "NaN", "12,50", "01.00", "-0.00", "1000000000000000000.00"})
            rejects(capture("account", account().put("amount", money(amount, "EUR"))));
        rejects(capture("account", account().put("operationDate", "2026-02-31")));
        rejects(capture("account", account().put("description", 42)));
        rejects(capture("account", account().put("amount", JSONObject.NULL)));
        rejects(capture("account", account().put("amount", money("1.00", "€"))));
        rejects(capture("unknown", account()));
        rejects(capture("account"));
    }

    @Test public void labelsAndRecordsAreBounded() throws Exception {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 241; i++) tooLong.append('x');
        rejects(capture("account", account().put("description", tooLong.toString())));
        JSONObject many = capture("account");
        for (int i = 0; i < 201; i++) many.getJSONArray("records").put(account());
        rejects(many);
        rejects(capture("account", account()).put("omittedRows", -1));
    }

    @Test public void utf8PayloadIsBoundedWithExplicitTruncation() throws Exception {
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < 240; i++) description.append('界');
        JSONObject source = capture("account");
        for (int i = 0; i < 200; i++) source.getJSONArray("records").put(account().put("description", description.toString()));
        JSONObject result = AbancaSnapshot.prepare(source);
        assertTrue(result.getBoolean("truncated"));
        assertTrue(result.getJSONArray("records").length() < 200);
        assertEquals(result.getJSONArray("records").length(), result.getJSONArray("rows").length());
        assertTrue(result.toString().getBytes(StandardCharsets.UTF_8).length < 128 * 1024);
        AbancaSnapshot.validate(result);
    }

    @Test public void rejectsDisplayThatDisagreesWithRecords() throws Exception {
        JSONObject result = AbancaSnapshot.prepare(capture("account", account()));
        result.put("rows", new JSONArray().put("Saldo inventado"));
        try { AbancaSnapshot.validate(result); fail("inconsistent display accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
