package com.sejio.calorapp.trade;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TradeValuationTest {
    private final long now = System.currentTimeMillis();
    private JSONObject row(String id, String quantity) throws Exception {
        return new JSONObject().put("instrumentId", id).put("quantity", quantity);
    }
    private JSONObject instrument(String id) throws Exception {
        return new JSONObject().put("isin", id).put("typeId", "etf").put("shortName", "Example ETF")
                .put("exchangeIds", new JSONArray().put("LSX").put("OTHER"))
                .put("fundInfo", new JSONObject().put("currency", "USD"));
    }
    private JSONObject ticker(String id, String currency, String price) throws Exception {
        JSONObject result = new JSONObject().put("isin", id).put("exchangeId", "LSX")
                .put("last", new JSONObject().put("price", price).put("time", now - 86400000));
        if (currency != null) result.put("currencyId", currency);
        return result;
    }
    private class Source implements TradeValuation.Source {
        int instruments, quotes;
        String currency = "EUR";
        @Override public Object instrument(String id) throws Exception { instruments++; return TradeValuationTest.this.instrument(id); }
        @Override public Object ticker(String id, String exchange) throws Exception {
            assertEquals("LSX", exchange); quotes++; return TradeValuationTest.this.ticker(id, currency, "123.45");
        }
    }

    @Test public void fractionalValuesAreExactAndDuplicateHoldingsUseOneQuote() throws Exception {
        JSONArray rows = new JSONArray().put(row("TEST", "0.123456789")).put(row("TEST", "2"));
        Source source = new Source();
        TradeValuation.collect(rows, null, source, now);
        assertEquals(1, source.instruments); assertEquals(1, source.quotes);
        assertEquals("15.24074060205", rows.getJSONObject(0).getString("estimatedValue"));
        JSONObject totals = TradeValuation.totals(rows);
        assertEquals("262.14074060205", totals.getJSONObject("byCurrency").getString("EUR"));
        assertTrue(totals.getBoolean("complete"));
        assertEquals(now - 86400000, rows.getJSONObject(0).getJSONObject("quote").getLong("quotedAt"));
    }

    @Test public void unknownVenueDoesNotBorrowFundOrPositionCurrency() throws Exception {
        JSONArray rows = new JSONArray().put(row("TEST", "1").put("currency", "GBP"));
        Source source = new Source() {
            @Override public Object instrument(String id) throws Exception { instruments++; return TradeValuationTest.this.instrument(id).put("exchangeIds", new JSONArray().put("UNKNOWN")); }
            @Override public Object ticker(String id, String exchange) throws Exception { quotes++; return TradeValuationTest.this.ticker(id, null, "123.45").put("exchangeId", "UNKNOWN"); }
        };
        TradeValuation.collect(rows, null, source, now);
        JSONObject result = rows.getJSONObject(0);
        assertEquals("UNKNOWN_CURRENCY", result.getString("valuationStatus"));
        assertEquals("123.45", result.getString("estimatedValue"));
        assertEquals("", result.getJSONObject("quote").getString("currency"));
        assertFalse(TradeValuation.totals(rows).getBoolean("complete"));
        assertEquals(0, TradeValuation.totals(rows).getJSONObject("byCurrency").length());
    }

    @Test public void lsxUnitQuotesUseExplicitVenueConventionWithoutFundCurrency() throws Exception {
        Source source = new Source(); source.currency = null;
        JSONArray rows = new JSONArray().put(row("TEST", "2").put("averageBuyIn", "100"));
        TradeValuation.collect(rows, null, source, now);
        JSONObject result = rows.getJSONObject(0);
        assertEquals("EUR", result.getJSONObject("quote").getString("currency"));
        assertEquals("lsx_eur_unit_quote_convention", result.getJSONObject("quote").getString("currencyBasis"));
        assertEquals("46.90", result.getJSONObject("pnl").getJSONObject("total").getString("amount"));
        assertTrue(TradeValuation.totals(rows).getBoolean("complete"));
    }
    @Test public void dailyReferenceKeepsItsOwnPriceTimeAndCurrencyChecks() throws Exception {
        JSONObject raw = ticker("TEST", "EUR", "110").put("pre", new JSONObject().put("price", "100").put("time", now - 2 * 86400000L));
        JSONObject result = TradeValuation.quote("TEST", "LSX", raw, now);
        assertEquals("100", result.getString("previousClose"));
        assertEquals(now - 2 * 86400000L, result.getLong("previousCloseAt"));
        raw.getJSONObject("pre").put("currency", "USD");
        try { TradeValuation.quote("TEST", "LSX", raw, now); fail(); } catch (TradeException expected) { assertEquals("PROTOCOL", expected.code); }
    }

    @Test public void freshMetadataIsReusedButQuoteIsRequestedAgain() throws Exception {
        Source source = new Source();
        JSONObject cache = TradeValuation.collect(new JSONArray().put(row("TEST", "1")), null, source, now);
        TradeValuation.collect(new JSONArray().put(row("TEST", "2")), cache, source, now + 1000);
        assertEquals(1, source.instruments); assertEquals(2, source.quotes);
        TradeValuation.collect(new JSONArray().put(row("TEST", "2")), cache, source, now + TradeValuation.METADATA_AGE + 1);
        assertEquals(2, source.instruments);
    }

    @Test public void requestLimitIsVisibleAndNoExtraInstrumentsAreContacted() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 22; i++) rows.put(row("TEST_" + i, "1"));
        Source source = new Source(); TradeValuation.collect(rows, null, source, now);
        assertEquals(20, source.instruments); assertEquals(20, source.quotes);
        assertEquals("LIMIT", rows.getJSONObject(20).getString("valuationStatus"));
        assertFalse(rows.getJSONObject(20).has("estimatedValue"));
        assertFalse(TradeValuation.totals(rows).getBoolean("complete"));
    }

    @Test public void unsupportedBondDoesNotRequestTickerOrGetValued() throws Exception {
        Source source = new Source() {
            @Override public Object instrument(String id) throws Exception { return TradeValuationTest.this.instrument(id).put("typeId", "bond"); }
        };
        JSONArray rows = new JSONArray().put(row("BOND", "1000"));
        TradeValuation.collect(rows, null, source, now);
        assertEquals(0, source.quotes); assertEquals("UNSUPPORTED_TYPE", rows.getJSONObject(0).getString("valuationStatus"));
        assertFalse(rows.getJSONObject(0).has("estimatedValue"));
    }

    @Test public void missingLastPriceIsNotZeroAndDoesNotUseAskInstead() throws Exception {
        Source source = new Source() {
            @Override public Object ticker(String id, String exchange) throws Exception {
                return new JSONObject().put("ask", new JSONObject().put("price", "999"));
            }
        };
        JSONArray rows = new JSONArray().put(row("TEST", "1"));
        TradeValuation.collect(rows, null, source, now);
        assertEquals("NO_PRICE", rows.getJSONObject(0).getString("valuationStatus"));
        assertFalse(rows.getJSONObject(0).has("estimatedValue"));
    }

    @Test public void currencyTotalsAreSeparateWithoutFxAssumptions() throws Exception {
        Source source = new Source() {
            @Override public Object ticker(String id, String exchange) throws Exception {
                return TradeValuationTest.this.ticker(id, id.equals("US") ? "USD" : "EUR", "10");
            }
        };
        JSONArray rows = new JSONArray().put(row("US", "2")).put(row("EU", "3"));
        TradeValuation.collect(rows, null, source, now);
        JSONObject totals = TradeValuation.totals(rows).getJSONObject("byCurrency");
        assertEquals("20", totals.getString("USD")); assertEquals("30", totals.getString("EUR"));
    }

    @Test public void mismatchedInstrumentOrVenueIsRejected() throws Exception {
        try { TradeValuation.quote("OTHER", "LSX", ticker("TEST", "EUR", "10"), now); fail(); }
        catch (TradeException expected) { assertEquals("PROTOCOL", expected.code); }
        try { TradeValuation.quote("TEST", "OTHER", ticker("TEST", "EUR", "10"), now); fail(); }
        catch (TradeException expected) { assertEquals("PROTOCOL", expected.code); }
    }

    @Test public void invalidOrMissingTimestampDoesNotBecomeFetchTimestamp() throws Exception {
        JSONObject raw = ticker("TEST", "EUR", "10"); raw.getJSONObject("last").remove("time");
        JSONObject parsed = TradeValuation.quote("TEST", "LSX", raw, now);
        assertEquals(0, parsed.getLong("quotedAt")); assertEquals(now, parsed.getLong("receivedAt"));
        raw.getJSONObject("last").put("time", now + 86400000);
        assertEquals(0, TradeValuation.quote("TEST", "LSX", raw, now).getLong("quotedAt"));
    }
}
