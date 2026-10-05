package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class BankingImportTest {
    static final String ACCOUNT = "Fecha ctble;Fecha valor;Concepto;Importe;Moneda;Saldo;Moneda;Concepto ampliado\r\n";
    static final String ROW = "01-06-2026;02-06-2026;Tienda ficticia;-12,50;EUR;987,50;EUR;Detalle adicional\r\n";
    private BankingImport.Parsed parse(String text) throws Exception { return BankingImport.parse(text.getBytes(StandardCharsets.UTF_8)); }
    private JSONObject product(String bank, String type) throws Exception {
        return new JSONObject().put("bank", bank).put("type", type).put("id", "product").put("label", "Ficticio");
    }
    private String tr(String id, String amount, String timestamp) {
        return "datetime,date,account_type,category,type,asset_class,name,symbol,shares,price,amount,fee,tax,currency,original_amount,original_currency,fx_rate,description,transaction_id,counterparty_name,counterparty_iban,payment_reference,mcc_code\n"
                + timestamp + ",2026-06-01,current,cash,payment,,,,,," + amount + ",,,EUR,,,,Tienda ficticia," + id + ",,,,\n";
    }
    @Test public void preservesEncodingAllFieldsAndExactMoney() throws Exception {
        String input = ACCOUNT + ROW.replace("Tienda ficticia", "Café ficticio");
        BankingImport.Parsed p = BankingImport.parse(input.getBytes(Charset.forName("windows-1252")));
        assertEquals("windows-1252", p.encoding); assertEquals(input, p.sourceText);
        assertEquals("Café ficticio", p.records.getJSONObject(0).getString("description"));
        assertEquals("-12.50", p.records.getJSONObject(0).getJSONObject("amount").getString("amount"));
        assertEquals("Detalle adicional", p.records.getJSONObject(0).getString("extendedDescription"));
        assertTrue(p.records.getJSONObject(0).isNull("operationDate"));
        assertEquals("2026-06-01", p.records.getJSONObject(0).getString("bookingDate"));
        assertEquals("2026-06-01", p.first); assertEquals("2026-06-01", p.last);
        assertEquals(2, p.records.getJSONObject(0).getInt("sourceRow"));
        assertEquals(8, p.sourceRows.getJSONObject(1).getJSONArray("cells").length());
    }
    @Test public void csvQuotingBomAndMultilineAreLossless() throws Exception {
        String source = "\ufeff" + ACCOUNT + ROW.replace("Tienda ficticia", "\"Tienda; \"\"Ficticia\"\"\nSucursal\"");
        BankingImport.Parsed p = parse(source);
        assertEquals(source, p.sourceText);
        assertEquals("Tienda; \"Ficticia\"\nSucursal", p.records.getJSONObject(0).getString("description"));
        assertEquals(1, p.records.length());
    }
    @Test public void parsesHeaderlessCardsWithoutDroppingFirstRowOrInventingPaymentDate() throws Exception {
        BankingImport.Parsed p = parse("TIT.\t28/05/2026\tFRA. VENTA\tLiquidado\tTienda ficticia\t-1.234,56 EUR\tCuota fija\n");
        JSONObject r = p.records.getJSONObject(0);
        assertEquals("card", p.productType); assertEquals("2026-05-28", p.first);
        assertEquals("-1234.56", r.getJSONObject("amount").getString("amount"));
        assertEquals("Cuota fija", r.getString("paymentMode")); assertFalse(r.has("paymentDate"));
        assertEquals(1, parse("TIT.\t01/06/2026\tFRA. VENTA\tCargado\tTienda\t-1,00 EUR\n").records.length());
    }
    @Test public void keepsLoanSectionsAndRepeatedLabelsDistinct() throws Exception {
        BankingImport.Parsed p = parse("Detalle del préstamo\nPendiente de pago\t\nRECIBOS PENDIENTES DE PAGO\nIMPORTE\t0,00 EUR\nCOMISIONES Y GASTOS PENDIENTES\nIMPORTE\t0,00 EUR\nCondiciones\t\nINTERÉS NOMINAL\t5,00%\n");
        List<BankingRecords.Entity> e = BankingRecords.imported(p.capture(product("abanca", "loan"), 1));
        assertEquals(3, e.size()); assertNotEquals(e.get(0).id, e.get(1).id);
        assertEquals("5,00%", e.get(2).data.getString("value")); assertEquals("loan_term", e.get(0).type);
    }
    @Test public void importAndWebviewMovementIdsMatchAndKeepDuplicateObservations() throws Exception {
        BankingImport.Parsed p = parse(ACCOUNT + ROW + ROW);
        JSONObject capture = p.capture(product("abanca", "account"), 1);
        List<BankingRecords.Entity> imported = BankingRecords.imported(capture);
        JSONObject web = new JSONObject().put("product", product("abanca", "account"))
                .put("normalized", new JSONObject().put("pageType", "account").put("records", new JSONArray().put(
                        new JSONObject(p.records.getJSONObject(0).toString()).put("operationDate", "2026-06-01"))));
        assertEquals(BankingRecords.abanca(web).get(0).id, imported.get(0).id);
        assertEquals(imported.get(0).id, imported.get(1).id); assertNotEquals(imported.get(0).sourceRow, imported.get(1).sourceRow);
    }
    @Test public void tradeIdentityMatchesNativeAndPreservesExportPrecisionAndMetadata() throws Exception {
        BankingImport.Parsed p = parse(tr("bank-id", "-0.123456789", "2026-06-01T12:30:00.123456Z"));
        List<BankingRecords.Entity> imported = BankingRecords.imported(p.capture(product("trade_republic", "investment_account"), 1));
        JSONObject raw = new JSONObject().put("id", "bank-id").put("title", "Changed title").put("timestamp", "2026-06-01T12:30:00Z");
        JSONObject nativeCapture = new JSONObject().put("product", product("trade_republic", "investment_account"))
                .put("data", new JSONObject().put("transactions", new JSONObject().put("items", new JSONArray().put(raw))));
        assertEquals(BankingRecords.trade(nativeCapture).get(0).id, imported.get(0).id);
        assertEquals("-0.123456789", imported.get(0).data.getJSONObject("amount").getString("amount"));
        assertEquals(23, imported.get(0).data.getJSONObject("exportFields").length());
        assertEquals("2026-06-01T12:30:00.123456Z", imported.get(0).data.getString("occurredAt"));
    }
    @Test public void fileIdentityIgnoresFilenameAndImportTimeButChangesWithContent() throws Exception {
        BankingImport.Parsed p = parse(ACCOUNT + ROW); JSONObject product = product("abanca", "account");
        assertEquals(p.capture(product, 1).getString("captureId"), p.capture(product, 99).getString("captureId"));
        assertNotEquals(p.digest, parse(ACCOUNT + ROW + ROW).digest);
    }
    @Test public void rejectsBadRowsWithoutPartialResult() throws Exception {
        String[] invalid = { ACCOUNT + ROW + ROW.replace("01-06-2026", "31-02-2026"), ACCOUNT + ROW.replace("-12,50", "1,234.56"),
            ACCOUNT + ROW.replace("EUR", "???"), ACCOUNT + ROW + "broken", ACCOUNT + ROW.replace("Tienda ficticia", "\"unterminated"),
            tr("", "1.00", "2026-06-01T12:30:00Z"), tr("id", "1.00", "2026-06-01T99:30:00Z"),
            tr("id", "1.00", "2026-06-01T12:30:00+99:00"), "unknown document", "\n\n" };
        for (String input : invalid) { try { parse(input); fail("Accepted invalid format"); } catch (BankingImport.Invalid expected) { } }
    }
    @Test public void rejectsWrongProductAndOversizedFiles() throws Exception {
        try { parse(ACCOUNT + ROW).capture(product("abanca", "card"), 1); fail(); } catch (BankingImport.Invalid expected) { }
        try { BankingImport.parse(new byte[BankingImport.MAX_BYTES + 1]); fail(); } catch (BankingImport.Invalid expected) { }
    }
    @Test public void rejectsPayloadTooLargeToReadReliablyFromAndroidSqlite() throws Exception {
        BankingImport.Parsed p = parse(ACCOUNT + ROW);
        char[] padding = new char[1024 * 1024]; java.util.Arrays.fill(padding, 'x'); p.sourceText = new String(padding);
        try { p.capture(product("abanca", "account"), 1); fail(); } catch (BankingImport.Invalid expected) { }
    }
    @Test public void formulaLikeTextIsStoredLiterally() throws Exception {
        BankingImport.Parsed p = parse(ACCOUNT + ROW.replace("Tienda ficticia", "=1+1"));
        assertEquals("=1+1", p.records.getJSONObject(0).getString("description"));
    }
}
