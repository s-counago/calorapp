package com.sejio.calorapp;

public final class BankBrowserModeTest {
    public static void main(String[] args) {
        String[] originals = {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A.240505.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.86 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.7339.123 Mobile Safari/537.36"
        };
        for (String original : originals) {
            String pc = BankBrowserMode.desktopUserAgent(original);
            if (pc.contains("Android") || pc.contains("Mobile") || pc.contains("wv") || pc.contains("Version/4.0"))
                throw new AssertionError("Mobile markers remain");
            if (!pc.contains("Chrome/" + BankBrowserMode.chromeVersion(original)))
                throw new AssertionError("Changed actual engine version");
        }
        for (BankBrowserMode a : BankBrowserMode.values()) {
            if (!a.profileName(BankProvider.ABANCA).equals(BankProvider.ABANCA.profileName()))
                throw new AssertionError("Changed ABANCA profile");
            for (BankBrowserMode b : BankBrowserMode.values()) {
                if (a != b && a.profileName(BankProvider.TRADE_REPUBLIC).equals(b.profileName(BankProvider.TRADE_REPUBLIC)))
                    throw new AssertionError("Test modes share sessions");
            }
        }
        if (BankBrowserMode.MOBILE.wide || BankBrowserMode.DESKTOP_ID.wide || !BankBrowserMode.DESKTOP_WIDE.wide)
            throw new AssertionError("A/B must keep the same viewport");
        if (BankBrowserMode.fromOrdinal(-1) != BankBrowserMode.MOBILE
                || BankBrowserMode.fromOrdinal(99) != BankBrowserMode.MOBILE)
            throw new AssertionError("Invalid preference must fall back to mobile");
        System.out.println("PASS: real WebView UA samples, engine version, independent sessions and A/B viewport policy");
    }
}
