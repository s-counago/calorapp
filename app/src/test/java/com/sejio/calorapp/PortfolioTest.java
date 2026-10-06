package com.sejio.calorapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;

public final class PortfolioTest {
    private static JSONObject position(String id, String name, String quantity, String averageBuyIn, String price) throws Exception {
        JSONObject row = new JSONObject().put("instrumentId", id).put("name", name).put("quantity", quantity)
                .put("averageBuyIn", averageBuyIn).put("currency", "EUR");
        if (price != null) row.put("valuationStatus", "VALUED").put("quote", new JSONObject().put("price", price).put("currency", "EUR"));
        else row.put("valuationStatus", "LIMIT");
        return row;
    }

    private static JSONObject reading(long at, boolean valued, JSONObject... rows) throws Exception {
        JSONArray positions = new JSONArray();
        for (JSONObject row : rows) positions.put(row);
        JSONObject reading = new JSONObject().put("capturedAt", at).put("positions", positions);
        if (valued) reading.put("valuationAt", at + 1000);
        return reading;
    }

    @Test public void valuesGainsAndHistoryComeFromStoredReadings() throws Exception {
        JSONArray readings = new JSONArray()
                .put(reading(2_000, true, position("IE00B5BMR087", "Core S&P 500", "1.5", "500", "520"),
                        position("FR0010655746", "IBEX 35", "0.2", "470", "460")))
                .put(reading(1_000, true, position("IE00B5BMR087", "Core S&P 500", "1", "500", "500")))
                // Positions read later without a valuation: the last known price is reused.
                .put(reading(3_000, false, position("IE00B5BMR087", "Core S&P 500", "2", "505", null),
                        position("FR0010655746", "IBEX 35", "0.2", "470", null)));
        Portfolio.View view = Portfolio.from(readings);
        assertEquals(3_000, view.readAt);
        assertEquals(2, view.history.size());
        assertEquals(50_000, view.history.get(0).value);
        assertEquals(78_000 + 9_200, view.history.get(1).value);
        assertEquals(75_000 + 9_400, view.history.get(1).cost);
        Portfolio.Holding sp = view.holdings.get(0);
        assertEquals("Core S&P 500", sp.name);
        assertEquals(104_000, sp.value); // 2 × 520 €, priced at the 2 000 valuation.
        assertEquals(101_000, sp.cost);
        assertEquals(3_000, sp.gain());
        assertEquals(0L, sp.pricedAt); // Never label receipt time as market time.
        assertEquals(3_000L, sp.receivedAt);
        assertEquals(104_000 + 9_200, view.value);
        assertEquals(2, view.holdingHistory.get("IE00B5BMR087").size());
        assertEquals("+3 %", Portfolio.percent(sp.gainRatio()));
    }

    @Test public void foreignPricesAreNotAddedUp() throws Exception {
        JSONObject dollar = position("US0378331005", "Apple", "1", "150", "190");
        dollar.getJSONObject("quote").put("currency", "USD");
        Portfolio.View view = Portfolio.from(new JSONArray().put(reading(1_000, true, dollar)));
        assertFalse(view.holdings.get(0).valued());
        assertEquals(0, view.value);
        assertTrue(view.history.isEmpty());
    }

    private JSONObject daily(String id, String quantity, String cost, String price, String previous, String day) throws Exception {
        JSONObject p = position(id, id, quantity, cost, price);
        long at = java.time.Instant.parse(day + "T10:00:00Z").toEpochMilli();
        p.getJSONObject("quote").put("exchange", "LSX").put("quotedAt", at).put("receivedAt", at + 1000)
                .put("previousCloseAt", at - 86400000L).put("previousClose", previous);
        return p;
    }
    @Test public void aggregateDailyReturnIsWeightedByReferenceValueNotAveragePercent() throws Exception {
        Portfolio.View v = Portfolio.from(new JSONArray().put(reading(1, true,
                daily("A", "1", "80", "110", "100", "2026-10-05"),
                daily("B", "9", "100", "90", "100", "2026-10-05"))));
        assertEquals(-8000, v.dailyGain()); assertEquals(-0.08, v.dailyRatio(), 1e-9);
        assertEquals(-6000, v.gain()); assertEquals(2, v.dailyValued); assertEquals(2, v.gainValued);
    }
    @Test public void missingCostsHaveNoPnlAndDoNotDrawAnInventedCost() throws Exception {
        JSONObject p = daily("A", "1", "80", "110", "100", "2026-10-05"); p.remove("averageBuyIn");
        Portfolio.View v = Portfolio.from(new JSONArray().put(reading(1, true, p)));
        assertEquals(11000, v.value); assertEquals(0, v.gainValued); assertTrue(Double.isNaN(v.gainRatio()));
        assertEquals(-1, v.history.get(0).cost); assertFalse(v.holdings.get(0).gainKnown()); assertEquals(1, v.dailyValued);
    }
    @Test public void differentQuoteSessionsAreNotCombinedAsTodaysReturn() throws Exception {
        Portfolio.View v = Portfolio.from(new JSONArray().put(reading(1, true,
                daily("A", "1", "80", "110", "100", "2026-10-05"),
                daily("B", "9", "100", "90", "100", "2026-10-02"))));
        assertEquals("2026-10-05", v.dailySession); assertEquals(1, v.dailyValued); assertEquals(1000, v.dailyGain());
        assertEquals("2026-10-02", v.holdings.get(0).dailySession);
    }
    @Test public void cachedQuoteKeepsMarketTimeAndReferenceWhenPositionsRefresh() throws Exception {
        JSONObject old = daily("A", "1", "80", "110", "100", "2026-10-05");
        Portfolio.View v = Portfolio.from(new JSONArray().put(reading(1, true, old)).put(reading(2, false, position("A", "A", "2", "85", null))));
        assertEquals(2000, v.dailyGain()); assertEquals(5000, v.gain());
        assertEquals(old.getJSONObject("quote").getLong("quotedAt"), v.holdings.get(0).pricedAt);
    }
    @Test public void partialValuationIsNotDrawnAsAWholePortfolioHistoryPoint() throws Exception {
        Portfolio.View v = Portfolio.from(new JSONArray().put(reading(1, true,
                position("A", "A", "1", "80", "110"), position("B", "B", "1", "100", null))));
        assertEquals(1, v.valued); assertEquals(1, v.gainValued); assertTrue(v.history.isEmpty());
        assertEquals(1, v.holdingHistory.get("A").size());
    }

    @Test public void switchingAccountsCannotReuseAnotherAccountsQuotes() throws Exception {
        Portfolio.View v = Portfolio.from(new JSONArray().put(reading(1, true, position("A", "A", "1", "80", "110")).put("accountId", "first"))
                .put(reading(2, false, position("A", "A", "2", "85", null)).put("accountId", "second")));
        assertEquals(0, v.valued); assertTrue(v.history.isEmpty());
    }

    @Test public void numbersReadInSpanish() {
        assertEquals("0,9265", Portfolio.quantity(new BigDecimal("0.926500")));
        assertEquals("471,65 €", Portfolio.price(new BigDecimal("471.65")));
        assertEquals("1.234,50 €", Portfolio.price(new BigDecimal("1234.5")));
        assertEquals("−2,1 %", Portfolio.percent(-0.0212));
    }
}
