package com.sejio.calorapp;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import com.sejio.calorapp.trade.TradeRepublicClient;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Session, pending confirmation and cached reads in one authenticated, atomic encrypted file. */
final class TradeVault implements TradeRepublicClient.Store {
    private static final String ALIAS = "pausa_trade_native_v1";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final AtomicFile file;

    TradeVault(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "trade_native_v1.enc"));
    }

    synchronized void erase() throws Exception {
        file.delete();
        if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists())
            throw new IllegalStateException("Could not erase encrypted state");
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        ks.deleteEntry(ALIAS);
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("Missing encryption key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    @Override public synchronized JSONObject load() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return null;
        byte[] data;
        try (java.io.InputStream in = file.openRead(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_BYTES + 64) throw new IllegalStateException("Oversize encrypted state");
                out.write(buffer, 0, n);
            }
            data = out.toByteArray();
        }
        if (data.length < 30 || data[0] != 1) throw new IllegalStateException("Invalid encrypted state");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, data, 1, 12));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(cipher.doFinal(data, 13, data.length - 13), StandardCharsets.UTF_8));
    }

    @Override public synchronized void save(JSONObject state) throws Exception {
        byte[] plain = state.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_BYTES) throw new IllegalStateException("Oversize state");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plain); } finally { java.util.Arrays.fill(plain, (byte) 0); }
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(1); output.write(cipher.getIV()); output.write(encrypted); file.finishWrite(output);
        } catch (Exception error) { file.failWrite(output); throw error; }
    }
}
