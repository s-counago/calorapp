package com.sejio.calorapp.trade;

import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.Assert.*;

public class TradePnlTest {
    private long time(String iso) { return Instant.parse(iso).toEpochMilli(); }
    private JSONObject row(String q, String buy, String last, String pre) throws Exception {
        JSONObject quote = new JSONObject().put("price", last).put("currency", "EUR").put("exchange", "LSX")
                .put("quotedAt", time("2026-10-05T10:00:00Z")).put("previousCloseAt", time("2026-10-02T19:00:00Z"));
        if (pre != null) quote.put("previousClose", pre);
        return new JSONObject().put("quantity", q).put("averageBuyIn", buy).put("currency", "EUR").put("valuationStatus", "VALUED").put("quote", quote);
    }
    private void decimal(String expected, JSONObject obj, String key) throws Exception {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(obj.getString(key))));
    }
    @Test public void exactTotalAndDailyWithFractionalSharesAndWeekendReference() throws Exception {
        JSONObject pnl = TradePnl.calculate(row("0.125", "80", "110", "100"));
        decimal("3.75", pnl.getJSONObject("total"), "amount"); decimal("37.5", pnl.getJSONObject("total"), "percent");
        decimal("1.25", pnl.getJSONObject("daily"), "amount"); decimal("10", pnl.getJSONObject("daily"), "percent");
        assertEquals("2026-10-05", pnl.getJSONObject("daily").getString("sessionDate"));
    }
    @Test public void lossesZeroChangesAndFreePositions() throws Exception {
        JSONObject pnl = TradePnl.calculate(row("2", "100", "90", "90"));
        decimal("-20", pnl.getJSONObject("total"), "amount"); decimal("-10", pnl.getJSONObject("total"), "percent");
        decimal("0", pnl.getJSONObject("daily"), "amount"); decimal("0", pnl.getJSONObject("daily"), "percent");
        pnl = TradePnl.calculate(row("2", "0", "90", "100"));
        decimal("180", pnl.getJSONObject("total"), "amount"); assertTrue(pnl.getJSONObject("total").isNull("percent"));
    }
    @Test public void missingAverageOrMismatchedCurrencyDoesNotBecomeZeroGain() throws Exception {
        JSONObject r = row("2", "100", "110", "100"); r.remove("averageBuyIn");
        assertFalse(TradePnl.calculate(r).has("total")); assertTrue(TradePnl.calculate(r).has("daily"));
        r.put("averageBuyIn", "100").put("currency", "USD"); assertFalse(TradePnl.calculate(r).has("total"));
        r.put("currency", ""); assertTrue(TradePnl.calculate(r).has("total"));
        r.getJSONObject("quote").put("exchange", "UNKNOWN"); assertFalse(TradePnl.calculate(r).has("total"));
    }
    @Test public void missingZeroSameDayFutureOrVeryOldReferenceDoesNotFabricateDailyReturn() throws Exception {
        assertFalse(TradePnl.calculate(row("1", "100", "110", null)).has("daily"));
        assertFalse(TradePnl.calculate(row("1", "100", "110", "0")).has("daily"));
        for (long before : new long[]{0, time("2026-10-05T09:00:00Z"), time("2026-10-06T10:00:00Z"), time("2026-09-01T10:00:00Z")}) {
            JSONObject r = row("1", "100", "110", "100"); r.getJSONObject("quote").put("previousCloseAt", before);
            assertFalse(TradePnl.calculate(r).has("daily")); assertTrue(TradePnl.calculate(r).has("total"));
        }
    }
    @Test public void dailyIsAnEstimateForCurrentQuantityNotAccountCashFlowPerformance() throws Exception {
        JSONObject small = TradePnl.calculate(row("1", "100", "110", "100")).getJSONObject("daily");
        JSONObject big = TradePnl.calculate(row("5", "105", "110", "100")).getJSONObject("daily");
        decimal("10", small, "amount"); decimal("50", big, "amount"); decimal("10", big, "percent");
        assertEquals("ticker_pre_current_quantity", big.getString("basis"));
    }
    @Test public void rejectsNegativeQuantityAndUnknownQuoteCurrency() throws Exception {
        assertEquals(1, TradePnl.calculate(row("-1", "100", "110", "100")).length());
        JSONObject r = row("1", "100", "110", "100"); r.getJSONObject("quote").put("currency", "");
        assertFalse(TradePnl.calculate(r).has("total")); assertFalse(TradePnl.calculate(r).has("daily"));
    }
    @Test public void sessionUsesBerlinCalendarIncludingDstNotElapsed24Hours() {
        assertEquals("2026-10-06", TradePnl.session(time("2026-10-05T22:30:00Z")));
        assertEquals("2026-10-25", TradePnl.session(time("2026-10-25T00:30:00Z")));
    }
}
