package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Offline initial history. Strict observed formats; never evaluates formulas or discards bad rows. */
final class BankingImport {
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ROWS = 5000;
    static final String VERSION = "bank-file-v1";
    private static final String TR_HEADER = "datetime,date,account_type,category,type,asset_class,name,symbol,shares,price,amount,fee,tax,currency,original_amount,original_currency,fx_rate,description,transaction_id,counterparty_name,counterparty_iban,payment_reference,mcc_code";
    private static final String ACCOUNT_HEADER = "Fecha ctble;Fecha valor;Concepto;Importe;Moneda;Saldo;Moneda;Concepto ampliado";
    static final class Invalid extends Exception { Invalid(String message) { super(message); } }
    static final class Parsed {
        String bank, productType, format, encoding, digest, sourceText, first = "", last = "";
        final JSONArray records = new JSONArray(), sourceRows = new JSONArray();
        final Set<String> dates = new HashSet<>();
        JSONObject capture(JSONObject product, long at) throws Exception {
            if (!productType.equals(product.getString("type")) || !bank.equals(product.getString("bank")))
                throw new Invalid("El producto no corresponde al formato del archivo.");
            String id = BankingRecords.hash(VERSION + ":" + bank + ":" + digest);
            JSONObject capture = new JSONObject().put("schema", 1).put("captureId", id).put("bank", bank).put("capturedAt", at)
                    .put("product", new JSONObject(product.toString())).put("transport", "file_import")
                    .put("sourcePath", "local-file/" + format).put("parserVersion", VERSION)
                    .put("importedAt", at).put("sourceObservedAt", JSONObject.NULL).put("sourceHash", digest).put("encoding", encoding).put("sourceText", sourceText)
                    .put("sourceRows", sourceRows).put("importRecords", records).put("partial", true)
                    .put("firstRecordDate", first).put("lastRecordDate", last);
            if (capture.toString().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
                throw new Invalid("El histórico procesado supera 1 MiB. Divide el archivo en periodos más pequeños.");
            return capture;
        }
        String summary() {
            return records.length() + (productType.equals("loan") ? " campos del préstamo" : " movimientos")
                    + (first.isEmpty() ? "" : "\nDesde " + first + " hasta " + last)
                    + "\n" + (bank.equals("abanca") ? "ABANCA" : "Trade Republic");
        }
    }
    static Parsed parse(byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new Invalid("Archivo vacío o mayor de 2 MiB.");
        Parsed out = new Parsed(); out.encoding = "UTF-8";
        try { out.sourceText = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { out.encoding = "windows-1252";
            out.sourceText = Charset.forName(out.encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        if (out.sourceText.indexOf('\0') >= 0) throw new Invalid("El archivo no es texto reconocido.");
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        out.digest = hash.toString();
        String text = out.sourceText.startsWith("\ufeff") ? out.sourceText.substring(1) : out.sourceText;
        String head = text.split("\\r?\\n", 2)[0];
        if (head.equals(ACCOUNT_HEADER)) {
            out.bank = "abanca"; out.productType = "account"; out.format = "abanca-account-csv";
            parseAccount(out, rows(text, ';'));
        } else if (head.equals("\"" + TR_HEADER.replace(",", "\",\"") + "\"") || head.equals(TR_HEADER)) {
            out.bank = "trade_republic"; out.productType = "investment_account"; out.format = "trade-transactions-csv";
            parseTrade(out, rows(text, ','));
        } else if (head.trim().equals("Detalle del préstamo")) {
            out.bank = "abanca"; out.productType = "loan"; out.format = "abanca-loan-text";
            parseLoan(out, rows(text, '\t'));
        } else {
            out.bank = "abanca"; out.productType = "card"; out.format = "abanca-card-tsv";
            parseCard(out, rows(text, '\t'));
        }
        if (out.records.length() > MAX_ROWS) throw new Invalid("El archivo supera los 5000 registros.");
        if (out.records.length() == 0) throw new Invalid("El archivo no contiene registros reconocidos.");
        for (String date : out.dates) { if (out.first.isEmpty() || date.compareTo(out.first) < 0) out.first = date;
            if (out.last.isEmpty() || date.compareTo(out.last) > 0) out.last = date; }
        return out;
    }
    /** RFC-style quoted CSV/TSV, including embedded separators/newlines and trailing empty cells. */
    static List<List<String>> rows(String text, char separator) throws Invalid {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; closed = true; } }
                else cell.append(ch);
            } else if (ch == separator || ch == '\n' || ch == '\r') {
                row.add(cell.toString()); cell.setLength(0); closed = false;
                if (ch != separator) { if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    addRow(rows, row); row = new ArrayList<>(); }
            } else if (ch == '"' && cell.length() == 0 && !closed) quoted = true;
            else { if (closed || ch == '"') throw new Invalid("Comillas incorrectas en el registro " + (rows.size() + 1) + "."); cell.append(ch); }
            if (cell.length() > 16384 || row.size() > 32) throw new Invalid("El archivo supera el tamaño de celda o columnas permitido.");
        }
        if (quoted) throw new Invalid("El archivo tiene un campo entre comillas sin cerrar.");
        if (cell.length() > 0 || closed || !row.isEmpty()) { row.add(cell.toString()); addRow(rows, row); }
        return rows;
    }
    private static void addRow(List<List<String>> rows, List<String> row) throws Invalid {
        if (row.size() == 1 && row.get(0).isEmpty()) return;
        if (rows.size() >= MAX_ROWS + 1) throw new Invalid("El archivo supera los 5000 registros.");
        rows.add(row);
    }
    private static void source(Parsed out, List<String> row, int index) throws Exception {
        out.sourceRows.put(new JSONObject().put("index", index).put("cells", new JSONArray(row)));
    }
    private static void require(boolean valid, int row) throws Invalid {
        if (!valid) throw new Invalid("Formato no reconocido en el registro " + row + ". No se ha importado ninguna fila.");
    }
    private static JSONObject record(Parsed out, int row, String type) throws Exception {
        JSONObject data = new JSONObject().put("entityType", type).put("sourceTable", 0).put("sourceRow", row);
        out.records.put(data); return data;
    }
    private static String date(String raw, String pattern, int row) throws Invalid {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT); format.setLenient(false); format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition pos = new ParsePosition(0); Date parsed = format.parse(raw, pos);
        if (parsed == null || pos.getIndex() != raw.length() || !format.format(parsed).equals(raw)) throw new Invalid("Fecha no reconocida en el registro " + row + ".");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT); iso.setTimeZone(TimeZone.getTimeZone("UTC")); return iso.format(parsed);
    }
    private static String decimal(String raw, boolean spanish, int row) throws Invalid {
        String clean = raw.trim().replace("\u00a0", "").replace("\u2212", "-");
        String pattern = spanish ? "[+-]?(?:[0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]+)(?:,[0-9]{2})?" : "[+-]?[0-9]+(?:\\.[0-9]+)?";
        if (clean.length() > 60 || !clean.matches(pattern)) throw new Invalid("Importe no reconocido en el registro " + row + ".");
        if (spanish) clean = clean.replace(".", "").replace(',', '.');
        return new BigDecimal(clean).toPlainString();
    }
    private static JSONObject money(String raw, String currency, boolean spanish, int row) throws Exception {
        require(currency.matches("[A-Z]{3}"), row);
        return new JSONObject().put("amount", decimal(raw, spanish, row)).put("currency", currency);
    }
    private static void parseAccount(Parsed out, List<List<String>> rows) throws Exception {
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i); source(out, r, i + 1); require(r.size() == 8, i + 1); if (i == 0) continue;
            String day = date(r.get(0), "dd-MM-yyyy", i + 1); require(!r.get(2).trim().isEmpty(), i + 1); out.dates.add(day);
            record(out, i + 1, "movement").put("operationDate", JSONObject.NULL).put("bookingDate", day).put("identityDateBasis", "booking_date").put("valueDate", date(r.get(1), "dd-MM-yyyy", i + 1))
                    .put("description", r.get(2)).put("extendedDescription", r.get(7)).put("amount", money(r.get(3), r.get(4), true, i + 1))
                    .put("balance", money(r.get(5), r.get(6), true, i + 1)).put("merchant", JSONObject.NULL).put("occurredAt", JSONObject.NULL);
        }
    }
    private static void parseCard(Parsed out, List<List<String>> rows) throws Exception {
        require(!rows.isEmpty(), 1); int width = rows.get(0).size(); require(width == 6 || width == 7, 1);
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i); source(out, r, i + 1);
            require(r.size() == width && !r.get(0).trim().isEmpty() && !r.get(2).trim().isEmpty() && !r.get(4).trim().isEmpty(), i + 1);
            String day = date(r.get(1), "dd/MM/yyyy", i + 1), amount = r.get(5).trim(); out.dates.add(day);
            require(amount.matches(".*\\s[A-Z]{3}"), i + 1);
            JSONObject data = record(out, i + 1, "movement").put("holder", r.get(0)).put("operationDate", day)
                    .put("operationType", r.get(2)).put("situation", r.get(3)).put("status", r.get(3)).put("description", r.get(4))
                    .put("amount", money(amount.substring(0, amount.length() - 3).trim(), amount.substring(amount.length() - 3), true, i + 1))
                    .put("merchant", JSONObject.NULL).put("valueDate", JSONObject.NULL).put("occurredAt", JSONObject.NULL);
            if (width == 7) data.put("paymentMode", r.get(6)); // Observed 'Cuota fija', never a payment date.
        }
    }
    private static void parseTrade(Parsed out, List<List<String>> rows) throws Exception {
        List<String> headers = Arrays.asList(TR_HEADER.split(",")); require(rows.get(0).equals(headers), 1);
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i); source(out, r, i + 1); require(r.size() == headers.size(), i + 1); if (i == 0) continue;
            String day = date(r.get(1), "yyyy-MM-dd", i + 1); out.dates.add(day);
            require(!r.get(18).isEmpty() && r.get(18).length() <= 200 && r.get(0).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})"), i + 1);
            date(r.get(0).substring(0, 19), "yyyy-MM-dd'T'HH:mm:ss", i + 1);
            String timestamp = r.get(0);
            if (!timestamp.endsWith("Z")) {
                int hour = Integer.parseInt(timestamp.substring(timestamp.length() - 5, timestamp.length() - 3));
                int minute = Integer.parseInt(timestamp.substring(timestamp.length() - 2));
                require(hour <= 18 && minute < 60 && (hour < 18 || minute == 0), i + 1);
            }
            JSONObject data = record(out, i + 1, "movement").put("bankId", r.get(18)).put("operationDate", day)
                    .put("occurredAt", r.get(0)).put("valueDate", JSONObject.NULL).put("description", r.get(17))
                    .put("amount", money(r.get(10), r.get(13), false, i + 1)).put("merchant", JSONObject.NULL);
            // Preserve every exported field separately, including fee, tax, units and counterparties.
            JSONObject fields = new JSONObject(); for (int c = 0; c < headers.size(); c++) fields.put(headers.get(c), r.get(c));
            data.put("exportFields", fields);
        }
    }
    private static void parseLoan(Parsed out, List<List<String>> rows) throws Exception {
        Set<String> sections = new HashSet<>(Arrays.asList("Datos del préstamo", "Datos generales", "Pendiente de pago", "Condiciones"));
        Set<String> groups = new HashSet<>(Arrays.asList("RECIBOS PENDIENTES DE PAGO", "COMISIONES Y GASTOS PENDIENTES", "TOTAL PENDIENTE (CUOTAS + COMISIONES + GASTOS)"));
        String section = "", group = "";
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i); source(out, r, i + 1); require(r.size() <= 2, i + 1);
            String label = r.get(0).trim(); if (i == 0 && label.equals("Detalle del préstamo")) continue;
            if (sections.contains(label)) { require(r.size() == 1 || r.get(1).trim().isEmpty(), i + 1); section = label; group = ""; continue; }
            if (r.size() == 1 && groups.contains(label) && section.equals("Pendiente de pago")) { group = label; continue; }
            require(!section.isEmpty() && r.size() == 2 && !label.isEmpty() && !r.get(1).trim().isEmpty(), i + 1);
            record(out, i + 1, "loan_term").put("section", section).put("group", group).put("label", label).put("value", r.get(1));
        }
    }
}
