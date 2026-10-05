package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Common entities plus lossless source observations. Identity rules are explicitly versioned. */
final class BankingRecords {
    static final class Entity {
        final String id, productId, type;
        final JSONObject data;
        final int index, sourceTable, sourceRow;
        Entity(String id, String productId, String type, JSONObject data, int index, int table, int row) {
            this.id = id; this.productId = productId; this.type = type; this.data = data;
            this.index = index; this.sourceTable = table; this.sourceRow = row;
        }
    }
    static String hash(String value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
            result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    static String productId(String bank, String type, String identity) throws Exception {
        return hash(new JSONArray().put("product-v1").put(bank).put(type).put(identity).toString());
    }
    private static String normalizedText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    static String movementId(String productId, JSONObject data) throws Exception {
        if (!data.optString("bankId").isEmpty()) return hash(new JSONArray().put("bank-id-v1").put(productId).put(data.getString("bankId")).toString());
        JSONObject amount = data.getJSONObject("amount");
        // Deliberately excludes changing running balance, posting status and row order.
        return hash(new JSONArray().put("abanca-fingerprint-v1").put(productId)
                .put(data.getString("operationDate"))
                .put(new BigDecimal(amount.getString("amount")).stripTrailingZeros().toPlainString())
                .put(amount.opt("currency")).put(normalizedText(data.getString("description")))
                .put(normalizedText(data.optString("operationType"))).toString());
    }

    static List<Entity> abanca(JSONObject capture) throws Exception {
        List<Entity> entities = new ArrayList<>(); JSONObject normalized = capture.optJSONObject("normalized");
        if (normalized == null) return entities;
        String page = normalized.getString("pageType"), productId = capture.getJSONObject("product").getString("id");
        JSONArray records = normalized.getJSONArray("records");
        for (int i = 0; i < records.length(); i++) {
            JSONObject raw = records.getJSONObject(i), data = new JSONObject(raw.toString());
            int table = raw.optInt("sourceTable", -1), row = raw.optInt("sourceRow", -1);
            String type, identity, owner = productId;
            if (page.equals("overview")) {
                owner = productId("abanca", raw.getString("productType"), raw.getString("label"));
                type = "balance"; identity = "balance";
                data.put("balanceType", raw.getString("productType").equals("account") ? "account_balance"
                        : raw.getString("productType").equals("card") ? "credit_used" : "loan_outstanding");
                data.put("product", new JSONObject().put("id", owner).put("type", raw.getString("productType"))
                        .put("label", raw.getString("label")).put("kind", raw.getString("kind")).put("identityBasis", "bank_label"));
            } else if (page.equals("loan")) {
                type = "loan_term"; identity = new JSONArray().put(raw.getString("section")).put(raw.getString("group")).put(raw.getString("label")).toString();
            } else {
                type = "movement";
                String originalDescription = sourceCell(capture, table, row, page.equals("account") ? 2 : 4);
                if (originalDescription != null && !originalDescription.isEmpty()) data.put("description", originalDescription);
                data.put("identityBasis", "abanca-fingerprint-v1");
                data.put("status", raw.has("situation") ? raw.get("situation") : JSONObject.NULL)
                        .put("occurredAt", JSONObject.NULL).put("merchant", JSONObject.NULL);
                if (!data.has("valueDate")) data.put("valueDate", JSONObject.NULL);
                identity = movementId(owner, data);
            }
            data.put("bank", "abanca").put("productId", owner).put("entityType", type);
            String id = type.equals("movement") ? identity : hash(new JSONArray().put(owner).put(type).put(identity).toString());
            entities.add(new Entity(id, owner, type, data, i, table, row));
        }
        return entities;
    }

    private static String sourceCell(JSONObject capture, int table, int row, int column) throws Exception {
        JSONArray tables = capture.optJSONArray("sourceTables");
        if (tables == null || table < 0 || table >= tables.length()) return null;
        JSONArray rows = tables.getJSONObject(table).getJSONArray("rows");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject source = rows.getJSONObject(i);
            if (source.getInt("index") != row) continue;
            JSONArray cells = source.getJSONArray("cells");
            for (int j = 0; j < cells.length(); j++) if (cells.getJSONObject(j).getInt("column") == column)
                return cells.getJSONObject(j).getString("text");
        }
        return null;
    }

    static List<Entity> trade(JSONObject capture) throws Exception {
        List<Entity> result = new ArrayList<>(); JSONObject source = capture.getJSONObject("data");
        String owner = capture.getJSONObject("product").getString("id"); int ordinal = 0;
        JSONArray balances = source.optJSONArray("balances");
        if (balances != null) for (int i = 0; i < balances.length(); i++) {
            JSONObject raw = balances.getJSONObject(i);
            JSONObject data = new JSONObject().put("balance", new JSONObject().put("amount", raw.getString("amount"))
                    .put("currency", raw.getString("currency"))).put("balanceType", "cash");
            result.add(entity(owner, "balance", raw.getString("currency"), data, ordinal++));
        }
        for (String topic : new String[]{"transactions", "activity"}) {
            JSONObject timeline = source.optJSONObject(topic); if (timeline == null) continue;
            JSONArray items = timeline.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject raw = items.getJSONObject(i), data = new JSONObject(raw.toString());
                data.put("bankId", raw.getString("id")).put("description", raw.optString("title"))
                        .put("identityBasis", "bank_id").put("occurredAt", raw.optString("timestamp"))
                        .put("operationDate", JSONObject.NULL).put("valueDate", JSONObject.NULL).put("merchant", JSONObject.NULL);
                JSONObject amount = raw.optJSONObject("amount");
                if (amount != null) data.put("amount", new JSONObject().put("amount", amount.getString("value")).put("currency", amount.getString("currency")));
                result.add(entity(owner, topic.equals("transactions") ? "movement" : "activity", raw.getString("id"), data, ordinal++));
            }
        }
        JSONArray positions = source.optJSONArray("positions");
        if (positions != null) for (int i = 0; i < positions.length(); i++) {
            JSONObject data = new JSONObject(positions.getJSONObject(i).toString());
            result.add(entity(owner, "investment_position", data.getString("instrumentId"), data, ordinal++));
        }
        return result;
    }
    private static Entity entity(String owner, String type, String identity, JSONObject data, int index) throws Exception {
        data.put("bank", "trade_republic").put("productId", owner).put("entityType", type);
        return new Entity(hash(new JSONArray().put(owner).put(type).put(identity).toString()), owner, type, data, index, -1, -1);
    }
}
