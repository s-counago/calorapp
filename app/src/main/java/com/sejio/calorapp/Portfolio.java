package com.sejio.calorapp;

import com.sejio.calorapp.trade.TradePnl;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Euro valuation and price PnL of open positions, with explicit coverage and quote session dates. */
final class Portfolio {
    static final class Holding {
        String id, name, currency, status = "", dailySession = "";
        BigDecimal quantity = BigDecimal.ZERO, averageBuyIn, price;
        BigDecimal totalAmount, totalBase, dailyAmount, dailyBase;
        long cost = -1, value = -1, pricedAt, receivedAt, referenceAt;
        boolean valued() { return value >= 0; }
        boolean gainKnown() { return totalAmount != null; }
        boolean dailyKnown() { return dailyAmount != null; }
        long gain() { return gainKnown() ? cents(totalAmount) : 0; }
        double gainRatio() { return ratio(totalAmount, totalBase); }
        long dailyGain() { return dailyKnown() ? cents(dailyAmount) : 0; }
        double dailyRatio() { return ratio(dailyAmount, dailyBase); }
    }
    static final class Point {
        final long at, value, cost;
        Point(long at, long value, long cost) { this.at = at; this.value = value; this.cost = cost; }
    }
    static final class View {
        final List<Holding> holdings = new ArrayList<>();
        final List<Point> history = new ArrayList<>();
        final Map<String, List<long[]>> holdingHistory = new LinkedHashMap<>();
        long readAt, valuedAt, value, cost;
        int valued, gainValued, dailyValued;
        String dailySession = "";
        BigDecimal totalAmount = BigDecimal.ZERO, totalBase = BigDecimal.ZERO;
        BigDecimal dailyAmount = BigDecimal.ZERO, dailyBase = BigDecimal.ZERO;
        boolean empty() { return holdings.isEmpty(); }
        long gain() { return cents(totalAmount); }
        double gainRatio() { return ratio(totalAmount, totalBase); }
        long dailyGain() { return cents(dailyAmount); }
        double dailyRatio() { return ratio(dailyAmount, dailyBase); }
        long marketSinceStart() {
            if (history.size() < 2) return 0;
            Point first = history.get(0), last = history.get(history.size() - 1);
            return first.cost < 0 || last.cost < 0 ? 0 : (last.value - last.cost) - (first.value - first.cost);
        }
    }
    static View from(JSONArray readings) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < readings.length(); i++) {
            JSONObject r = readings.optJSONObject(i);
            if (r != null && r.optJSONArray("positions") != null && r.optLong("capturedAt") > 0) list.add(r);
        }
        Collections.sort(list, (a, b) -> Long.compare(a.optLong("capturedAt"), b.optLong("capturedAt")));
        View view = new View(); if (list.isEmpty()) return view;
        JSONObject latest = list.get(list.size() - 1);
        String account = latest.optString("accountId");
        // A previous connection/account cannot supply quotes or performance history to another account.
        Map<String, JSONObject> lastQuote = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (JSONObject reading : list) {
            if (!account.equals(reading.optString("accountId"))) continue;
            JSONArray positions = reading.optJSONArray("positions");
            long at = reading.optLong("valuationAt", reading.optLong("capturedAt"));
            long value = 0, cost = 0; int priced = 0; boolean allCosts = true;
            for (int i = 0; i < positions.length(); i++) {
                JSONObject row = positions.optJSONObject(i); if (row == null) continue;
                String id = row.optString("instrumentId");
                if (!row.optString("name").isEmpty()) names.put(id, row.optString("name"));
                JSONObject quote = euroQuote(row); BigDecimal units = decimal(row.optString("quantity"));
                if (quote == null || units == null || units.signum() < 0) continue;
                try {
                    JSONObject copy = new JSONObject(quote.toString());
                    if (copy.optLong("receivedAt") <= 0) copy.put("receivedAt", at);
                    lastQuote.put(id, copy);
                    long rowValue = cents(units.multiply(decimal(quote.optString("price")))), rowCost = cost(row, units, quote);
                    value = Math.addExact(value, rowValue); if (rowCost >= 0) cost = Math.addExact(cost, rowCost); else allCosts = false;
                    priced++;
                    List<long[]> series = view.holdingHistory.get(id);
                    if (series == null) { series = new ArrayList<>(); view.holdingHistory.put(id, series); }
                    series.add(new long[]{at, rowValue, rowCost});
                } catch (Exception invalid) { allCosts = false; }
            }
            if (priced > 0 && priced == positions.length()) view.history.add(new Point(at, value, allCosts ? cost : -1));
        }
        view.readAt = latest.optLong("capturedAt");
        JSONArray positions = latest.optJSONArray("positions");
        for (int i = 0; i < positions.length(); i++) {
            JSONObject row = positions.optJSONObject(i); if (row == null) continue;
            Holding h = new Holding(); h.id = row.optString("instrumentId");
            h.name = row.optString("name", names.containsKey(h.id) ? names.get(h.id) : h.id);
            if (h.name.isEmpty()) h.name = h.id;
            h.currency = row.optString("currency");
            BigDecimal units = decimal(row.optString("quantity"));
            if (units != null && units.signum() >= 0) h.quantity = units;
            h.averageBuyIn = decimal(row.optString("averageBuyIn"));
            h.status = row.optString("valuationStatus");
            JSONObject quote = lastQuote.get(h.id);
            try {
                h.cost = units == null || units.signum() < 0 ? -1 : cost(row, units, quote);
                if (quote != null && units != null && units.signum() >= 0) {
                    h.price = decimal(quote.optString("price")); h.value = cents(units.multiply(h.price));
                    h.pricedAt = quote.optLong("quotedAt"); h.receivedAt = quote.optLong("receivedAt");
                    JSONObject valuedRow = new JSONObject(row.toString()).put("quote", quote).put("valuationStatus", "VALUED");
                    JSONObject pnl = TradePnl.calculate(valuedRow), total = pnl.optJSONObject("total"), daily = pnl.optJSONObject("daily");
                    if (total != null) { h.totalAmount = decimal(total.getString("amount")); h.totalBase = decimal(total.getString("basisAmount")); }
                    if (daily != null) {
                        h.dailyAmount = decimal(daily.getString("amount")); h.dailyBase = decimal(daily.getString("basisAmount"));
                        h.dailySession = daily.getString("sessionDate"); h.referenceAt = daily.getLong("referenceAt");
                        if (h.dailySession.compareTo(view.dailySession) > 0) view.dailySession = h.dailySession;
                    }
                }
            } catch (Exception invalid) { h.value = -1; h.totalAmount = null; h.dailyAmount = null; }
            view.holdings.add(h);
            if (h.valued()) { view.value += h.value; view.valued++; view.valuedAt = Math.max(view.valuedAt, h.receivedAt); }
            if (h.gainKnown()) { view.gainValued++; view.totalAmount = view.totalAmount.add(h.totalAmount); view.totalBase = view.totalBase.add(h.totalBase); }
        }
        view.cost = cents(view.totalBase);
        for (Holding h : view.holdings) if (h.dailyKnown() && h.dailySession.equals(view.dailySession)) {
            view.dailyValued++; view.dailyAmount = view.dailyAmount.add(h.dailyAmount); view.dailyBase = view.dailyBase.add(h.dailyBase);
        }
        Collections.sort(view.holdings, (a, b) -> Long.compare(Math.max(b.value, b.cost), Math.max(a.value, a.cost)));
        return view;
    }
    private static JSONObject euroQuote(JSONObject row) {
        JSONObject quote = row.optJSONObject("quote");
        if (quote == null || !"VALUED".equals(row.optString("valuationStatus")) || !"EUR".equals(quote.optString("currency"))) return null;
        BigDecimal price = decimal(quote.optString("price")); return price != null && price.signum() > 0 ? quote : null;
    }
    private static long cost(JSONObject row, BigDecimal quantity, JSONObject quote) {
        BigDecimal average = decimal(row.optString("averageBuyIn")); String currency = row.optString("currency");
        boolean euros = currency.equals("EUR") || (currency.isEmpty() && quote != null && "LSX".equals(quote.optString("exchange")) && "EUR".equals(quote.optString("currency")));
        if (average == null || average.signum() < 0 || !euros) return -1;
        try { return cents(quantity.multiply(average)); } catch (Exception invalid) { return -1; }
    }
    private static BigDecimal decimal(String value) { try { return new BigDecimal(value); } catch (Exception error) { return null; } }
    private static double ratio(BigDecimal amount, BigDecimal base) {
        return amount == null || base == null || base.signum() <= 0 ? Double.NaN : amount.divide(base, 12, RoundingMode.HALF_EVEN).doubleValue();
    }
    static long cents(BigDecimal euros) { return euros.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact(); }

    /** "0,9265" — quantities with up to four decimals, Spanish style. */
    static String quantity(BigDecimal value) {
        String plain = value.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
        return plain.replace('.', ',');
    }

    /** "471,65 €" for prices, which may need more than cents. */
    static String price(BigDecimal value) {
        BigDecimal scaled = value.setScale(value.abs().compareTo(BigDecimal.TEN) < 0 ? 3 : 2, RoundingMode.HALF_EVEN);
        String[] parts = scaled.toPlainString().split("\\.");
        StringBuilder digits = new StringBuilder(parts[0].replace("-", ""));
        for (int i = digits.length() - 3; i > 0; i -= 3) digits.insert(i, '.');
        return (scaled.signum() < 0 ? "−" : "") + digits + (parts.length > 1 ? "," + parts[1] : "") + " €";
    }

    static String percent(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) return "—";
        double value = Math.round(ratio * 1000) / 10.0;
        String text = (value > 0 ? "+" : value < 0 ? "−" : "") + String.valueOf(Math.abs(value)).replace('.', ',');
        return (text.endsWith(",0") ? text.substring(0, text.length() - 2) : text) + " %";
    }

    private Portfolio() { }
}
