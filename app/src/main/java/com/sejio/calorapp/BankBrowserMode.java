package com.sejio.calorapp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Diagnostic modes. A and B differ only in the browser's identification. */
enum BankBrowserMode {
    MOBILE("A · Móvil", false, false),
    DESKTOP_ID("B · Identificación PC", true, false),
    DESKTOP_WIDE("C · PC + pantalla ancha", true, true);

    final String label;
    final boolean desktop;
    final boolean wide;

    BankBrowserMode(String label, boolean desktop, boolean wide) {
        this.label = label;
        this.desktop = desktop;
        this.wide = wide;
    }

    static BankBrowserMode fromOrdinal(int value) {
        return value >= 0 && value < values().length ? values()[value] : MOBILE;
    }

    static String chromeVersion(String original) {
        Matcher version = Pattern.compile("Chrome/([0-9.]+)").matcher(original);
        if (!version.find()) throw new IllegalArgumentException("Versión de WebView no reconocida");
        return version.group(1);
    }

    static String desktopUserAgent(String original) {
        // Retain the installed engine's version, rather than claiming a hard-coded one.
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + chromeVersion(original) + " Safari/537.36";
    }

    String profileName(BankProvider bank) {
        return bank == BankProvider.TRADE_REPUBLIC
                ? bank.profileName() + "_ua_" + ordinal() : bank.profileName();
    }
}
