package com.sejio.calorapp.trade;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Manual, one-shot quotes. Never infer quote currency from the fund or cash account. */
final class TradeValuation {
    static final int MAX_INSTRUMENTS = 20;
    static final long METADATA_AGE = TimeUnit.DAYS.toMillis(1);
    interface Source {
        Object instrument(String id) throws Exception;
        Object ticker(String id, String exchange) throws Exception;
        default void diagnostic(String message) { }
    }

    static JSONObject collect(JSONArray positions, JSONObject oldCache, Source source, long now) throws Exception {
        Map<String, JSONObject> fetched = new LinkedHashMap<>();
        JSONObject cache = new JSONObject();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (int i = 0; i < positions.length(); i++) {
            JSONObject row = positions.getJSONObject(i);
            String id = row.getString("instrumentId");
            source.diagnostic("Instrumento " + (i + 1));
            JSONObject info = fetched.get(id);
            if (info == null) {
                info = new JSONObject();
                if (fetched.size() >= MAX_INSTRUMENTS || System.nanoTime() >= deadline) {
                    row.put("valuationStatus", "LIMIT"); continue;
                }
                JSONObject metadata = oldCache == null ? null : oldCache.optJSONObject(id);
                long age = metadata == null ? -1 : now - metadata.optLong("fetchedAt");
                if (age < 0 || age > METADATA_AGE || metadata.optInt("schema") != 3) {
                    source.diagnostic("Consultando metadatos");
                    Object rawMetadata = source.instrument(id);
                    metadata = metadata(id, rawMetadata, now);
                } else source.diagnostic("Metadatos en caché");
                source.diagnostic(metadata.getString("diagnostic"));
                cache.put(id, metadata);
                info.put("name", metadata.optString("name", id)).put("exchange", metadata.optString("exchange"));
                if (!metadata.optBoolean("unitPrice")) info.put("valuationStatus", "UNSUPPORTED_TYPE");
                else if (metadata.optString("exchange").isEmpty()) info.put("valuationStatus", "NO_MARKET");
                else if (System.nanoTime() >= deadline) info.put("valuationStatus", "LIMIT");
                else {
                    source.diagnostic("Consultando último precio");
                    Object raw = source.ticker(id, metadata.getString("exchange"));
                    source.diagnostic(quoteShape(raw));
                    JSONObject parsed = quote(id, metadata.getString("exchange"), raw, System.currentTimeMillis());
                    if (parsed.optString("currency").isEmpty() && metadata.getString("exchange").equals("LSX"))
                        parsed.put("currency", "EUR").put("currencyBasis", "lsx_eur_unit_quote_convention");
                    info.put("quote", parsed);
                }
                fetched.put(id, info);
            }
            row.put("name", info.optString("name", id));
            JSONObject quote = info.optJSONObject("quote");
            if (quote == null) {
                row.put("valuationStatus", info.optString("valuationStatus", "UNAVAILABLE"));
                source.diagnostic("Resultado: " + row.getString("valuationStatus"));
                continue;
            }
            if (!quote.optString("previousCloseCurrency").isEmpty() && !quote.optString("currency").isEmpty()
                    && !quote.getString("previousCloseCurrency").equals(quote.getString("currency"))) throw TradeException.protocol();
            row.put("quote", new JSONObject(quote.toString()));
            if (!quote.has("price")) { row.put("valuationStatus", "NO_PRICE"); source.diagnostic("Resultado: NO_PRICE"); continue; }
            BigDecimal quantity = new BigDecimal(TradeRepublicClient.decimal(row.get("quantity")));
            if (quantity.signum() < 0) { row.put("valuationStatus", "UNSUPPORTED_QUANTITY"); continue; }
            row.put("estimatedValue", quantity.multiply(new BigDecimal(quote.getString("price"))).toPlainString());
            row.put("valuationStatus", quote.optString("currency").isEmpty() ? "UNKNOWN_CURRENCY" : "VALUED");
            row.put("pnl", TradePnl.calculate(row));
            source.diagnostic("Resultado: " + row.getString("valuationStatus"));
        }
        return cache;
    }

    private static String metadataShape(Object raw) {
        if (!(raw instanceof JSONObject)) return "Metadatos: formato inesperado";
        JSONObject data = (JSONObject) raw;
        String type = data.optString("typeId");
        String known = java.util.Arrays.asList("stock", "etf", "bond", "crypto", "fund", "derivative").contains(type) ? type : "otro o ausente";
        JSONArray exchanges = data.optJSONArray("exchangeIds");
        return "Tipo: " + known + "; mercados: " + (exchanges == null ? 0 : exchanges.length())
                + "; factor presente: " + (data.has("priceFactor") && !data.isNull("priceFactor"))
                + "; factor unitario: " + unitFactor(data);
    }

    private static boolean unitFactor(JSONObject data) {
        try {
            return data.has("priceFactor") && !data.isNull("priceFactor")
                    && new BigDecimal(TradeRepublicClient.decimal(data.get("priceFactor"))).compareTo(BigDecimal.ONE) == 0;
        } catch (Exception failure) { return false; }
    }

    private static String quoteShape(Object raw) {
        if (!(raw instanceof JSONObject)) return "Cotización: formato inesperado";
        JSONObject data = (JSONObject) raw, last = data.optJSONObject("last");
        return "Último: " + (last != null) + "; precio: " + (last != null && last.has("price") && !last.isNull("price"))
                + "; fecha: " + (last != null && last.has("time")) + "; referencia: " + (data.optJSONObject("pre") != null)
                + "; divisa: " + (data.has("currency") || data.has("currencyId") || last != null && (last.has("currency") || last.has("currencyId")));
    }

    static JSONObject metadata(String id, Object raw, long now) throws Exception {
        if (!(raw instanceof JSONObject)) throw TradeException.protocol();
        JSONObject data = (JSONObject) raw;
        String returnedId = string(data, "isin", 200);
        if (!returnedId.isEmpty() && !returnedId.equals(id)) throw TradeException.protocol();
        String type = string(data, "typeId", 40);
        boolean unit = type.equals("stock") || type.equals("etf") || type.equals("fund") && unitFactor(data);
        if (data.has("priceFactor") && !data.isNull("priceFactor"))
            unit &= new BigDecimal(TradeRepublicClient.decimal(data.get("priceFactor"))).compareTo(BigDecimal.ONE) == 0;
        String name = string(data, "shortName", 300);
        if (name.isEmpty()) name = string(data, "name", 300);
        String exchange = "";
        JSONArray exchanges = data.optJSONArray("exchangeIds");
        if (exchanges != null && exchanges.length() > 0) {
            Object first = exchanges.get(0);
            if (!(first instanceof String) || !((String) first).matches("[A-Za-z0-9_-]{1,24}")) throw TradeException.protocol();
            exchange = (String) first; // Same selection as pytr; no venue probing or fallbacks.
        }
        return new JSONObject().put("name", name.isEmpty() ? id : name).put("exchange", exchange)
                .put("unitPrice", unit).put("fetchedAt", now).put("schema", 3).put("diagnostic", metadataShape(data));
    }

    static JSONObject quote(String id, String exchange, Object raw, long receivedAt) throws Exception {
        if (!(raw instanceof JSONObject)) throw TradeException.protocol();
        JSONObject data = (JSONObject) raw;
        String returnedId = string(data, "isin", 200), returnedExchange = string(data, "exchangeId", 24);
        if ((!returnedId.isEmpty() && !id.equals(returnedId)) || (!returnedExchange.isEmpty() && !exchange.equals(returnedExchange)))
            throw TradeException.protocol();
        JSONObject result = new JSONObject().put("exchange", exchange).put("receivedAt", receivedAt);
        JSONObject last = data.optJSONObject("last");
        if (last == null || !last.has("price") || last.isNull("price")) return result;
        String price = TradeRepublicClient.decimal(last.get("price"));
        if (new BigDecimal(price).signum() <= 0) return result;
        String currency = currency(last);
        String outerCurrency = currency(data);
        if (!currency.isEmpty() && !outerCurrency.isEmpty() && !currency.equals(outerCurrency)) throw TradeException.protocol();
        if (currency.isEmpty()) currency = outerCurrency;
        long timestamp = TradeRepublicClient.deadline(last.opt("time"));
        if (timestamp < 946684800000L || timestamp > receivedAt + TimeUnit.MINUTES.toMillis(5)) timestamp = 0;
        result.put("price", price).put("currency", currency).put("quotedAt", timestamp)
                .put("currencyBasis", currency.isEmpty() ? "unknown" : "ticker")
                .put("source", "trade_republic_ticker").put("quality", string(data, "qualityId", 80));
        // Keep the reference that arrived with this exact quote, never yesterday's app snapshot.
        JSONObject pre = data.optJSONObject("pre");
        if (pre != null && pre.has("price") && !pre.isNull("price")) {
            String previous = TradeRepublicClient.decimal(pre.get("price"));
            String preCurrency = currency(pre);
            if (!preCurrency.isEmpty() && !currency.isEmpty() && !preCurrency.equals(currency)) throw TradeException.protocol();
            long previousAt = TradeRepublicClient.deadline(pre.opt("time"));
            if (new BigDecimal(previous).signum() > 0) result.put("previousClose", previous)
                    .put("previousCloseCurrency", preCurrency)
                    .put("previousCloseAt", previousAt >= 946684800000L && previousAt <= receivedAt + TimeUnit.MINUTES.toMillis(5) ? previousAt : 0);
        }
        return result;
    }

    private static String currency(JSONObject data) throws Exception {
        String value = string(data, "currencyId", 3), alternative = string(data, "currency", 3);
        if (!value.isEmpty() && !alternative.isEmpty() && !value.equals(alternative)) throw TradeException.protocol();
        if (value.isEmpty()) value = alternative;
        if (!value.isEmpty() && !value.matches("[A-Z]{3}")) throw TradeException.protocol();
        return value;
    }

    static JSONObject totals(JSONArray positions) throws Exception {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        int priced = 0;
        for (int i = 0; i < positions.length(); i++) {
            JSONObject row = positions.getJSONObject(i), quote = row.optJSONObject("quote");
            if (!"VALUED".equals(row.optString("valuationStatus")) || quote == null) continue;
            String currency = quote.getString("currency");
            BigDecimal previous = totals.containsKey(currency) ? totals.get(currency) : BigDecimal.ZERO;
            totals.put(currency, previous.add(new BigDecimal(row.getString("estimatedValue")))); priced++;
        }
        JSONObject values = new JSONObject();
        for (Map.Entry<String, BigDecimal> total : totals.entrySet()) values.put(total.getKey(), total.getValue().toPlainString());
        return new JSONObject().put("byCurrency", values).put("valued", priced).put("positions", positions.length())
                .put("complete", priced == positions.length());
    }

    private static String string(JSONObject data, String key, int max) throws Exception {
        if (!data.has(key) || data.isNull(key)) return "";
        Object value = data.get(key);
        if (!(value instanceof String) || ((String) value).length() > max) throw TradeException.protocol();
        return (String) value;
    }
}
