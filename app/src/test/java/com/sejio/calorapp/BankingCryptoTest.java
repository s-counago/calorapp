package com.sejio.calorapp;

import org.json.JSONObject;
import org.junit.Test;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class BankingCryptoTest {
    private final SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
    @Test public void financialTextIsEncryptedAndRoundTrips() throws Exception {
        JSONObject data = new JSONObject().put("description", "FictionalPrivatePurchase").put("amount", "-12.50");
        byte[] encrypted = BankingCrypto.encrypt(key, "captures:test", data);
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("FictionalPrivatePurchase"));
        assertEquals(data.toString(), BankingCrypto.decrypt(key, "captures:test", encrypted).toString());
    }
    @Test public void tamperingAndMovingCiphertextBetweenRowsAreRejected() throws Exception {
        byte[] encrypted = BankingCrypto.encrypt(key, "captures:a", new JSONObject().put("value", "test"));
        try { BankingCrypto.decrypt(key, "captures:b", encrypted); fail(); } catch (javax.crypto.AEADBadTagException expected) { }
        encrypted[encrypted.length - 1] ^= 1;
        try { BankingCrypto.decrypt(key, "captures:a", encrypted); fail(); } catch (javax.crypto.AEADBadTagException expected) { }
    }
}
