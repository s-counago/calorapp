package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.Locale;

/** Canonical, bounded HTML results. Does not accept URLs, cookies or form fields. */
final class AbancaSnapshot {
    static final String READER = "abanca-html-v1";

    static JSONObject prepare(JSONObject source) throws Exception {
        if (!READER.equals(source.getString("reader")) || !"captured".equals(source.getString("status"))
                || !source.getBoolean("partial")) throw invalid();
        String page = source.getString("pageType");
        if (!page.equals("overview") && !page.equals("account") && !page.equals("card")) throw invalid();
        boolean truncated = source.getBoolean("truncated");
        int omitted = source.getInt("omittedRows");
        if (omitted < 0 || omitted > 100_000) throw invalid();
        JSONArray input = source.getJSONArray("records");
        if (input.length() == 0 || input.length() > 200) throw invalid();
        JSONArray records = new JSONArray(), rows = new JSONArray();
        int payloadBytes = 0;
        for (int i = 0; i < input.length(); i++) {
            JSONObject raw = input.getJSONObject(i), record = new JSONObject();
            String row;
            if (page.equals("overview")) {
                String type = string(raw, "productType", 16, false);
                if (!type.equals("account") && !type.equals("card") && !type.equals("loan")) throw invalid();
                String label = string(raw, "label", 120, false), kind = string(raw, "kind", 80, true);
                JSONObject balance = money(raw, "balance", false), limit = money(raw, "limit", true);
                if (type.equals("account") && limit != null) throw invalid();
                record.put("productType", type).put("label", label).put("kind", kind)
                        .put("balance", balance).put("limit", limit == null ? JSONObject.NULL : limit);
                String product = type.equals("account") ? "Cuenta" : type.equals("card") ? "Tarjeta" : "Préstamo";
                String balanceLabel = type.equals("account") ? "Saldo" : type.equals("card") ? "Dispuesto" : "Pendiente";
                row = product + " · " + label + (kind.isEmpty() ? "" : " · " + kind)
                        + "\n" + balanceLabel + ": " + displayMoney(balance)
                        + (limit == null ? "" : " · Límite concedido: " + displayMoney(limit));
            } else {
                String operationDate = date(raw, "operationDate"), description = string(raw, "description", 240, false);
                JSONObject amount = money(raw, "amount", false);
                record.put("operationDate", operationDate).put("description", description).put("amount", amount);
                row = displayDate(operationDate) + " · " + description + "\nImporte: " + displayMoney(amount);
                if (page.equals("account")) {
                    String valueDate = date(raw, "valueDate");
                    JSONObject balance = money(raw, "balance", false);
                    record.put("valueDate", valueDate).put("balance", balance);
                    row += " · Saldo: " + displayMoney(balance) + "\nFecha valor: " + displayDate(valueDate);
                } else {
                    String type = string(raw, "operationType", 80, true), situation = string(raw, "situation", 80, true);
                    String payment = string(raw, "payment", 80, true);
                    record.put("operationType", type).put("situation", situation).put("payment", payment);
                    row += (type.isEmpty() ? "" : "\nTipo: " + type)
                            + (situation.isEmpty() ? "" : " · Situación: " + situation)
                            + (payment.isEmpty() ? "" : "\nPago: " + payment);
                }
            }
            if (row.length() > 400) { row = row.substring(0, 399) + "…"; truncated = true; }
            payloadBytes += record.toString().getBytes(StandardCharsets.UTF_8).length
                    + new JSONArray().put(row).toString().getBytes(StandardCharsets.UTF_8).length + 4;
            // Leave space for metadata and JSON delimiters under the encrypted store's 128 KiB limit.
            if (payloadBytes > 110 * 1024) { truncated = true; break; }
            records.put(record);
            rows.put(row);
        }
        // Rebuild instead of persisting arbitrary keys supplied by a page.
        return new JSONObject().put("status", "captured").put("reader", READER).put("pageType", page)
                .put("partial", true).put("truncated", truncated).put("omittedRows", omitted)
                .put("records", records).put("rows", rows);
    }

    static void validate(JSONObject source) throws Exception {
        JSONObject canonical = prepare(source);
        if (!source.getJSONArray("rows").toString().equals(canonical.getJSONArray("rows").toString())
                || source.getBoolean("truncated") != canonical.getBoolean("truncated")) throw invalid();
    }

    static String title(JSONObject source) {
        switch (source.optString("pageType")) {
            case "overview": return "Resumen de productos";
            case "account": return "Movimientos de cuenta";
            case "card": return "Movimientos de tarjeta";
            default: return "Datos guardados";
        }
    }

    private static String string(JSONObject source, String field, int max, boolean empty) throws Exception {
        Object value = source.get(field);
        if (!(value instanceof String)) throw invalid();
        String text = (String) value;
        if (text.length() > max || (!empty && text.trim().isEmpty())) throw invalid();
        return text;
    }

    private static JSONObject money(JSONObject source, String field, boolean optional) throws Exception {
        if (optional && source.has(field) && source.isNull(field)) return null;
        JSONObject value = source.getJSONObject(field);
        String amount = string(value, "amount", 23, false);
        if (!amount.matches("-?(?:0|[1-9][0-9]{0,17})\\.[0-9]{2}") || amount.equals("-0.00")) throw invalid();
        if (!value.has("currency")) throw invalid();
        String currency = value.isNull("currency") ? null : string(value, "currency", 3, false);
        if (currency != null && !currency.matches("[A-Z]{3}")) throw invalid();
        return new JSONObject().put("amount", amount).put("currency", currency == null ? JSONObject.NULL : currency);
    }

    private static String date(JSONObject source, String field) throws Exception {
        String text = string(source, field, 10, false);
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        if (format.parse(text, position) == null || position.getIndex() != 10) throw invalid();
        return text;
    }

    private static String displayDate(String value) {
        return value.substring(8) + "/" + value.substring(5, 7) + "/" + value.substring(0, 4);
    }

    private static String displayMoney(JSONObject value) throws Exception {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(new Locale("es", "ES")));
        return format.format(new BigDecimal(value.getString("amount")))
                + (value.isNull("currency") ? " (moneda no indicada)" : " " + value.getString("currency"));
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Captura ABANCA no válida"); }
}
