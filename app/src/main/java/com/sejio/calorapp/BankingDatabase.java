package com.sejio.calorapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.List;

/** Durable ledger: append-only captures/observations, independently updatable common entities. */
final class BankingDatabase extends SQLiteOpenHelper {
    private static BankingDatabase instance;
    static synchronized BankingDatabase get(Context context) {
        if (instance == null) instance = new BankingDatabase(context.getApplicationContext());
        return instance;
    }
    static final String[] SCHEMA = {
        "CREATE TABLE sync_runs (id TEXT PRIMARY KEY, bank TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, status TEXT NOT NULL, skipped INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL)",
        "CREATE TABLE products (id TEXT PRIMARY KEY, bank TEXT NOT NULL, type TEXT NOT NULL, updated_at INTEGER NOT NULL, payload BLOB NOT NULL)",
        "CREATE TABLE captures (id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES sync_runs(id) ON DELETE CASCADE, product_id TEXT NOT NULL REFERENCES products(id), captured_at INTEGER NOT NULL, source_path TEXT NOT NULL, parser_version TEXT NOT NULL, payload BLOB NOT NULL)",
        "CREATE TABLE entities (id TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE, type TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, observed_at INTEGER NOT NULL, latest_capture TEXT NOT NULL REFERENCES captures(id) ON DELETE CASCADE, payload BLOB NOT NULL)",
        "CREATE TABLE observations (capture_id TEXT NOT NULL REFERENCES captures(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, entity_id TEXT NOT NULL REFERENCES entities(id) ON DELETE CASCADE, source_table INTEGER NOT NULL, source_row INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(capture_id, ordinal))",
        "CREATE INDEX captures_by_run ON captures(run_id, captured_at)",
        "CREATE INDEX entities_by_product ON entities(product_id, type)",
        "CREATE INDEX observations_by_entity ON observations(entity_id)",
        "CREATE INDEX runs_by_bank ON sync_runs(bank, started_at DESC)"
    };
    private BankingDatabase(Context context) {
        this(context, new File(context.getNoBackupFilesDir(), "banking.sqlite"));
    }
    BankingDatabase(Context context, File file) {
        super(context, file.getAbsolutePath(), null, 1);
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); db.execSQL("PRAGMA synchronous=FULL"); }
    @Override public void onCreate(SQLiteDatabase db) { for (String sql : SCHEMA) db.execSQL(sql); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("La base de datos necesita una migración compatible; no se borrará.");
    }
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) db.execSQL("UPDATE sync_runs SET status='interrupted' WHERE status='collecting'");
    }

    synchronized boolean begin(String id, String bank, String source, long at) {
        if ((!bank.equals("abanca") && !bank.equals("trade_republic")) || at <= 0) throw new IllegalArgumentException();
        ContentValues row = new ContentValues(); row.put("id", id); row.put("bank", bank); row.put("source", source);
        row.put("started_at", at); row.put("status", "collecting");
        return getWritableDatabase().insertWithOnConflict("sync_runs", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    synchronized void finish(String id, String status, int skipped) {
        if (!status.equals("complete") && !status.equals("interrupted") && !status.equals("failed")) throw new IllegalArgumentException();
        ContentValues row = new ContentValues(); row.put("status", status); row.put("finished_at", System.currentTimeMillis()); row.put("skipped", skipped);
        getWritableDatabase().update("sync_runs", row, "id=?", new String[]{id});
    }

    synchronized void capture(String run, JSONObject capture) throws Exception {
        SQLiteDatabase db = getWritableDatabase(); String bank = capture.getString("bank"), id = capture.getString("captureId");
        long at = capture.getLong("capturedAt"); JSONObject product = capture.getJSONObject("product");
        List<BankingRecords.Entity> records = bank.equals("abanca") ? BankingRecords.abanca(capture) : BankingRecords.trade(capture);
        db.beginTransaction();
        try {
            try (Cursor cursor = db.rawQuery("SELECT bank FROM sync_runs WHERE id=?", new String[]{run})) {
                if (!cursor.moveToFirst() || !bank.equals(cursor.getString(0))) throw new IllegalArgumentException("Consulta no válida.");
            }
            product(db, bank, product, at);
            ContentValues page = new ContentValues(); page.put("id", id); page.put("run_id", run); page.put("product_id", product.getString("id"));
            page.put("captured_at", at); page.put("source_path", capture.getString("sourcePath")); page.put("parser_version", capture.getString("parserVersion"));
            page.put("payload", encrypt("captures", id, capture));
            if (db.insertWithOnConflict("captures", null, page, SQLiteDatabase.CONFLICT_IGNORE) == -1) { db.setTransactionSuccessful(); return; }
            if (bank.equals("trade_republic") && capture.getJSONObject("data").has("positions"))
                db.execSQL("UPDATE entities SET active=0 WHERE product_id=? AND type='investment_position' AND observed_at<=?", new Object[]{product.getString("id"), at});
            for (BankingRecords.Entity entity : records) {
                if (entity.data.has("product")) product(db, bank, entity.data.getJSONObject("product"), at);
                ContentValues value = new ContentValues(); value.put("id", entity.id); value.put("product_id", entity.productId); value.put("type", entity.type);
                value.put("observed_at", at); value.put("latest_capture", id); value.put("payload", encrypt("entities", entity.id, entity.data));
                value.put("active", entity.data.optBoolean("removed") ? 0 : 1);
                db.insertWithOnConflict("entities", null, value, SQLiteDatabase.CONFLICT_IGNORE);
                db.update("entities", value, "id=? AND observed_at<=?", new String[]{entity.id, String.valueOf(at)});
                ContentValues observation = new ContentValues(); observation.put("capture_id", id); observation.put("ordinal", entity.index);
                observation.put("entity_id", entity.id); observation.put("source_table", entity.sourceTable); observation.put("source_row", entity.sourceRow);
                observation.put("payload", encrypt("observations", id + ":" + entity.index, entity.data));
                db.insertOrThrow("observations", null, observation);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private void product(SQLiteDatabase db, String bank, JSONObject data, long at) throws Exception {
        String id = data.getString("id"); ContentValues row = new ContentValues(); row.put("id", id); row.put("bank", bank);
        row.put("type", data.getString("type")); row.put("updated_at", at); row.put("payload", encrypt("products", id, data));
        db.insertWithOnConflict("products", null, row, SQLiteDatabase.CONFLICT_IGNORE);
        db.update("products", row, "id=? AND updated_at<=?", new String[]{id, String.valueOf(at)});
    }

    synchronized void trade(JSONObject view) throws Exception {
        for (String topic : new String[]{"snapshot", "portfolio"}) {
            JSONObject data = view.optJSONObject(topic); if (data == null || data.optLong("capturedAt") <= 0) continue;
            long at = data.getLong("capturedAt"); String owner = data.optString("accountId", "legacy-unidentified");
            String run = BankingRecords.hash("trade:" + topic + ":" + owner + ":" + at);
            if (!begin(run, "trade_republic", "trade_native", at)) {
                try (Cursor existing = getReadableDatabase().rawQuery("SELECT id FROM captures WHERE id=?", new String[]{run})) {
                    if (existing.moveToFirst()) continue;
                }
            }
            try {
                JSONObject product = new JSONObject().put("id", BankingRecords.productId("trade_republic", "investment_account", owner))
                        .put("type", "investment_account").put("label", "Trade Republic").put("identityBasis", owner.equals("legacy-unidentified") ? "legacy_unknown" : "bank_account_hash");
                JSONObject capture = new JSONObject().put("schema", 1).put("captureId", run).put("bank", "trade_republic")
                        .put("capturedAt", at).put("product", product).put("transport", "trade_native")
                        .put("sourcePath", topic).put("parserVersion", "trade-client-v1").put("data", new JSONObject(data.toString()));
                capture(run, capture); finish(run, "complete", 0);
            } catch (Exception failure) { finish(run, "failed", 0); throw failure; }
        }
    }

    synchronized JSONArray runs(String bank, int offset) throws Exception {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT r.id,r.started_at,r.status,r.skipped,COUNT(c.id) FROM sync_runs r LEFT JOIN captures c ON c.run_id=r.id WHERE r.bank=? GROUP BY r.id ORDER BY r.started_at DESC LIMIT 30 OFFSET ?", new String[]{bank, String.valueOf(Math.max(0, offset))})) {
            while (cursor.moveToNext()) result.put(new JSONObject().put("id", cursor.getString(0)).put("capturedAt", cursor.getLong(1))
                    .put("status", cursor.getString(2)).put("skipped", cursor.getInt(3)).put("pages", cursor.getInt(4)));
        }
        return result;
    }
    synchronized void migrateLegacy(Context context, BankProvider bank) throws Exception {
        if (bank == BankProvider.TRADE_REPUBLIC) {
            JSONObject trade = new TradeVault(context).load();
            if (trade != null) {
                JSONObject financial = new JSONObject();
                for (String topic : new String[]{"snapshot", "portfolio"}) if (trade.has(topic)) financial.put(topic, trade.getJSONObject(topic));
                trade(financial);
            }
            return;
        }
        JSONObject old = BankSnapshotStore.load(context, BankProvider.ABANCA);
        if (old == null) return;
        long at = old.getLong("capturedAt"); String run = BankingRecords.hash("legacy-abanca:" + at);
        if (!begin(run, "abanca", "legacy_manual_snapshot", at)) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM captures WHERE id=?", new String[]{run})) { if (c.moveToFirst()) return; }
        }
        try {
            JSONObject normalized = AbancaSnapshot.READER.equals(old.optString("reader")) ? AbancaSnapshot.prepare(old) : null;
            JSONObject product = new JSONObject().put("id", BankingRecords.productId("abanca", "legacy_unidentified", run))
                    .put("type", "legacy_unidentified").put("label", "Captura anterior de ABANCA").put("identityBasis", "legacy_unknown");
            JSONObject capture = new JSONObject().put("schema", 1).put("captureId", run).put("bank", "abanca").put("capturedAt", at)
                    .put("product", product).put("sourcePath", "legacy_manual_snapshot").put("parserVersion", "legacy-v1")
                    .put("sourceTables", new JSONArray()).put("sourceTruncated", old.optBoolean("truncated"))
                    .put("normalized", normalized == null ? JSONObject.NULL : normalized).put("legacySnapshot", old);
            capture(run, capture); finish(run, "complete", 0);
        } catch (Exception error) { finish(run, "failed", 0); throw error; }
    }
    /** Latest interpretation of every active movement with its product, for the budget. Read-only. */
    synchronized JSONArray movements() throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        java.util.Map<String, JSONObject> products = new java.util.HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT id,bank,type,payload FROM products", null)) {
            while (cursor.moveToNext()) {
                JSONObject product = new JSONObject().put("bank", cursor.getString(1)).put("type", cursor.getString(2));
                try { product.put("label", decrypt("products", cursor.getString(0), cursor.getBlob(3)).optString("label")); }
                catch (Exception ignored) { product.put("label", ""); }
                products.put(cursor.getString(0), product);
            }
        }
        JSONArray result = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT id,product_id,payload FROM entities WHERE type='movement' AND active=1", null)) {
            while (cursor.moveToNext()) {
                JSONObject product = products.get(cursor.getString(1));
                if (product == null) continue;
                JSONObject data;
                try { data = decrypt("entities", cursor.getString(0), cursor.getBlob(2)); }
                catch (Exception unreadable) { continue; }
                result.put(new JSONObject().put("id", cursor.getString(0)).put("bank", product.getString("bank"))
                        .put("productType", product.getString("type")).put("productLabel", product.getString("label")).put("data", data));
            }
        }
        return result;
    }

    synchronized long lastSync() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT MAX(COALESCE(finished_at, started_at)) FROM sync_runs WHERE status='complete'", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    synchronized JSONArray pages(String run) throws Exception {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,payload FROM captures WHERE run_id=? ORDER BY captured_at,rowid", new String[]{run})) {
            while (cursor.moveToNext()) result.put(decrypt("captures", cursor.getString(0), cursor.getBlob(1)));
        }
        return result;
    }
    synchronized JSONObject counts(String bank) throws Exception {
        JSONObject result = new JSONObject();
        for (String table : new String[]{"products", "sync_runs"}) {
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table + " WHERE bank=?", new String[]{bank})) {
                cursor.moveToFirst(); result.put(table, cursor.getLong(0));
            }
        }
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entities e JOIN products p ON p.id=e.product_id WHERE p.bank=? AND e.type='movement'", new String[]{bank})) {
            cursor.moveToFirst(); result.put("movements", cursor.getLong(0));
        }
        return result;
    }
    synchronized void deleteBank(String bank) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.delete("sync_runs", "bank=?", new String[]{bank}); db.delete("products", "bank=?", new String[]{bank}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    private byte[] encrypt(String table, String id, JSONObject data) throws Exception { return BankingCrypto.encrypt(BankSnapshotStore.key(), "banking-db-v1:" + table + ":" + id, data); }
    private JSONObject decrypt(String table, String id, byte[] data) throws Exception { return BankingCrypto.decrypt(BankSnapshotStore.key(), "banking-db-v1:" + table + ":" + id, data); }
}
