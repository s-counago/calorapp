package com.sejio.calorapp.trade;

import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Price-only estimates for currently held units. No realized gains, flows, fees or dividend assumptions. */
public final class TradePnl {
    public static JSONObject calculate(JSONObject row) throws Exception {
        JSONObject result = new JSONObject().put("version", 1);
        JSONObject quote = row.optJSONObject("quote");
        if (quote == null || !"VALUED".equals(row.optString("valuationStatus"))) return result;
        String currency = quote.optString("currency");
        BigDecimal quantity = number(row, "quantity"), price = number(quote, "price");
        if (!currency.matches("[A-Z]{3}") || quantity == null || quantity.signum() < 0 || price == null || price.signum() <= 0) return result;
        BigDecimal average = number(row, "averageBuyIn");
        String costCurrency = row.optString("currency"), costBasis = "position_currency";
        // Compact portfolio often omits its buy-in currency. Restrict this convention to LSX euro unit prices.
        if (costCurrency.isEmpty() && "LSX".equals(quote.optString("exchange")) && currency.equals("EUR")) {
            costCurrency = "EUR"; costBasis = "lsx_eur_buy_in_convention";
        }
        if (average != null && average.signum() >= 0 && currency.equals(costCurrency))
            result.put("total", change(quantity, price, average, currency).put("basis", "average_buy_in")
                    .put("costCurrencyBasis", costBasis));
        BigDecimal previous = number(quote, "previousClose");
        long at = quote.optLong("quotedAt"), before = quote.optLong("previousCloseAt");
        if (previous != null && previous.signum() > 0 && at > 0 && before > 0 && before < at
                && !session(at).equals(session(before)) && at - before <= 7L * 24 * 60 * 60 * 1000)
            result.put("daily", change(quantity, price, previous, currency).put("basis", "ticker_pre_current_quantity")
                    .put("quotedAt", at).put("referenceAt", before).put("sessionDate", session(at)));
        return result;
    }
    private static JSONObject change(BigDecimal units, BigDecimal price, BigDecimal basis, String currency) throws Exception {
        BigDecimal base = units.multiply(basis), amount = units.multiply(price.subtract(basis));
        return new JSONObject().put("amount", amount.toPlainString()).put("basisAmount", base.toPlainString()).put("currency", currency)
                .put("percent", base.signum() > 0 ? amount.multiply(new BigDecimal("100")).divide(base, 12, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() : JSONObject.NULL);
    }
    private static BigDecimal number(JSONObject data, String key) {
        try { return new BigDecimal(TradeRepublicClient.decimal(data.get(key))); } catch (Exception invalid) { return null; }
    }
    public static String session(long at) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        day.setTimeZone(TimeZone.getTimeZone("Europe/Berlin")); return day.format(new Date(at));
    }
    private TradePnl() { }
}
