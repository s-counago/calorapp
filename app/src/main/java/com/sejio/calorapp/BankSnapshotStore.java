package com.sejio.calorapp;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One bounded, encrypted page snapshot per bank. Never stores credentials or cookies. */
final class BankSnapshotStore {
    private static final int MAX_BYTES = 128 * 1024;
    private static final String ALIAS = "pausa_banking_snapshots_v1";

    private static AtomicFile file(Context context, BankProvider bank) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "banking_" + bank.id + ".enc"));
    }

    static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static void validate(BankProvider bank, JSONObject data) throws Exception {
        if (data.getInt("schema") != 1 || !bank.id.equals(data.getString("bank"))
                || !bank.allows(data.getString("url")) || !data.getBoolean("partial")
                || data.getLong("capturedAt") <= 0) throw new IllegalArgumentException("Captura no válida");
        if (data.has("reader")) {
            if (bank != BankProvider.ABANCA) throw new IllegalArgumentException("Lector no válido");
            AbancaSnapshot.validate(data);
        }
        JSONArray rows = data.getJSONArray("rows");
        if (rows.length() == 0 || rows.length() > 200) throw new IllegalArgumentException("Sin datos legibles");
        for (int i = 0; i < rows.length(); i++) {
            Object row = rows.get(i);
            if (!(row instanceof String) || ((String) row).trim().isEmpty() || ((String) row).length() > 400)
                throw new IllegalArgumentException("Fila no válida");
        }
        if (data.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Captura demasiado grande");
    }

    static synchronized void save(Context context, BankProvider bank, JSONObject data) throws Exception {
        validate(bank, data);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(bank.id.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        AtomicFile target = file(context, bank);
        FileOutputStream stream = null;
        try {
            stream = target.startWrite();
            stream.write(1);
            stream.write(cipher.getIV()); // AndroidKeyStore uses a 12-byte GCM IV.
            stream.write(encrypted);
            target.finishWrite(stream);
        } catch (Exception error) {
            target.failWrite(stream);
            throw error;
        }
    }

    static synchronized JSONObject load(Context context, BankProvider bank) throws Exception {
        AtomicFile target = file(context, bank);
        if (!target.getBaseFile().exists()) return null;
        if (target.getBaseFile().length() > MAX_BYTES + 64) throw new IllegalStateException("Archivo no válido");
        byte[] bytes = target.readFully();
        if (bytes.length < 30 || bytes[0] != 1) throw new IllegalStateException("Archivo no válido");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 1, 12));
        cipher.updateAAD(bank.id.getBytes(StandardCharsets.UTF_8));
        JSONObject data = new JSONObject(new String(cipher.doFinal(bytes, 13, bytes.length - 13), StandardCharsets.UTF_8));
        validate(bank, data);
        return data;
    }

    static synchronized void delete(Context context, BankProvider bank) { file(context, bank).delete(); }
}
