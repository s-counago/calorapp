package com.sejio.calorapp;

import java.net.URI;

/** Only these banks can supply pages to the local reader. No caller-supplied URLs. */
enum BankProvider {
    ABANCA("abanca", "ABANCA", "https://bancaelectronica.abanca.com/",
            new String[]{"bancaelectronica.abanca.com", "be.abanca.com"}),
    TRADE_REPUBLIC("trade_republic", "Trade Republic", "https://app.traderepublic.com/",
            new String[]{"app.traderepublic.com"});

    final String id, label, home;
    private final String[] hosts;

    BankProvider(String id, String label, String home, String[] hosts) {
        this.id = id; this.label = label; this.home = home; this.hosts = hosts;
    }

    String profileName() { return "banking_v1_" + id; }

    boolean allows(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            for (String host : hosts) if (host.equalsIgnoreCase(uri.getHost())) return true;
        } catch (Exception ignored) { }
        return false;
    }

    static BankProvider fromId(String id) {
        for (BankProvider bank : values()) if (bank.id.equals(id)) return bank;
        throw new IllegalArgumentException("Banco desconocido");
    }
}
