package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Source text and normalized interpretation are separate and linked by table/row coordinates. */
final class AbancaCapture {
    static JSONObject fromPage(String label, String kind, String url, JSONObject raw, long at) throws Exception {
        String type = AbancaSyncPolicy.pageType(url);
        if (type.isEmpty() || !type.equals(raw.getString("pageType")) || label.isEmpty() || label.length() > 120 || kind.length() > 80 || at <= 0) throw invalid();
        if (!raw.getString("status").equals("captured") && !raw.getString("status").equals("no_records")) throw invalid();
        JSONArray source = sourceTables(raw.getJSONArray("sourceTables"));
        JSONObject product = new JSONObject().put("id", BankingRecords.productId("abanca", type, label))
                .put("type", type).put("label", label).put("kind", kind).put("identityBasis", "bank_label");
        JSONObject normalized = raw.getString("status").equals("captured") ? AbancaSnapshot.prepare(raw) : null;
        return new JSONObject().put("schema", 1).put("captureId", UUID.randomUUID().toString()).put("bank", "abanca")
                .put("product", product).put("capturedAt", at).put("sourcePath", new URI(url).getRawPath())
                .put("transport", "webview").put("parserVersion", "abanca-html-v2").put("partial", true)
                .put("sourceTruncated", raw.getBoolean("sourceTruncated")).put("sourceTables", source)
                .put("normalized", normalized == null ? JSONObject.NULL : normalized)
                .put("status", normalized == null ? "source_only" : "parsed")
                .put("omittedRows", raw.getInt("omittedRows"));
    }

    static JSONArray sourceTables(JSONArray input) throws Exception {
        if (input.length() == 0 || input.length() > 8) throw invalid();
        JSONArray tables = new JSONArray();
        for (int t = 0; t < input.length(); t++) {
            JSONObject raw = input.getJSONObject(t); String key = string(raw, "key", 40), title = string(raw, "title", 80);
            JSONArray inputRows = raw.getJSONArray("rows"), rows = new JSONArray();
            if (inputRows.length() > 2001) throw invalid(); int previous = -1;
            for (int r = 0; r < inputRows.length(); r++) {
                JSONObject row = inputRows.getJSONObject(r); int index = row.getInt("index");
                if (index <= previous || index > 100000) throw invalid(); previous = index;
                JSONArray inputCells = row.getJSONArray("cells"), cells = new JSONArray();
                if (inputCells.length() > 8) throw invalid();
                for (int c = 0; c < inputCells.length(); c++) {
                    JSONObject cell = inputCells.getJSONObject(c); int span = cell.getInt("colspan");
                    if (cell.getInt("column") != c || span < 1 || span > 8) throw invalid();
                    cells.put(new JSONObject().put("column", c).put("text", string(cell, "text", 4096))
                            .put("colspan", span).put("header", cell.getBoolean("header")));
                }
                rows.put(new JSONObject().put("index", index).put("cells", cells));
            }
            tables.put(new JSONObject().put("key", key).put("title", title).put("rows", rows));
        }
        if (tables.toString().getBytes(StandardCharsets.UTF_8).length > 512 * 1024) throw invalid();
        return tables;
    }
    private static String string(JSONObject data, String key, int max) throws Exception {
        Object value = data.get(key);
        if (!(value instanceof String) || ((String) value).length() > max) throw invalid();
        return (String) value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("La página no tiene una estructura reconocida."); }
}
