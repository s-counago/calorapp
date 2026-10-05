package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Disposable emulator only: replaces and removes both banking snapshots, never contacts a bank. */
public final class BankingStoreSmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        try {
            for (BankProvider bank : BankProvider.values()) BankSnapshotStore.delete(context, bank);
            JSONObject data = new JSONObject().put("schema", 1).put("bank", "abanca")
                    .put("url", BankProvider.ABANCA.home).put("partial", true)
                    .put("capturedAt", System.currentTimeMillis()).put("rows", new JSONArray().put("Saldo de prueba 123,45 EUR"));
            BankSnapshotStore.save(context, BankProvider.ABANCA, data);
            check(BankSnapshotStore.load(context, BankProvider.ABANCA).toString().equals(data.toString()));
            check(BankSnapshotStore.load(context, BankProvider.TRADE_REPUBLIC) == null);
            File file = new File(context.getNoBackupFilesDir(), "banking_abanca.enc");
            byte[] bytes = new android.util.AtomicFile(file).readFully();
            check(!new String(bytes, StandardCharsets.UTF_8).contains("123,45"));
            try {
                BankSnapshotStore.save(context, BankProvider.ABANCA, new JSONObject(data.toString()).put("rows", new JSONArray()));
                throw new AssertionError("empty snapshot accepted");
            } catch (IllegalArgumentException expected) { }
            check(BankSnapshotStore.load(context, BankProvider.ABANCA).toString().equals(data.toString()));
            try (RandomAccessFile tamper = new RandomAccessFile(file, "rw")) {
                tamper.seek(bytes.length - 1); tamper.write(bytes[bytes.length - 1] ^ 1);
            }
            try { BankSnapshotStore.load(context, BankProvider.ABANCA); throw new AssertionError("tamper accepted"); }
            catch (javax.crypto.AEADBadTagException expected) { }
            result.putString("stream", "PASS: encrypted round-trip, isolated banks, invalid snapshot preserves data, tamper detection\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            for (BankProvider bank : BankProvider.values()) BankSnapshotStore.delete(context, bank);
        }
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
}
