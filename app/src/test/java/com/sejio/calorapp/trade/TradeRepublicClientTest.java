package com.sejio.calorapp.trade;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class TradeRepublicClientTest {
    private MockWebServer server;
    private Memory store;
    private TradeRepublicClient client;
    private static final String PHONE = "+34600000000";
    private static final String PIN = "1357";

    static final class Memory implements TradeRepublicClient.Store {
        JSONObject state;
        volatile boolean fail;
        @Override public JSONObject load() throws Exception { return state == null ? null : new JSONObject(state.toString()); }
        @Override public void save(JSONObject state) throws Exception {
            if (fail) throw new java.io.IOException("private storage details");
            this.state = new JSONObject(state.toString());
        }
    }

    @Before public void setup() throws Exception {
        server = new MockWebServer(); server.start(); store = new Memory(); client = client();
    }
    @After public void tearDown() throws Exception { server.shutdown(); }
    private TradeRepublicClient client() throws Exception {
        return new TradeRepublicClient(store, "Pausa-Test", new JSONObject().put("browser", "test"),
                server.url("/"), server.url("/login"), new OkHttpClient.Builder());
    }
    private void json(String body) { server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body)); }
    private RecordedRequest next() throws Exception {
        RecordedRequest result = server.takeRequest(2, TimeUnit.SECONDS); assertNotNull(result); return result;
    }
    private void pending(String action) throws Exception {
        server.enqueue(new MockResponse().setBody("<meta name=\"app-version\" content=\"2.3000.1\">"));
        server.enqueue(new MockResponse().setHeader("Set-Cookie", "session=TEST_PENDING; HttpOnly; Path=/")
                .setBody("{\"processId\":\"process-test-1\",\"countdownInSeconds\":120}"));
        json("{\"status\":\"PENDING\",\"requiredAction\":\"" + action + "\"}");
        client.beginLogin(PHONE, PIN);
    }
    private void sessionResponses() {
        server.enqueue(new MockResponse().setResponseCode(204).setHeader("Set-Cookie", "session=TEST_CONNECTED; HttpOnly; Path=/"));
        json("{\"securitiesAccountNumber\":\"TEST_ACCOUNT\"}");
    }

    @Test public void loginSurvivesClientRecreationWithoutSavingCredentials() throws Exception {
        pending("DEVICE_CONFIRMATION");
        assertEquals("/login", next().getPath());
        RecordedRequest login = next();
        assertEquals("/api/v2/auth/web/login", login.getPath());
        assertEquals(PIN, new JSONObject(login.getBody().readUtf8()).getString("pin"));
        assertEquals("2.3000.1", login.getHeader("X-TR-App-Version"));
        String device = login.getHeader("X-TR-Device-Info");
        assertEquals("/api/v2/auth/web/login/processes/process-test-1", next().getPath());
        assertFalse(store.state.toString().contains(PHONE)); assertFalse(store.state.toString().contains("\"pin\""));
        assertTrue(client.view().getBoolean("pending"));
        client = client();
        json("{\"status\":\"CONFIRMED\"}"); sessionResponses(); client.checkLogin();
        RecordedRequest resumed = next();
        assertEquals("GET", resumed.getMethod()); assertEquals(device, resumed.getHeader("X-TR-Device-Info"));
        assertEquals("session=TEST_PENDING", resumed.getHeader("Cookie"));
        assertEquals("/api/v1/auth/web/session", next().getPath());
        assertEquals("session=TEST_CONNECTED", next().getHeader("Cookie"));
        assertTrue(client.view().getBoolean("connected")); assertFalse(client.view().getBoolean("pending"));
        assertFalse(client.view().toString().contains("TEST_CONNECTED"));
    }

    @Test public void pendingSurvivesNetworkErrorAfterLoginResponse() throws Exception {
        server.enqueue(new MockResponse().setBody("<meta name=\"app-version\" content=\"2.3.4\">"));
        json("{\"processId\":\"pending-safe\"}");
        server.enqueue(new MockResponse().setResponseCode(503).setBody("sensitive text"));
        try { client.beginLogin(PHONE, PIN); fail(); } catch (TradeException error) { assertEquals("HTTP_503", error.code); }
        assertEquals("pending-safe", store.state.getString("processId"));
        assertTrue(client().view().getBoolean("pending"));
    }

    @Test public void confirmedLoginResumesSessionCheckWithoutReusingChallenge() throws Exception {
        pending("DEVICE_CONFIRMATION");
        json("{\"status\":\"CONFIRMED\"}");
        server.enqueue(new MockResponse().setResponseCode(503));
        try { client.checkLogin(); fail(); } catch (TradeException e) { assertEquals("HTTP_503", e.code); }
        assertTrue(store.state.getBoolean("approved"));
        while (server.takeRequest(20, TimeUnit.MILLISECONDS) != null) { }
        client = client(); sessionResponses(); client.checkLogin();
        assertEquals("/api/v1/auth/web/session", next().getPath());
        assertEquals("/api/v2/auth/account", next().getPath());
        assertTrue(client.view().getBoolean("connected"));
        assertFalse(store.state.has("approved"));
    }

    @Test public void repeatedLoginDoesNotCreateSecondChallenge() throws Exception {
        pending("DEVICE_CONFIRMATION"); int calls = server.getRequestCount();
        try { client.beginLogin(PHONE, PIN); fail(); } catch (TradeException e) { assertEquals("PENDING", e.code); }
        assertEquals(calls, server.getRequestCount());
    }

    @Test public void expiredProcessDoesNotPollOrRestart() throws Exception {
        pending("DEVICE_CONFIRMATION"); store.state.put("expiresAt", System.currentTimeMillis() - 1); client = client();
        int calls = server.getRequestCount();
        try { client.checkLogin(); fail(); } catch (TradeException error) { assertEquals("EXPIRED", error.code); }
        assertEquals(calls, server.getRequestCount()); assertFalse(client.view().getBoolean("pending"));
    }

    @Test public void rejectedAuthenticatorCodeCanBeRetriedWithoutNewLogin() throws Exception {
        pending("AUTHENTICATOR_VERIFICATION");
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"errors\":[{\"errorCode\":\"VALIDATION_CODE_INVALID\"}]}"));
        try { client.verifyAuthenticator("123456"); fail(); } catch (TradeException e) { assertEquals("CODE", e.code); }
        assertTrue(client.view().getBoolean("pending"));
        server.enqueue(new MockResponse().setResponseCode(204)); sessionResponses(); client.verifyAuthenticator("654321");
        assertTrue(client.view().getBoolean("connected"));
        assertFalse(store.state.toString().contains("123456")); assertFalse(store.state.toString().contains("654321"));
    }

    @Test public void rateLimitPreservesPendingProcessAndDoesNotRetry() throws Exception {
        pending("DEVICE_CONFIRMATION"); int before = server.getRequestCount();
        server.enqueue(new MockResponse().setResponseCode(429).setBody("SECRET_BODY"));
        try { client.checkLogin(); fail(); } catch (TradeException error) {
            assertEquals("RATE_LIMIT", error.code); assertFalse(error.getMessage().contains("SECRET_BODY"));
        }
        assertEquals(before + 1, server.getRequestCount()); assertTrue(client.view().getBoolean("pending"));
    }

    @Test public void redirectsAreNotFollowedWithCredentials() throws Exception {
        server.enqueue(new MockResponse().setBody(""));
        server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", server.url("/leak")));
        try { client.beginLogin(PHONE, PIN); fail(); } catch (TradeException e) { assertEquals("REDIRECT", e.code); }
        assertEquals(2, server.getRequestCount());
    }

    @Test public void sessionExpiryKeepsOldSnapshotButClearsAuthentication() throws Exception {
        pending("DEVICE_CONFIRMATION");
        store.state.remove("processId"); store.state.put("connected", true).put("snapshot", new JSONObject().put("capturedAt", 123));
        client = client(); server.enqueue(new MockResponse().setResponseCode(401));
        try { client.sync(); fail(); } catch (TradeException e) { assertEquals("AUTH", e.code); }
        assertFalse(client.view().getBoolean("connected")); assertEquals(123, client.view().getJSONObject("snapshot").getInt("capturedAt"));
        assertEquals(0, store.state.getJSONArray("cookies").length());
    }

    @Test public void storageFailureStopsBeforeCredentialsLeaveDevice() throws Exception {
        store.fail = true;
        try { client.beginLogin(PHONE, PIN); fail(); } catch (TradeException e) { assertEquals("STORAGE", e.code); }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void websocketReadsCashAndBothTimelinesAndDeduplicatesPages() throws Exception {
        sessionResponses();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String message) {
                try {
                    if (message.startsWith("connect 31 ")) { ws.send("connected"); return; }
                    if (message.startsWith("unsub ")) return;
                    String[] parts = message.split(" ", 3);
                    if (!parts[0].equals("sub")) { ws.close(1008, "unsupported"); return; }
                    JSONObject payload = new JSONObject(parts[2]); String topic = payload.getString("type"); String answer;
                    if (topic.equals("cash")) answer = "[{\"amount\":1234.56,\"currencyId\":\"EUR\"}]";
                    else if (topic.equals("timelineTransactions")) {
                        answer = payload.has("after")
                                ? "{\"items\":[{\"id\":\"one\",\"title\":\"Updated\",\"amount\":{\"value\":-4.25,\"currencyId\":\"EUR\"}},{\"id\":\"two\",\"title\":\"Deposit\"}],\"cursors\":{\"after\":null}}"
                                : "{\"items\":[{\"id\":\"one\",\"title\":\"Original\"}],\"cursors\":{\"after\":\"page-two\"}}";
                    } else if (topic.equals("timelineActivityLog")) answer = "{\"items\":[],\"cursors\":{\"after\":null}}";
                    else { ws.close(1008, "unsupported topic"); return; }
                    ws.send(parts[1] + " A " + answer);
                } catch (Exception e) { ws.close(1011, "fixture failed"); }
            }
        }));
        client.sync();
        JSONObject snapshot = client.view().getJSONObject("snapshot");
        assertEquals("1234.56", snapshot.getJSONArray("balances").getJSONObject(0).getString("amount"));
        JSONObject feed = snapshot.getJSONObject("transactions"); assertTrue(feed.getBoolean("complete"));
        assertEquals(2, feed.getJSONArray("items").length());
        assertEquals("Updated", feed.getJSONArray("items").getJSONObject(0).getString("title"));
        assertEquals("-4.25", feed.getJSONArray("items").getJSONObject(0).getJSONObject("amount").getString("value"));
        assertEquals(0, snapshot.getJSONObject("activity").getJSONArray("items").length());
    }

    @Test public void malformedFeedDoesNotReplacePreviousSnapshot() throws Exception {
        store.state = new JSONObject().put("schema", 1).put("deviceId", new String(new char[128]).replace('\0', 'a'))
                .put("snapshot", new JSONObject().put("capturedAt", 123)); client = client();
        sessionResponses();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                if (text.startsWith("connect")) ws.send("connected");
                else if (text.startsWith("sub 1 ")) ws.send("1 A [{\"amount\":\"10.01\",\"currencyId\":\"EUR\"}]");
                else if (text.startsWith("sub 2 ")) ws.send("2 D unexpected-delta");
            }
        }));
        try { client.sync(); fail(); } catch (TradeException e) { assertEquals("PROTOCOL", e.code); }
        assertEquals(123, client.view().getJSONObject("snapshot").getInt("capturedAt"));
    }

    @Test public void cookiesKeepAbsoluteExpiryAndStayOnApiOrigin() throws Exception {
        TradeCookies jar = new TradeCookies(server.url("/"));
        okhttp3.Cookie cookie = new okhttp3.Cookie.Builder().name("s").value("PRIVATE").hostOnlyDomain(server.getHostName())
                .path("/").expiresAt(System.currentTimeMillis() + 60000).build();
        jar.saveFromResponse(server.url("/"), java.util.Collections.singletonList(cookie));
        JSONArray saved = jar.export(); TradeCookies restored = new TradeCookies(server.url("/")); restored.restore(saved);
        assertEquals(cookie.expiresAt(), restored.export().getJSONObject(0).getLong("expires"));
        assertTrue(restored.loadForRequest(okhttp3.HttpUrl.get("https://example.com/")).isEmpty());
        saved.getJSONObject(0).put("expires", System.currentTimeMillis() - 1);
        TradeCookies expired = new TradeCookies(server.url("/")); expired.restore(saved);
        assertTrue(expired.loadForRequest(server.url("/")).isEmpty());
    }

    @Test public void failedSnapshotWriteKeepsPreviousSnapshotInMemoryAndStorage() throws Exception {
        store.state = new JSONObject().put("schema", 1).put("deviceId", new String(new char[128]).replace('\0', 'a'))
                .put("snapshot", new JSONObject().put("capturedAt", 123)); client = client();
        sessionResponses();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                if (text.startsWith("connect")) ws.send("connected");
                else if (text.startsWith("sub 1 ")) ws.send("1 A [{\"amount\":\"10.01\",\"currencyId\":\"EUR\"}]");
                else if (text.startsWith("sub 2 ")) ws.send("2 A {\"items\":[],\"cursors\":{\"after\":null}}");
                else if (text.startsWith("sub 3 ")) {
                    store.fail = true;
                    ws.send("3 A {\"items\":[],\"cursors\":{\"after\":null}}");
                }
            }
        }));
        try { client.sync(); fail(); } catch (TradeException e) { assertEquals("STORAGE", e.code); }
        assertEquals(123, client.view().getJSONObject("snapshot").getInt("capturedAt"));
        assertEquals(123, store.state.getJSONObject("snapshot").getInt("capturedAt"));
    }

    @Test public void paginationIsBoundedAndHistoryIsLabelledPartial() throws Exception {
        sessionResponses();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            private int pages;
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    if (text.startsWith("connect")) { ws.send("connected"); return; }
                    if (!text.startsWith("sub ")) return;
                    String[] parts = text.split(" ", 3);
                    String topic = new JSONObject(parts[2]).getString("type");
                    String payload;
                    if (topic.equals("cash")) payload = "[{\"amount\":\"0\",\"currencyId\":\"EUR\"}]";
                    else if (topic.equals("timelineTransactions")) {
                        pages++;
                        payload = new JSONObject().put("items", new JSONArray().put(new JSONObject()
                                .put("id", "transaction-" + pages).put("subtitle", JSONObject.NULL)
                                .put("status", "PENDING").put("amount", new JSONObject().put("currency", "EUR").put("value", "-0.01"))))
                                .put("cursors", new JSONObject().put("after", "next-" + pages)).toString();
                    } else payload = "{\"items\":[],\"cursors\":{\"after\":null}}";
                    ws.send(parts[1] + " A " + payload);
                } catch (Exception error) { ws.close(1011, "fixture"); }
            }
        }));
        client.sync();
        JSONObject feed = client.view().getJSONObject("snapshot").getJSONObject("transactions");
        assertEquals(5, feed.getJSONArray("items").length()); assertFalse(feed.getBoolean("complete"));
        JSONObject row = feed.getJSONArray("items").getJSONObject(0);
        assertEquals("PENDING", row.getString("status")); assertEquals("", row.getString("subtitle"));
        assertEquals("-0.01", row.getJSONObject("amount").getString("value"));
    }

    @Test public void parsesServerDeadlinesWithoutExtendingThem() {
        assertEquals(1791194400000L, TradeRepublicClient.deadline("2026-10-05T10:00:00.123Z"));
        assertEquals(1791194400000L, TradeRepublicClient.deadline(1791194400L));
        assertEquals(1791194400000L, TradeRepublicClient.deadline(1791194400000L));
        assertEquals(0, TradeRepublicClient.deadline("bad date"));
    }

    @Test public void monetaryValuesAreExactAndRejectUnboundedExponents() throws Exception {
        assertEquals("123456789.0123456789", TradeRepublicClient.decimal("123456789.0123456789"));
        assertEquals("-0.01", TradeRepublicClient.decimal("-1e-2"));
        for (String value : new String[]{"1e2147483647", "1e-2147483647", "NaN", "Infinity", "not-money"}) {
            try { TradeRepublicClient.decimal(value); fail(value); }
            catch (TradeException expected) { assertEquals("PROTOCOL", expected.code); }
        }
    }
}
