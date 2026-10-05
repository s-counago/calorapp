package com.sejio.calorapp.trade;

import org.json.JSONArray;
import org.json.JSONObject;

/** Only fields evidenced by the compact portfolio reference; no invented quotes or currency. */
final class TradePortfolio {
    static JSONArray normalize(Object response) throws Exception {
        if (!(response instanceof JSONObject)) throw TradeException.protocol();
        JSONArray categories = ((JSONObject) response).optJSONArray("categories");
        if (categories == null || categories.length() > 100) throw TradeException.protocol();
        JSONArray result = new JSONArray();
        for (int group = 0; group < categories.length(); group++) {
            JSONArray positions = categories.getJSONObject(group).getJSONArray("positions");
            for (int i = 0; i < positions.length(); i++) {
                if (result.length() >= 500) throw TradeException.protocol();
                JSONObject position = positions.getJSONObject(i);
                String id = string(position, "isin", 200);
                if (id.isEmpty()) id = string(position, "instrumentId", 200);
                if (id.isEmpty() || position.isNull("netSize")) throw TradeException.protocol();
                JSONObject row = new JSONObject().put("instrumentId", id)
                        .put("quantity", TradeRepublicClient.decimal(position.get("netSize")));
                if (position.has("averageBuyIn") && !position.isNull("averageBuyIn"))
                    row.put("averageBuyIn", TradeRepublicClient.decimal(position.get("averageBuyIn")));
                // These optional fields are not guaranteed by the reference.
                String currency = string(position, "currencyId", 3);
                if (currency.isEmpty()) currency = string(position, "currency", 3);
                if (!currency.isEmpty() && !currency.matches("[A-Z]{3}")) throw TradeException.protocol();
                row.put("currency", currency);
                result.put(row);
            }
        }
        return result;
    }

    private static String string(JSONObject object, String key, int max) throws Exception {
        if (!object.has(key) || object.isNull(key)) return "";
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).length() > max) throw TradeException.protocol();
        return (String) value;
    }
}
