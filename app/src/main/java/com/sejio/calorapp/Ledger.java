package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Bank movements reduced to one comparable shape: a local day, signed cents, a readable merchant and
 * what kind of money it is. Pure Java so the budget can be verified on the JVM.
 */
final class Ledger {
    enum Kind { SPEND, REFUND, INCOME, TRANSFER, INVEST, IGNORED }

    static final class Txn {
        final String id, bank, source, sourceLabel, raw, merchant, merchantKey;
        final int day;
        final long cents;
        /** Account value date, used only to pair card charges with their account copy. */
        final int valueDay;
        Kind kind;
        String category;
        boolean salary, duplicate;

        Txn(String id, String bank, String source, String sourceLabel, int day, int valueDay, long cents, String raw, Kind kind, String category) {
            this.id = id; this.bank = bank; this.source = source; this.sourceLabel = sourceLabel;
            this.day = day; this.valueDay = valueDay; this.cents = cents; this.raw = raw;
            this.merchant = pretty(raw); this.merchantKey = key(merchant);
            this.kind = kind; this.category = category;
        }

        /** Searchable text: the original concept plus its readable form. */
        String haystack() { return key(raw) + " " + merchantKey; }
    }

    // ------------------------------------------------------------ categories

    static final class Category {
        final String id, label, symbol;
        final int color;
        final String[] keywords;
        Category(String id, String label, String symbol, int color, String... keywords) {
            this.id = id; this.label = label; this.symbol = symbol; this.color = color; this.keywords = keywords;
        }
    }

    /** Order matters: the first category whose keyword appears wins. */
    static final Category[] CATEGORIES = {
        new Category("cafe", "Cafés y bares", "coffee", 0xFFB0782A, "CRUCENA", "CAFE", "CAFETERIA", "VENDING", "COFFEE", " BAR ", "BEAR TEA",
                "TAPA", "TABERNA", "CERVEC", " PUB ", "SHAM ROCK", "JAMONERIA", "LA CAMPANA", "FUSION M", "PICNIC", "DIN DON", "CASTRO SA"),
        new Category("tabaco", "Tabaco", "cigarette", 0xFF7D756A, "ESTANCO", "EXPENDEDURIA", "TABAC"),
        new Category("super", "Súper", "cart", 0xFF50684C, "MERCADONA", "DIA RETAIL", "MP DIA", "CARREFOUR", "COVIRAN", "MERCATODO",
                "LIDL", "ALCAMPO", "GADIS", "FROIZ", "EROSKI", "FAMILIA ARCADE", "SUPERMERC", "MIEL ORO", "HIPER"),
        new Category("comer", "Comer fuera", "fork", 0xFFAA4C30, "RAMEN", "KEBAP", "KEBAB", "DONER", "POPEYES", "PIZZA", "JUST EAT",
                "GLOVO", "WETACA", "MOMO", "RESTAURAN", "HORNO", "BURGER", "MCDONALD", "XEADO", "TARTAS", "UBER EATS", "SUSHI"),
        new Category("transporte", "Transporte", "train", 0xFF3E6A73, "RENFE", "MONFOBUS", "BOLT", "TAXI", "XG 817", "BLABLACAR",
                "AUDASA", "PARKING", "GALURESA", "REPSOL", "CEPSA", "CABIFY", "UBER", "ALSA", "AUTOBUS", "TUSSA", "A CORUNA SANTIAGO"),
        new Category("apps", "Apps y digital", "play", 0xFF6A5A8C, "GOOGLE", "ANTHROPIC", "CLAUDE", "CLOUDFLARE", "OBSIDIAN", "Z AI",
                "ANOMALY", "X CORP", "NETFLIX", "SPOTIFY", "AMAZON PRIME", "APPLE", "STEAM", "OPENAI", "SIMYO", "RECARGAS"),
        new Category("compras", "Compras", "bag", 0xFF9C5B6E, "AMAZON", "PULL AND BEAR", "SPRINTER", "IKEA", "HSN", "TIGER", "ESPADESA",
                "VINTAGE", "FOTO", "LAPICES", "ALTAIRA", "ZARA", "DECATHLON", "MR STOVE", "PRIMARK", "MARIKILLA", "TMG ECOMM"),
        new Category("ocio", "Ocio", "ticket", 0xFFD39A2C, "WEEZEVENT", "WOUTICK", "PELICANO", "TICKET", "CINE", "MAKUMBA", "ARCADE",
                "CONCIERTO", "TEATRO", "MUSEO"),
        new Category("regalos", "Regalos y detalles", "gift", 0xFFC0727C, "FLOR", "REGALO", "SUPEREGALO", "SUPERREGALO"),
        new Category("personas", "Bizum y personas", "people", 0xFF7F8F52, "BIZUM"),
        new Category("efectivo", "Efectivo", "cash", 0xFF626960, "RETIRO", "HAL CASH", "CAJERO", "ATM"),
        new Category("deudas", "Deudas", "card", 0xFF183C30, "AMORTIZACION DEUDA", "CARGO PRESTAMO", "SEGURO PROT"),
        new Category("otros", "Otros", "spark", 0xFFA39E92),
    };

    static Category category(String id) {
        for (Category category : CATEGORIES) if (category.id.equals(id)) return category;
        return CATEGORIES[CATEGORIES.length - 1];
    }

    static String guessCategory(String haystack) {
        String padded = " " + haystack + " ";
        for (Category category : CATEGORIES)
            for (String keyword : category.keywords) if (padded.contains(keyword)) return category.id;
        return "otros";
    }

    // ------------------------------------------------------------ normalization

    private static final Locale SPANISH = new Locale("es", "ES");
    private static final Pattern CARD_PREFIX = Pattern.compile("^\\d{12}\\s+");
    private static final Pattern OWN_TRANSFER = Pattern.compile("^(TRADE( REPUBLIC)?|PAGO( TJ| TARJETA)?|TRASPASO.*|TRANSFERENCIA PROPIA.*)$");

    /** Rows from {@link BankingDatabase#movements()}: {id, bank, productType, productLabel, data}. */
    static List<Txn> fromDatabase(JSONArray rows, TimeZone zone) {
        List<Txn> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                Txn txn = parse(rows.getJSONObject(i), zone);
                if (txn != null) result.add(txn);
            } catch (Exception ignored) {
                // One unreadable movement never hides the rest of the ledger.
            }
        }
        detectSalary(result);
        pairCardCharges(result);
        Collections.sort(result, (a, b) -> a.day != b.day ? Integer.compare(b.day, a.day) : a.id.compareTo(b.id));
        return result;
    }

    static Txn parse(JSONObject row, TimeZone zone) throws Exception {
        JSONObject data = row.getJSONObject("data");
        String bank = row.optString("bank"), type = row.optString("productType");
        JSONObject amount = data.optJSONObject("amount");
        if (amount == null) return null;
        String currency = amount.optString("currency", "EUR");
        if (!currency.isEmpty() && !currency.equals("null") && !currency.equals("EUR")) return null;
        String value = amount.has("amount") ? amount.getString("amount") : amount.optString("value");
        if (value.isEmpty()) return null;
        long cents = cents(value);
        String id = row.getString("id");
        String label = row.optString("productLabel");
        if (bank.equals("trade_republic")) {
            String occurred = data.optString("occurredAt", data.optString("timestamp"));
            if (occurred.length() < 10) return null;
            int day = localDay(occurred, zone);
            String title = data.optString("description", data.optString("title"));
            Kind kind = tradeKind(data.optString("eventType"), data.optString("status"), data.optString("subtitle"), cents);
            Txn txn = new Txn(id, bank, "trade", "Trade Republic", day, day, cents, title, kind, null);
            txn.category = kind == Kind.SPEND && data.optString("eventType").toLowerCase(Locale.ROOT).contains("atm") ? "efectivo" : guessCategory(txn.haystack());
            return txn;
        }
        int day = parseIso(data.getString("operationDate"));
        String valueDate = data.optString("valueDate");
        int valueDay = valueDate.length() >= 10 && !valueDate.equals("null") ? parseIso(valueDate) : day;
        String description = data.optString("description");
        if (type.equals("card")) {
            Kind kind = cardKind(data.optString("operationType"), cents);
            Txn txn = new Txn(id, bank, "card", label.isEmpty() ? "Tarjeta ABANCA" : label, day, valueDay, cents, description, kind, null);
            txn.category = guessCategory(txn.haystack());
            return txn;
        }
        Kind kind = accountKind(description, cents);
        Txn txn = new Txn(id, bank, "account", label.isEmpty() ? "Cuenta ABANCA" : label, day, valueDay, cents, description, kind, null);
        txn.salary = kind == Kind.INCOME && key(description).contains("NOMINA");
        txn.category = guessCategory(txn.haystack());
        return txn;
    }

    static Kind tradeKind(String eventType, String status, String subtitle, long cents) {
        String event = eventType.toLowerCase(Locale.ROOT), state = status.toLowerCase(Locale.ROOT);
        if (state.contains("cancel") || state.contains("fail") || state.contains("reject") || event.contains("failed")) return Kind.IGNORED;
        if (event.contains("card")) return cents < 0 ? Kind.SPEND : Kind.REFUND;
        if (event.contains("saveback") || event.contains("spare_change") || event.contains("savings_plan") || event.contains("savingsplan")
                || event.contains("order") || event.contains("trade"))
            return cents < 0 ? Kind.INVEST : Kind.IGNORED;
        if (event.contains("interest") || event.contains("dividend") || event.contains("coupon") || event.contains("tax")) return Kind.IGNORED;
        if (event.contains("inbound") || event.contains("incoming") || event.contains("outbound") || event.contains("outgoing")
                || event.contains("transfer") || event.contains("payment")) return Kind.TRANSFER;
        String hint = key(subtitle);
        if (hint.contains("TRANSFER")) return Kind.TRANSFER;
        return cents < 0 ? Kind.SPEND : Kind.TRANSFER;
    }

    static Kind cardKind(String operationType, long cents) {
        String type = key(operationType);
        if (type.contains("AMORTIZACION") || type.contains("INGR") || type.contains("TRASPASO")) return Kind.TRANSFER;
        if (type.contains("ABONO")) return Kind.REFUND;
        return cents < 0 ? Kind.SPEND : Kind.REFUND;
    }

    static Kind accountKind(String description, long cents) {
        String text = key(description);
        if (cents > 0) {
            if (text.contains("NOMINA")) return Kind.INCOME;
            if (text.contains("BIZUM") || text.contains("DEVOLUCION") || text.contains("ABONO") || text.contains("REEMBOLSO")) return Kind.REFUND;
            return Kind.TRANSFER;
        }
        if (OWN_TRANSFER.matcher(text).matches()) return Kind.TRANSFER;
        return Kind.SPEND;
    }

    /** Payroll without the word NOMINA: a large account inflow that repeats in two months at a similar amount. */
    private static void detectSalary(List<Txn> txns) {
        for (Txn txn : txns) if (txn.salary) return;
        List<Txn> candidates = new ArrayList<>();
        for (Txn txn : txns) if (txn.source.equals("account") && txn.cents >= 60_000 && txn.kind == Kind.TRANSFER) candidates.add(txn);
        for (Txn a : candidates) {
            for (Txn b : candidates) {
                if (a == b || Math.abs(a.day - b.day) < 20 || !a.merchantKey.equals(b.merchantKey)) continue;
                if (Math.abs(a.cents - b.cents) * 10 > a.cents) continue;
                for (Txn c : candidates) if (c.merchantKey.equals(a.merchantKey)) { c.kind = Kind.INCOME; c.salary = true; }
                return;
            }
        }
    }

    /** Debit-card purchases appear twice: on the card and, days later, in the account. Keep the card copy. */
    private static void pairCardCharges(List<Txn> txns) {
        List<Txn> cards = new ArrayList<>();
        for (Txn txn : txns) if (txn.source.equals("card") && txn.cents < 0) cards.add(txn);
        boolean[] used = new boolean[cards.size()];
        for (Txn txn : txns) {
            if (!txn.source.equals("account") || txn.cents >= 0 || !CARD_PREFIX.matcher(txn.raw.trim()).find()) continue;
            for (int i = 0; i < cards.size(); i++) {
                Txn card = cards.get(i);
                if (used[i] || card.cents != txn.cents || Math.abs(card.day - txn.valueDay) > 3) continue;
                used[i] = true; txn.duplicate = true; txn.kind = Kind.IGNORED; break;
            }
        }
    }

    // ------------------------------------------------------------ text

    static String key(String value) {
        if (value == null) return "";
        String plain = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    private static final String[][] BRANDS = {
        {"NETFLIX", "Netflix"}, {"AMAZON PRIME", "Amazon Prime"}, {"AMAZON", "Amazon"}, {"GOOGLE CLOUD", "Google Cloud"},
        {"GOOGLE PLAY", "Google Play"}, {"ANTHROPIC", "Claude"}, {"CLOUDFLARE", "Cloudflare"}, {"OBSIDIAN", "Obsidian"},
        {"Z AI", "Z.ai"}, {"SPOTIFY", "Spotify"}, {"JUST EAT", "Just Eat"}, {"BOLT EU", "Bolt"}, {"MP DIA", "Dia"},
        {"DIA RETAIL", "Dia"}, {"X CORP", "X"}, {"RENFE VIAJEROS", "Renfe"}, {"RENFE VIRTUAL", "Renfe"}, {"CUENTA PAREJA", "Cuenta de pareja"},
        {"CARGO PRESTAMO", "Cuota del préstamo"}, {"AMORTIZACION DEUDA", "Cuota de la tarjeta"}, {"SEGURO PROT", "Seguro de la tarjeta"},
        {"EVOFIT", "Evofit"}, {"SIMYO", "Simyo"}, {"HAL CASH", "Hal Cash"}, {"COM CAMBIO DIVISA", "Comisión de cambio"},
    };

    /** Readable merchant: no card prefixes, city suffixes or reference codes; brands spelled as people write them. */
    static String pretty(String raw) {
        if (raw == null) return "";
        String text = CARD_PREFIX.matcher(raw.trim()).replaceFirst("");
        String upper = key(text);
        for (String[] brand : BRANDS) if ((" " + upper + " ").contains(" " + brand[0] + " ")) return brand[1];
        if (upper.startsWith("PAGO BIZUM")) {
            String concept = text.replaceFirst("(?i)^PAGO BIZUM\\s*-?\\s*", "").replaceAll("#.*$", "").trim();
            return concept.isEmpty() ? "Bizum" : "Bizum · " + concept;
        }
        if (upper.startsWith("INGRESO BIZUM")) {
            String concept = text.replaceFirst("(?i)^INGRESO BIZUM\\s*-?\\s*", "").replaceAll("#.*$", "").trim();
            return concept.isEmpty() ? "Bizum recibido" : "Bizum · " + concept;
        }
        if (upper.startsWith("ALQUILER")) return "Alquiler";
        int city = text.indexOf('\\');
        if (city > 0) text = text.substring(0, city);
        int cc = text.indexOf(" CC ");
        if (cc > 0) text = text.substring(0, cc);
        String[] words = text.replace('*', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        int first = 0;
        while (first < words.length - 1 && words[first].replaceAll("\\D", "").length() >= 5) first++; // Leading tax or mandate codes.
        for (int i = first; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;
            if (i > first && word.matches(".*\\d.*")) break; // Reference codes end the readable part.
            if (word.matches("(?i)S\\.?L\\.?U?\\.?|S\\.?A\\.?|,")) continue;
            if (out.length() > 0) out.append(' ');
            out.append(word.equals(word.toUpperCase(Locale.ROOT)) && word.length() > 1 ? title(word) : word);
            if (out.length() > 28) break;
        }
        String result = out.toString().replaceAll("[,.-]+$", "").trim();
        return result.isEmpty() ? text.trim() : result;
    }

    private static String title(String word) {
        String lower = word.toLowerCase(SPANISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // ------------------------------------------------------------ numbers and days

    static long cents(String decimal) {
        return new BigDecimal(decimal.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's days_from_civil). */
    static int epochDay(int year, int month, int day) {
        int y = month <= 2 ? year - 1 : year;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** {year, month, day} for an epoch day. */
    static int[] civil(int epochDay) {
        int z = epochDay + 719468;
        int era = (z >= 0 ? z : z - 146096) / 146097;
        int doe = z - era * 146097;
        int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        int mp = (5 * doy + 2) / 153;
        int d = doy - (153 * mp + 2) / 5 + 1;
        int m = mp + (mp < 10 ? 3 : -9);
        return new int[]{yoe + era * 400 + (m <= 2 ? 1 : 0), m, d};
    }

    static int parseIso(String iso) {
        return epochDay(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)), Integer.parseInt(iso.substring(8, 10)));
    }

    static String iso(int day) {
        int[] c = civil(day);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c[0], c[1], c[2]);
    }

    /** 0 = Monday … 6 = Sunday. 1970-01-01 was a Thursday. */
    static int weekday(int day) { return Math.floorMod(day + 3, 7); }

    static int monthLength(int year, int month) {
        return epochDay(month == 12 ? year + 1 : year, month == 12 ? 1 : month + 1, 1) - epochDay(year, month, 1);
    }

    /** The calendar day, in the given zone, of an ISO-8601 instant such as 2026-06-02T13:13:53.606680Z. */
    static int localDay(String instant, TimeZone zone) {
        int day = parseIso(instant);
        if (instant.length() < 19) return day;
        int seconds = Integer.parseInt(instant.substring(11, 13)) * 3600 + Integer.parseInt(instant.substring(14, 16)) * 60
                + Integer.parseInt(instant.substring(17, 19));
        int offset = 0;
        String tail = instant.substring(19).replaceFirst("^\\.\\d+", "");
        if (tail.startsWith("+") || tail.startsWith("-")) {
            String digits = tail.substring(1).replace(":", "");
            if (digits.length() >= 4) offset = (Integer.parseInt(digits.substring(0, 2)) * 3600 + Integer.parseInt(digits.substring(2, 4)) * 60)
                    * (tail.startsWith("-") ? -1 : 1);
        }
        long millis = (day * 86_400L + seconds - offset) * 1000L;
        return (int) Math.floorDiv(millis + zone.getOffset(millis), 86_400_000L);
    }

    private Ledger() { }
}
