package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.UUID;

/** Uses its own disposable DB file. Does not touch the user's ledger or any bank. */
public final class BankingDatabaseSmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        File file = new File(getTargetContext().getNoBackupFilesDir(), "ledger-test-" + UUID.randomUUID() + ".sqlite");
        BankingDatabase db = null; Bundle result = new Bundle();
        try {
            db = new BankingDatabase(getTargetContext(), file);
            check(db.begin("test-run", "abanca", "synthetic", 1));
            JSONObject money = new JSONObject().put("amount", "-12.50").put("currency", "EUR");
            JSONObject row = new JSONObject().put("operationDate", "2026-10-05").put("valueDate", "2026-10-06")
                    .put("description", "FictionalPrivatePurchase").put("amount", money).put("balance", money);
            JSONObject normalized = AbancaSnapshot.prepare(new JSONObject().put("reader", AbancaSnapshot.READER).put("status", "captured")
                    .put("pageType", "account").put("partial", true).put("truncated", false).put("omittedRows", 0)
                    .put("records", new JSONArray().put(row).put(row)));
            JSONObject capture = new JSONObject().put("bank", "abanca").put("captureId", "test-capture").put("capturedAt", 2)
                    .put("product", new JSONObject().put("id", "test-product").put("type", "account").put("label", "FictionalAccount"))
                    .put("sourcePath", "synthetic").put("parserVersion", "test").put("normalized", normalized).put("sourceTables", new JSONArray());
            db.capture("test-run", capture); db.capture("test-run", capture);
            check(db.counts("abanca").getLong("movements") == 1);
            try (Cursor cursor = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM observations", null)) { cursor.moveToFirst(); check(cursor.getInt(0) == 2); }
            byte[] encrypted;
            try (Cursor cursor = db.getReadableDatabase().rawQuery("SELECT payload FROM captures", null)) { cursor.moveToFirst(); encrypted = cursor.getBlob(0); }
            check(!new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1).contains("FictionalPrivatePurchase"));
            db.close(); db = new BankingDatabase(getTargetContext(), file);
            check(db.runs("abanca", 0).getJSONObject(0).getString("status").equals("interrupted"));
            check(db.pages("test-run").getJSONObject(0).getJSONObject("normalized").getJSONArray("records").length() == 2);
            db.deleteBank("abanca"); check(db.counts("abanca").getLong("movements") == 0);
            result.putString("stream", "PASS: native SQLite/Keystore, duplicate observations, idempotent capture, encrypted payload, reopen and deletion\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName() + "\n"); finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (db != null) db.close();
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file);
        }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
