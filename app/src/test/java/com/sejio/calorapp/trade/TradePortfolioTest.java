package com.sejio.calorapp.trade;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TradePortfolioTest {
    private JSONObject portfolio(JSONObject row) throws Exception {
        return new JSONObject().put("categories", new JSONArray().put(new JSONObject().put("positions", new JSONArray().put(row))));
    }

    @Test public void fractionalHoldingsRetainCostWithoutInventingCurrencyOrQuotes() throws Exception {
        JSONArray rows = TradePortfolio.normalize(portfolio(new JSONObject().put("isin", "TEST_ISIN")
                .put("netSize", "0.123456789").put("averageBuyIn", "123.45")));
        JSONObject row = rows.getJSONObject(0);
        assertEquals("TEST_ISIN", row.getString("instrumentId"));
        assertEquals("0.123456789", row.getString("quantity"));
        assertEquals("123.45", row.getString("averageBuyIn"));
        assertEquals("", row.getString("currency"));
        assertFalse(row.has("price")); assertFalse(row.has("value"));
    }

    @Test public void emptyPortfolioIsDifferentFromUnknownSchema() throws Exception {
        assertEquals(0, TradePortfolio.normalize(new JSONObject().put("categories", new JSONArray())).length());
        try { TradePortfolio.normalize(new JSONObject().put("someNewField", new JSONArray())); fail(); }
        catch (TradeException e) { assertEquals("PROTOCOL", e.code); }
    }

    @Test public void alternateIdAndMissingCostRemainUsable() throws Exception {
        JSONObject row = TradePortfolio.normalize(portfolio(new JSONObject().put("instrumentId", "TEST_OTHER")
                .put("netSize", 2).put("currencyId", "USD"))).getJSONObject(0);
        assertEquals("TEST_OTHER", row.getString("instrumentId"));
        assertEquals("USD", row.getString("currency")); assertFalse(row.has("averageBuyIn"));
    }

    @Test public void missingQuantityCannotBeMistakenForZero() throws Exception {
        try { TradePortfolio.normalize(portfolio(new JSONObject().put("isin", "TEST"))); fail(); }
        catch (TradeException e) { assertEquals("PROTOCOL", e.code); }
    }

    @Test public void excessivePositionCountIsRejectedInsteadOfSilentlyTruncated() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 501; i++) rows.put(new JSONObject().put("isin", "TEST_" + i).put("netSize", 1));
        try { TradePortfolio.normalize(new JSONObject().put("categories", new JSONArray().put(new JSONObject().put("positions", rows)))); fail(); }
        catch (TradeException e) { assertEquals("PROTOCOL", e.code); }
    }
}
