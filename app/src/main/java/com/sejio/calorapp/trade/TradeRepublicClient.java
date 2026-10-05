package com.sejio.calorapp.trade;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.ByteString;

/** Native read client. Protocol reference: pytr e7f3ba3; no Python code is executed. */
public final class TradeRepublicClient implements AutoCloseable {
    public interface Progress { void changed(String message); }
    private Progress progress = message -> {};
    public synchronized void setProgress(Progress progress) { this.progress = progress; }
    public interface Store {
        JSONObject load() throws Exception;
        void save(JSONObject data) throws Exception;
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String FALLBACK_VERSION = "2.2640.20";
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private final Store store;
    private final HttpUrl api;
    private final HttpUrl frontend;
    private final String userAgent;
    private final TradeCookies cookies;
    private final OkHttpClient http;
    private JSONObject state;
    private String securitiesAccount = "";

    public TradeRepublicClient(Store store, String userAgent, JSONObject device) throws Exception {
        this(store, userAgent, device, HttpUrl.get("https://api.traderepublic.com/"),
                HttpUrl.get("https://app.traderepublic.com/login"), new OkHttpClient.Builder());
    }

    // Package-private transport injection, used only by local tests; no configurable production hosts.
    TradeRepublicClient(Store store, String userAgent, JSONObject device, HttpUrl api,
                        HttpUrl frontend, OkHttpClient.Builder builder) throws Exception {
        this.store = store;
        this.api = api;
        this.frontend = frontend;
        this.userAgent = userAgent;
        try { state = store.load(); }
        catch (Exception error) { throw storageError(); }
        if (state == null) {
            byte[] random = new byte[64]; new SecureRandom().nextBytes(random);
            state = new JSONObject().put("schema", 1).put("deviceId", ByteString.of(random).hex());
        }
        if (state.optInt("schema") != 1 || !state.optString("deviceId").matches("[0-9a-f]{128}")) throw storageError();
        if (!state.has("deviceInfo")) {
            JSONObject identity = new JSONObject(device.toString()).put("stableDeviceId", state.getString("deviceId"));
            state.put("deviceInfo", ByteString.encodeUtf8(identity.toString()).base64());
        }
        cookies = new TradeCookies(api);
        cookies.restore(state.optJSONArray("cookies"));
        http = builder.cookieJar(cookies).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
                .webSocketCloseTimeout(2, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build();
    }

    public synchronized JSONObject view() throws Exception {
        JSONObject result = new JSONObject().put("connected", state.optBoolean("connected"))
                .put("pending", state.has("processId")).put("authenticator", "AUTHENTICATOR_VERIFICATION".equals(state.optString("requiredAction")))
                .put("expiresAt", state.optLong("expiresAt"));
        if (state.has("snapshot")) result.put("snapshot", new JSONObject(state.getJSONObject("snapshot").toString()));
        if (state.has("portfolio")) result.put("portfolio", new JSONObject(state.getJSONObject("portfolio").toString()));
        return result;
    }

    public synchronized void beginLogin(String phone, String pin) throws Exception {
        if (!phone.matches("\\+[1-9][0-9]{6,14}") || !pin.matches("[0-9]{4}"))
            throw new TradeException("INPUT", "Introduce el teléfono con prefijo internacional y el PIN de Trade Republic de 4 cifras.");
        if (state.has("processId") && state.optLong("expiresAt") > System.currentTimeMillis())
            throw new TradeException("PENDING", "Ya hay un acceso pendiente. Confírmalo o cancélalo antes de empezar otro.");
        // Explicit new connection; never mix snapshots or cookies from different accounts.
        cookies.clear(); clearProcess(); state.remove("snapshot"); state.remove("portfolio"); state.remove("instruments");
        state.remove("valuationAttempt"); securitiesAccount = "";
        state.put("connected", false); persist();
        String version = fetchAppVersion();
        state.put("appVersion", version); persist();
        JSONObject response = request("POST", "/api/v2/auth/web/login",
                new JSONObject().put("phoneNumber", phone).put("pin", pin), true);
        // No phone or PIN is retained in state, preferences, diagnostics or logs.
        if (response.isNull("processId") && response.has("processId")) { verifySession(); return; }
        String process = response.optString("processId");
        if (!process.matches("[A-Za-z0-9_-]{1,200}")) throw TradeException.protocol();
        state.put("processId", process).put("expiresAt", System.currentTimeMillis()
                + Math.max(1, Math.min(600, response.optInt("countdownInSeconds", 120))) * 1000L);
        persist(); // Commit the process before the next network request or any Activity transition.
        checkLogin();
    }

    public synchronized void checkLogin() throws Exception {
        String path = processPath();
        // Approval may have succeeded before a network failure or process recreation.
        if (state.optBoolean("approved")) { verifySession(); return; }
        if (System.currentTimeMillis() >= state.optLong("expiresAt")) {
            clearProcess(); persist();
            throw new TradeException("EXPIRED", "El intento de acceso ha caducado. Inicia uno nuevo.");
        }
        JSONObject response = request("GET", path, null, true);
        long deadline = deadline(response.opt("expiresAt"));
        if (deadline > 0) state.put("expiresAt", Math.min(state.getLong("expiresAt"), deadline));
        String action = response.optString("requiredAction");
        if (!action.isEmpty() && action.length() < 100) state.put("requiredAction", action);
        String status = response.optString("status");
        if (status.equals("CONFIRMED") || status.equals("COMPLETED") || status.equals("APPROVED")) {
            state.put("approved", true); state.remove("requiredAction"); persist();
            verifySession(); // Approval alone is not proof of a usable authenticated session.
        } else if (status.equals("PENDING")) { persist(); }
        else {
            clearProcess(); persist();
            throw new TradeException("LOGIN_ENDED", "El banco ha cerrado o rechazado este intento. Puedes iniciar otro.");
        }
    }

    public synchronized void verifyAuthenticator(String code) throws Exception {
        if (!"AUTHENTICATOR_VERIFICATION".equals(state.optString("requiredAction")) || !code.matches("[0-9]{6,8}"))
            throw new TradeException("CODE", "Introduce el código de tu aplicación de autenticación.");
        if (System.currentTimeMillis() >= state.optLong("expiresAt")) {
            clearProcess(); persist();
            throw new TradeException("EXPIRED", "El intento de acceso ha caducado. Inicia uno nuevo.");
        }
        request("POST", processPath() + "/authenticator-verification", new JSONObject().put("code", code), true);
        state.put("approved", true); state.remove("requiredAction"); persist();
        verifySession();
    }

    public synchronized void cancelLogin() throws Exception {
        clearProcess(); cookies.clear(); state.put("connected", false); persist();
    }

    public synchronized void disconnect() throws Exception {
        clearProcess(); cookies.clear(); state.put("connected", false); state.remove("snapshot"); state.remove("portfolio");
        state.remove("instruments"); state.remove("valuationAttempt");
        securitiesAccount = ""; persist();
    }

    public synchronized void verifySession() throws Exception {
        request("GET", "/api/v1/auth/web/session", null, false);
        JSONObject account = request("GET", "/api/v2/auth/account", null, false);
        if (account.length() == 0 || cookies.loadForRequest(api).isEmpty()) throw TradeException.protocol();
        securitiesAccount = account.optString("securitiesAccountNumber", "");
        state.put("connected", true); clearProcess(); persist();
    }

    public synchronized void syncPortfolio() throws Exception {
        syncPortfolio(false);
    }

    public synchronized void syncValuation() throws Exception {
        long now = System.currentTimeMillis();
        if (state.optLong("valuationAttempt") > now - TimeUnit.MINUTES.toMillis(1))
            throw new TradeException("QUOTE_COOLDOWN", "Ya se ha solicitado una valoración hace poco. Espera un minuto antes de repetir.");
        if (state.has("processId")) throw new TradeException("PENDING", "Completa primero la confirmación del acceso.");
        state.put("valuationAttempt", now); persist();
        syncPortfolio(true);
    }

    private void syncPortfolio(boolean valuePositions) throws Exception {
        if (state.has("processId")) throw new TradeException("PENDING", "Completa primero la confirmación del acceso.");
        progress.changed("Comprobando la sesión de Trade Republic…");
        verifySession();
        if (securitiesAccount.isEmpty() || securitiesAccount.equals("null") || securitiesAccount.length() > 100)
            throw new TradeException("NO_SECURITIES_ACCOUNT", "El banco no ha proporcionado una cuenta de valores. Se conserva la cartera anterior.");
        JSONObject portfolio;
        JSONObject instrumentCache = state.optJSONObject("instruments");
        try (TradeSocket socket = new TradeSocket(http, new Request.Builder().url(api).header("User-Agent", userAgent).build())) {
            socket.connect();
            progress.changed("Consultando las posiciones actuales…");
            JSONArray positions;
            try { positions = TradePortfolio.normalize(socket.readPortfolio(securitiesAccount)); }
            catch (org.json.JSONException error) { throw TradeException.protocol(); }
            portfolio = new JSONObject().put("capturedAt", System.currentTimeMillis())
                    .put("accountId", ByteString.encodeUtf8(securitiesAccount).sha256().hex()).put("positions", positions);
            if (valuePositions) {
                instrumentCache = TradeValuation.collect(positions, instrumentCache, new TradeValuation.Source() {
                    int quotes;
                    @Override public Object instrument(String id) throws Exception {
                        progress.changed("Consultando los datos del instrumento…");
                        return socket.readInstrument(id);
                    }
                    @Override public Object ticker(String id, String exchange) throws Exception {
                        progress.changed("Consultando cotización " + (++quotes) + " (máximo 20)…");
                        return socket.readTicker(id, exchange);
                    }
                }, System.currentTimeMillis());
                portfolio.put("valuationAt", System.currentTimeMillis()).put("totals", TradeValuation.totals(positions));
            }
        }
        JSONObject previous = state.optJSONObject("portfolio");
        JSONObject previousCache = state.optJSONObject("instruments");
        state.put("portfolio", portfolio);
        if (valuePositions) state.put("instruments", instrumentCache);
        try { persist(); }
        catch (Exception error) {
            if (previous == null) state.remove("portfolio"); else state.put("portfolio", previous);
            if (previousCache == null) state.remove("instruments"); else state.put("instruments", previousCache);
            throw error;
        }
    }

    public synchronized void sync() throws Exception {
        if (state.has("processId")) throw new TradeException("PENDING", "Completa primero la confirmación del acceso.");
        verifySession();
        JSONObject snapshot = new JSONObject().put("capturedAt", System.currentTimeMillis());
        if (!securitiesAccount.isEmpty()) snapshot.put("accountId", ByteString.encodeUtf8(securitiesAccount).sha256().hex());
        Request request = new Request.Builder().url(api).header("User-Agent", userAgent).build();
        try (TradeSocket socket = new TradeSocket(http, request)) {
            socket.connect();
            Object cash = socket.read("cash", null);
            if (!(cash instanceof JSONArray) || ((JSONArray) cash).length() == 0) throw TradeException.protocol();
            JSONArray balances = new JSONArray();
            for (int i = 0; i < ((JSONArray) cash).length(); i++) {
                JSONObject item = ((JSONArray) cash).getJSONObject(i);
                String amount = decimal(item.get("amount"));
                String currency = item.getString("currencyId");
                if (!currency.matches("[A-Z]{3}")) throw TradeException.protocol();
                balances.put(new JSONObject().put("amount", amount).put("currency", currency));
            }
            snapshot.put("balances", balances);
            snapshot.put("transactions", readTimeline(socket, "timelineTransactions"));
            snapshot.put("activity", readTimeline(socket, "timelineActivityLog"));
        }
        JSONObject previous = state.optJSONObject("snapshot");
        state.put("snapshot", snapshot);
        try { persist(); }
        catch (Exception error) { if (previous == null) state.remove("snapshot"); else state.put("snapshot", previous); throw error; }
    }

    private JSONObject readTimeline(TradeSocket socket, String topic) throws Exception {
        LinkedHashMap<String, JSONObject> items = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String after = null;
        boolean complete = false;
        // Bounded initial history, explicitly labelled. Never silently presented as all history.
        for (int page = 0; page < 5; page++) {
            Object raw = socket.read(topic, after);
            if (!(raw instanceof JSONObject)) throw TradeException.protocol();
            JSONObject data = (JSONObject) raw;
            JSONArray rows = data.getJSONArray("items");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = row.getString("id");
                if (id.isEmpty() || id.length() > 200) throw TradeException.protocol();
                if (row.optBoolean("deleted") || row.optBoolean("hidden")) { items.remove(id); continue; }
                if (!items.containsKey(id) && items.size() >= 500) break;
                items.put(id, new JSONObject().put("id", id).put("timestamp", text(row, "timestamp", 80))
                        .put("title", text(row, "title", 300)).put("subtitle", text(row, "subtitle", 300))
                        .put("status", text(row, "status", 80))
                        .put("eventType", text(row, "eventType", 100)).put("amount", normalizedAmount(row.optJSONObject("amount"))));
            }
            JSONObject cursor = data.getJSONObject("cursors");
            String next = cursor.isNull("after") ? null : cursor.optString("after", null);
            if (next == null || next.isEmpty()) { complete = rows.length() == 0 || items.size() < 500; break; }
            if (next.length() > 2048 || !cursors.add(next)) throw TradeException.protocol();
            after = next;
            if (items.size() >= 500) break;
        }
        JSONArray rows = new JSONArray();
        for (JSONObject item : items.values()) rows.put(item);
        return new JSONObject().put("items", rows).put("complete", complete);
    }

    private static Object normalizedAmount(JSONObject amount) throws Exception {
        if (amount == null) return JSONObject.NULL;
        String currency = amount.optString("currencyId");
        if (currency.isEmpty()) currency = amount.optString("currency");
        if (!currency.matches("[A-Z]{3}") || !amount.has("value")) return JSONObject.NULL;
        return new JSONObject().put("value", decimal(amount.get("value"))).put("currency", currency);
    }

    static String decimal(Object raw) throws TradeException {
        try {
            String text = String.valueOf(raw);
            if (text.length() > 80) throw TradeException.protocol();
            BigDecimal value = new BigDecimal(text);
            if (value.precision() > 40 || Math.abs((long) value.scale()) > 20) throw TradeException.protocol();
            return value.toPlainString();
        } catch (NumberFormatException error) { throw TradeException.protocol(); }
    }

    @Override public synchronized void close() {
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }

    private static String text(JSONObject object, String key, int max) throws Exception {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return "";
        if (!(value instanceof String) || ((String) value).length() > max) throw TradeException.protocol();
        return (String) value;
    }

    private String fetchAppVersion() {
        try (Response response = http.newCall(new Request.Builder().url(frontend).header("User-Agent", userAgent).build()).execute()) {
            if (response.code() == 200 && response.body() != null) {
                String html = response.peekBody(256 * 1024).string();
                Matcher m = Pattern.compile("<meta\\s+name=[\"']app-version[\"']\\s+content=[\"']([0-9.]{3,40})[\"']").matcher(html);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) { /* No credentials on this public, optional request. */ }
        return state.optString("appVersion", FALLBACK_VERSION);
    }

    private JSONObject request(String method, String path, JSONObject body, boolean login) throws Exception {
        HttpUrl url = api.resolve(path);
        if (url == null || !url.host().equals(api.host()) || url.port() != api.port() || !url.scheme().equals(api.scheme()))
            throw TradeException.protocol();
        Request.Builder builder = new Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json");
        if (login) builder.header("X-TR-Device-Info", state.getString("deviceInfo"))
                .header("X-TR-App-Version", state.optString("appVersion", FALLBACK_VERSION))
                .header("X-Tr-Platform", "web-pro").header("Accept-Language", "es");
        if (method.equals("POST")) builder.post(RequestBody.create(body.toString(), JSON));
        try (Response response = http.newCall(builder.build()).execute()) {
            persist(); // Keep rotated cookies, including after a failed request.
            if (response.code() >= 300 && response.code() < 400)
                throw new TradeException("REDIRECT", "El banco ha pedido una redirección no prevista. No se han reenviado las credenciales.");
            String raw = response.body() == null ? "" : response.peekBody(MAX_RESPONSE + 1L).string();
            if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RESPONSE) throw TradeException.protocol();
            JSONObject parsed = null;
            try { if (!raw.trim().isEmpty()) parsed = new JSONObject(raw); } catch (Exception ignored) { }
            if (!response.isSuccessful()) throw apiError(response.code(), parsed);
            if (raw.trim().isEmpty()) return new JSONObject();
            if (parsed == null) throw TradeException.protocol();
            return parsed;
        } catch (TradeException error) { throw error; }
        catch (IOException error) { throw TradeException.network(); }
    }

    private TradeException apiError(int status, JSONObject body) throws Exception {
        String code = "";
        if (body != null) {
            JSONArray errors = body.optJSONArray("errors");
            if (errors != null && errors.length() > 0 && errors.optJSONObject(0) != null)
                code = errors.optJSONObject(0).optString("errorCode");
        }
        if (status == 429 || code.equals("TOO_MANY_REQUESTS"))
            return new TradeException("RATE_LIMIT", "Trade Republic limita los intentos. Espera unos minutos antes de volver a probar.");
        if (code.equals("PROCESS_GONE") || code.equals("ALREADY_PROCESSED") || code.equals("NOT_FOUND")) {
            clearProcess(); persist();
            return new TradeException("EXPIRED", "El intento ha caducado, se ha rechazado o ya fue utilizado. Inicia uno nuevo.");
        }
        if (code.equals("VALIDATION_CODE_INVALID") || code.equals("VALIDATION_CODE_ALREADY_USED"))
            return new TradeException("CODE", "El código no es válido o ya se utilizó. Comprueba tu aplicación de autenticación.");
        if (status == 401) {
            state.put("connected", false); clearProcess(); cookies.clear(); persist();
            return new TradeException("AUTH", "El banco no acepta las credenciales o la sesión ha caducado. Vuelve a conectar.");
        }
        if (status == 403 || status == 405)
            return new TradeException("ACCESS", "Trade Republic ha rechazado este acceso directo. No se reintentará automáticamente.");
        if (code.equals("MISSING_REQUIRED_HEADER"))
            return new TradeException("PROTOCOL", "El banco requiere datos de identificación distintos. Hay que actualizar el cliente.");
        return new TradeException("HTTP_" + status, "La operación no se pudo completar (HTTP " + status + "). No se ha iniciado otro acceso.");
    }

    private String processPath() throws Exception {
        String id = state.optString("processId");
        if (!id.matches("[A-Za-z0-9_-]{1,200}")) throw new TradeException("NO_PROCESS", "Inicia primero el acceso a Trade Republic.");
        return "/api/v2/auth/web/login/processes/" + id;
    }

    private void clearProcess() { state.remove("processId"); state.remove("expiresAt"); state.remove("requiredAction"); state.remove("approved"); }

    private void persist() throws TradeException {
        try { state.put("cookies", cookies.export()); store.save(new JSONObject(state.toString())); }
        catch (Exception error) { throw storageError(); }
    }

    private static TradeException storageError() {
        return new TradeException("STORAGE", "No se pudo leer o guardar la sesión cifrada. No se guardará sin cifrar.");
    }

    static long deadline(Object value) {
        try {
            if (value instanceof Number) {
                double n = ((Number) value).doubleValue();
                return (long) (n < 100000000000.0 ? n * 1000 : n);
            }
            if (!(value instanceof String)) return 0;
            String text = ((String) value).replaceFirst("\\.\\d+", "");
            if (text.endsWith("Z")) text = text.substring(0, text.length() - 1) + "+0000";
            else text = text.replaceFirst("([+-][0-9]{2}):([0-9]{2})$", "$1$2");
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT);
            format.setLenient(false);
            return format.parse(text).getTime();
        } catch (Exception ignored) { return 0; }
    }
}
