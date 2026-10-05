package com.sejio.calorapp.trade;

import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Bounded one-shot read subscriptions. No trading or payment commands. */
final class TradeSocket extends WebSocketListener implements AutoCloseable {
    private final ArrayBlockingQueue<String> inbox = new ArrayBlockingQueue<>(32);
    private volatile boolean failed;
    private WebSocket socket;
    private int nextId;

    TradeSocket(OkHttpClient client, Request request) { socket = client.newWebSocket(request, this); }

    @Override public void onMessage(WebSocket webSocket, String text) {
        if (text.length() > 2 * 1024 * 1024 || !inbox.offer(text)) { failed = true; webSocket.cancel(); }
    }
    @Override public void onFailure(WebSocket ws, Throwable error, Response response) { failed = true; inbox.offer(""); }
    @Override public void onClosed(WebSocket ws, int code, String reason) { failed = true; inbox.offer(""); }
    @Override public void onClosing(WebSocket ws, int code, String reason) { failed = true; ws.close(code, null); inbox.offer(""); }

    void connect() throws Exception {
        JSONObject payload = new JSONObject().put("locale", "es").put("platformId", "webtrading")
                .put("platformVersion", "chrome - 94.0.4606").put("clientId", "app.traderepublic.com").put("clientVersion", "5582");
        send("connect 31 " + payload);
        if (!"connected".equals(receive(System.nanoTime() + TimeUnit.SECONDS.toNanos(25)))) throw TradeException.protocol();
    }

    Object read(String topic, String cursor) throws Exception {
        if (!topic.equals("cash") && !topic.equals("timelineTransactions") && !topic.equals("timelineActivityLog"))
            throw new IllegalArgumentException("Unsupported read topic");
        JSONObject payload = new JSONObject().put("type", topic);
        if (cursor != null) payload.put("after", cursor);
        return readPayload(payload);
    }

    Object readPortfolio(String securitiesAccount) throws Exception {
        return readPayload(new JSONObject().put("type", "compactPortfolioByType").put("secAccNo", securitiesAccount));
    }

    private Object readPayload(JSONObject payload) throws Exception {
        int id = ++nextId;
        send("sub " + id + " " + payload);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        try {
            while (true) {
                String message = receive(deadline);
                String prefix = id + " ";
                if (!message.startsWith(prefix)) continue; // Other subscriptions' close acknowledgements.
                String frame = message.substring(prefix.length());
                if (frame.isEmpty()) throw TradeException.protocol();
                char kind = frame.charAt(0);
                if (kind == 'E') throw new TradeException("DATA_REJECTED", "Trade Republic ha rechazado una consulta. La copia anterior se conserva.");
                if (kind != 'A') throw TradeException.protocol(); // Require full initial snapshot; do not mistake deltas for one.
                return new JSONTokener(frame.substring(1).trim()).nextValue();
            }
        } finally { socket.send("unsub " + id); }
    }

    private void send(String text) throws TradeException {
        if (failed || !socket.send(text)) throw TradeException.network();
    }

    private String receive(long deadline) throws Exception {
        while (!failed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            String text = inbox.poll(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
            if (text != null && !text.isEmpty()) return text;
        }
        throw new TradeException("SOCKET", "La conexión de datos se interrumpió o tardó demasiado. Puedes volver a sincronizar.");
    }

    @Override public void close() { if (socket != null) { socket.close(1000, null); socket.cancel(); } }
}
