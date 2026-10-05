package com.sejio.calorapp;

/** Pure JVM policy checks; run tools/test-banking-policy.sh. */
public final class BankProviderTest {
    public static void main(String[] args) {
        for (BankProvider bank : BankProvider.values()) {
            check(bank.allows(bank.home));
            check(!bank.allows("http://" + java.net.URI.create(bank.home).getHost()));
            check(!bank.allows(bank.home.replace("https://", "https://attacker@")));
            check(!bank.allows(bank.home.replace(".com/", ".com.evil.example/")));
            check(!bank.allows(bank.home.replace(".com/", ".com:8443/")));
            check(!bank.allows("javascript:alert(1)"));
            check(!bank.allows("file:///data/data/app"));
            check(!bank.allows("https://evil.example/?next=" + bank.home));
            check(!bank.allows(null));
            check(BankProvider.fromId(bank.id) == bank);
        }
        check(!BankProvider.ABANCA.allows(BankProvider.TRADE_REPUBLIC.home));
        check(!BankProvider.ABANCA.profileName().equals(BankProvider.TRADE_REPUBLIC.profileName()));
        check(BankProvider.ABANCA.allows("https://be.abanca.com/"));
        try { BankProvider.fromId("../renfe_sergio"); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PASS: bank origin allowlist, profile separation and provider validation");
    }
    private static void check(boolean result) { if (!result) throw new AssertionError(); }
}
