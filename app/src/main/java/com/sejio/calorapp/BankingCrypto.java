package com.sejio.calorapp;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Financial JSON is encrypted before SQLite sees it, including its WAL and journal. */
final class BankingCrypto {
    static byte[] encrypt(SecretKey key, String identity, JSONObject data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        byte[] plain = data.toString().getBytes(StandardCharsets.UTF_8), encrypted;
        try { encrypted = cipher.doFinal(plain); } finally { Arrays.fill(plain, (byte) 0); }
        byte[] result = new byte[13 + encrypted.length]; result[0] = 1;
        System.arraycopy(cipher.getIV(), 0, result, 1, 12); System.arraycopy(encrypted, 0, result, 13, encrypted.length);
        return result;
    }
    static JSONObject decrypt(SecretKey key, String identity, byte[] data) throws Exception {
        if (data.length < 30 || data[0] != 1) throw new IllegalArgumentException("Datos cifrados no válidos.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 1, 12));
        cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(data, 13, data.length - 13);
        try { return new JSONObject(new String(plain, StandardCharsets.UTF_8)); } finally { Arrays.fill(plain, (byte) 0); }
    }
}
