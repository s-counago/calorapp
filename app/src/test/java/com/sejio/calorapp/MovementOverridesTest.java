package com.sejio.calorapp;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class MovementOverridesTest {
    private Ledger.Txn movement(String id, long amount) {
        return new Ledger.Txn(id, "abanca", "card", "Tarjeta crédito", Ledger.parseIso("2026-10-04"), Ledger.parseIso("2026-10-04"), -amount, "GOOGLE PLAY", Ledger.Kind.SPEND, "apps");
    }

    @Test public void renamingOnePaymentPreservesItsRawConceptAndSibling() throws Exception {
        Budget.Settings settings = new Budget.Settings();
        settings.movementNames.put("first", "Mi música");
        settings = Budget.Settings.fromJson(settings.toJson());
        List<Budget.Entry> result = Budget.resolve(Arrays.asList(movement("first", 649), movement("second", 649)), settings);
        assertEquals("Mi música", result.get(0).label());
        assertNotEquals("Mi música", result.get(1).label());
        assertEquals("GOOGLE PLAY", result.get(0).txn.raw);
        assertEquals(result.get(0).merchantKey, result.get(1).merchantKey);
    }

    @Test public void sameMerchantIsSeparatedByInclusiveAmountRanges() throws Exception {
        Budget.Settings settings = new Budget.Settings();
        settings.rules.add(new Budget.Rule("music", "Música", "music", 649, 4, "GOOGLE PLAY").range(600, 700));
        settings.rules.add(new Budget.Rule("video", "Vídeo", "play", 1299, 4, "GOOGLE PLAY").range(1200, 1400));
        settings = Budget.Settings.fromJson(settings.toJson());
        List<Budget.Entry> result = Budget.resolve(Arrays.asList(movement("a", 600), movement("b", 700), movement("c", 1299), movement("d", 999)), settings);
        assertEquals("music", result.get(0).rule.id);
        assertEquals("music", result.get(1).rule.id);
        assertEquals("video", result.get(2).rule.id);
        assertNull(result.get(3).rule);
    }
}
