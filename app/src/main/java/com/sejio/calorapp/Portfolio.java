package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trade Republic holdings from the stored portfolio readings: what is held, what it cost, what it was worth
 * at each valuation. Only euro prices are added up; prices are the last ones received, never a sale price.
 */
final class Portfolio {
    static final class Holding {
        String id, name, currency;
        BigDecimal quantity = BigDecimal.ZERO, averageBuyIn, price;
        /** Cents; -1 when unknown. */
        long cost = -1, value = -1;
        /** When the price was received; older than the reading when the latest one had no quote for it. */
        long pricedAt;
        String status = "";

        boolean valued() { return value >= 0; }
        long gain() { return valued() && cost >= 0 ? value - cost : 0; }
        double gainRatio() { return valued() && cost > 0 ? (value - cost) / (double) cost : 0; }
    }

    /** One valuation of the whole portfolio. */
    static final class Point {
        final long at, value, cost;
        Point(long at, long value, long cost) { this.at = at; this.value = value; this.cost = cost; }
    }

    static final class View {
        final List<Holding> holdings = new ArrayList<>();
        final List<Point> history = new ArrayList<>();
        /** Per instrument: {at, value, cost} for every valuation that priced it. */
        final Map<String, List<long[]>> holdingHistory = new LinkedHashMap<>();
        long readAt, valuedAt, value, cost;
        int valued;

        boolean empty() { return holdings.isEmpty(); }
        long gain() { return value - cost; }
        double gainRatio() { return cost > 0 ? (value - cost) / (double) cost : 0; }
        /** Value added by the market since the first valuation, beyond what was put in. */
        long marketSinceStart() {
            if (history.size() < 2) return 0;
            Point first = history.get(0), last = history.get(history.size() - 1);
            return (last.value - last.cost) - (first.value - first.cost);
        }
    }

    /** {@code readings}: the {@code data} of each stored portfolio capture, in any order. */
    static View from(JSONArray readings) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < readings.length(); i++) {
            JSONObject reading = readings.optJSONObject(i);
            if (reading != null && reading.optJSONArray("positions") != null && reading.optLong("capturedAt") > 0) list.add(reading);
        }
        Collections.sort(list, (a, b) -> Long.compare(a.optLong("capturedAt"), b.optLong("capturedAt")));
        View view = new View();
        Map<String, Object[]> lastPrice = new LinkedHashMap<>(); // id -> {price, currency, at}
        for (JSONObject reading : list) {
            JSONArray positions = reading.optJSONArray("positions");
            long at = reading.optLong("valuationAt", reading.optLong("capturedAt"));
            long value = 0, cost = 0;
            int priced = 0;
            for (int i = 0; i < positions.length(); i++) {
                JSONObject row = positions.optJSONObject(i);
                if (row == null) continue;
                BigDecimal price = euroPrice(row);
                if (price == null) continue;
                String id = row.optString("instrumentId");
                BigDecimal quantity = decimal(row.optString("quantity"));
                long rowValue = cents(quantity.multiply(price)), rowCost = cost(row, quantity);
                lastPrice.put(id, new Object[]{price, "EUR", at});
                value += rowValue;
                if (rowCost >= 0) cost += rowCost; else cost += rowValue; // Unknown cost counts as neither gain nor loss.
                priced++;
                List<long[]> series = view.holdingHistory.get(id);
                if (series == null) { series = new ArrayList<>(); view.holdingHistory.put(id, series); }
                series.add(new long[]{at, rowValue, rowCost});
            }
            if (priced > 0) view.history.add(new Point(at, value, cost));
        }
        if (list.isEmpty()) return view;
        JSONObject latest = list.get(list.size() - 1);
        view.readAt = latest.optLong("capturedAt");
        JSONArray positions = latest.optJSONArray("positions");
        for (int i = 0; i < positions.length(); i++) {
            JSONObject row = positions.optJSONObject(i);
            if (row == null) continue;
            Holding holding = new Holding();
            holding.id = row.optString("instrumentId");
            holding.name = row.optString("name", holding.id);
            if (holding.name.isEmpty()) holding.name = holding.id;
            holding.currency = row.optString("currency");
            holding.quantity = decimal(row.optString("quantity"));
            if (row.has("averageBuyIn")) holding.averageBuyIn = decimal(row.optString("averageBuyIn"));
            holding.cost = cost(row, holding.quantity);
            holding.status = row.optString("valuationStatus");
            Object[] known = lastPrice.get(holding.id);
            if (known != null) {
                holding.price = (BigDecimal) known[0];
                holding.pricedAt = (Long) known[2];
                holding.value = cents(holding.quantity.multiply(holding.price));
            }
            view.holdings.add(holding);
            if (holding.valued()) {
                view.value += holding.value;
                view.cost += holding.cost >= 0 ? holding.cost : holding.value;
                view.valued++;
                view.valuedAt = Math.max(view.valuedAt, holding.pricedAt);
            }
        }
        Collections.sort(view.holdings, (a, b) -> Long.compare(Math.max(b.value, b.cost), Math.max(a.value, a.cost)));
        return view;
    }

    private static BigDecimal euroPrice(JSONObject row) {
        JSONObject quote = row.optJSONObject("quote");
        if (quote == null || !"VALUED".equals(row.optString("valuationStatus")) || !"EUR".equals(quote.optString("currency"))) return null;
        try { return new BigDecimal(quote.getString("price")); } catch (Exception error) { return null; }
    }

    /** Average buy-in times quantity, when the buy-in is in euros or of unstated currency in a euro account. */
    private static long cost(JSONObject row, BigDecimal quantity) {
        String average = row.optString("averageBuyIn"), currency = row.optString("currency");
        if (average.isEmpty() || !(currency.isEmpty() || currency.equals("EUR"))) return -1;
        try { return cents(quantity.multiply(new BigDecimal(average))); } catch (Exception error) { return -1; }
    }

    private static BigDecimal decimal(String value) {
        try { return new BigDecimal(value); } catch (Exception error) { return BigDecimal.ZERO; }
    }

    static long cents(BigDecimal euros) { return euros.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValue(); }

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
        double value = Math.round(ratio * 1000) / 10.0;
        String text = (value > 0 ? "+" : value < 0 ? "−" : "") + String.valueOf(Math.abs(value)).replace('.', ',');
        return (text.endsWith(",0") ? text.substring(0, text.length() - 2) : text) + " %";
    }

    private Portfolio() { }
}
