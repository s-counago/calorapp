package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * One sync round against the backend: push the outbox in batches, then pull everything after the
 * cursor page by page. Plain Java so the protocol is tested on the JVM with a fake storage.
 */
final class SyncEngine {
    static final int PUSH_BATCH = 200;
    static final int MAX_ROUNDS = 50;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** A queued local change: the full current record plus the outbox revision it was read at. */
    static final class Pending {
        final String table, id;
        final long rev, updatedAt;
        final boolean deleted;
        final JSONObject data;

        Pending(String table, String id, long rev, long updatedAt, boolean deleted, JSONObject data) {
            this.table = table; this.id = id; this.rev = rev; this.updatedAt = updatedAt; this.deleted = deleted; this.data = data;
        }
    }

    /** A record that arrived from the server. */
    static final class Change {
        final String table, id;
        final long updatedAt;
        final boolean deleted;
        final JSONObject data;

        Change(String table, String id, long updatedAt, boolean deleted, JSONObject data) {
            this.table = table; this.id = id; this.updatedAt = updatedAt; this.deleted = deleted; this.data = data;
        }
    }

    interface Storage {
        List<Pending> pending(int limit) throws Exception;
        /** Drops the outbox entries that were sent, unless they were edited again since `rev`. */
        void acknowledge(List<Pending> sent) throws Exception;
        long cursor() throws Exception;
        /** Applies one page with last-write-wins and stores the new cursor, in one transaction. Returns how many rows changed. */
        int applyPage(List<Change> changes, long cursor) throws Exception;
    }

    interface Log {
        void event(String level, String message, JSONObject data);
    }

    /** The server no longer accepts this device token: the user must sign in again. */
    static final class AuthFailure extends IOException {
        final String code;
        AuthFailure(String code, String message) { super(message); this.code = code; }
    }

    /** Any other non-2xx answer, with the server's error code and request id for the logs. */
    static final class HttpFailure extends IOException {
        final int status;
        final String code, requestId;
        HttpFailure(int status, String code, String requestId, String message) {
            super("HTTP " + status + " " + code + ": " + message); this.status = status; this.code = code; this.requestId = requestId;
        }
    }

    static final class Result {
        int pushed, stale, pulled, applied;
        long cursor;
    }

    private final OkHttpClient http;
    private final HttpUrl base;
    private final String token, appVersion;
    private final Log log;

    SyncEngine(HttpUrl base, String token, String appVersion, Log log) {
        this(new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS).build(), base, token, appVersion, log);
    }

    SyncEngine(OkHttpClient http, HttpUrl base, String token, String appVersion, Log log) {
        this.http = http; this.base = base; this.token = token; this.appVersion = appVersion; this.log = log;
    }

    Result run(Storage storage) throws Exception {
        Result result = new Result();
        push(storage, result);
        pull(storage, result);
        // Edits made while the round ran are pushed now instead of waiting for the next round.
        push(storage, result);
        return result;
    }

    private void push(Storage storage, Result result) throws Exception {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Pending> batch = storage.pending(PUSH_BATCH);
            if (batch.isEmpty()) return;
            JSONArray changes = new JSONArray();
            for (Pending change : batch) {
                changes.put(new JSONObject().put("table", change.table).put("id", change.id)
                        .put("updated_at", change.updatedAt).put("deleted", change.deleted).put("data", change.data));
            }
            JSONObject body = call("POST", base.newBuilder().addPathSegments("api/sync").build(), new JSONObject().put("changes", changes));
            Set<String> applied = new HashSet<>();
            JSONArray ids = body.optJSONArray("applied");
            if (ids != null) for (int i = 0; i < ids.length(); i++) applied.add(ids.getString(i));
            storage.acknowledge(batch);
            int stale = batch.size() - applied.size();
            result.pushed += applied.size();
            result.stale += stale;
            log.event("info", "push", new JSONObject().put("sent", batch.size()).put("applied", applied.size()).put("stale", stale)
                    .put("cursor", body.optLong("cursor")));
        }
        throw new IOException("Outbox still not empty after " + MAX_ROUNDS + " batches");
    }

    private void pull(Storage storage, Result result) throws Exception {
        long cursor = storage.cursor();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            HttpUrl url = base.newBuilder().addPathSegments("api/sync").addQueryParameter("since", String.valueOf(cursor)).build();
            JSONObject body = call("GET", url, null);
            JSONArray raw = body.getJSONArray("changes");
            List<Change> changes = new ArrayList<>();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject item = raw.getJSONObject(i);
                String table = item.getString("table");
                if (!SyncSchema.isTable(table)) continue; // A table this app version does not know yet.
                changes.add(new Change(table, item.getString("id"), item.getLong("updated_at"), item.optBoolean("deleted"),
                        item.optJSONObject("data") == null ? new JSONObject() : item.getJSONObject("data")));
            }
            long next = body.getLong("cursor");
            int applied = storage.applyPage(changes, next);
            result.pulled += raw.length();
            result.applied += applied;
            if (!changes.isEmpty() || next != cursor) {
                log.event("info", "pull", new JSONObject().put("since", cursor).put("cursor", next).put("received", raw.length()).put("applied", applied));
            }
            cursor = next;
            result.cursor = cursor;
            if (!body.optBoolean("more")) return;
        }
        throw new IOException("Pull did not finish after " + MAX_ROUNDS + " pages");
    }

    /** Sends the queued client logs. */
    void uploadLogs(JSONArray entries) throws Exception {
        call("POST", base.newBuilder().addPathSegments("api/logs").build(), new JSONObject().put("app_version", appVersion).put("entries", entries));
    }

    JSONObject call(String method, HttpUrl url, JSONObject body) throws Exception {
        String requestId = UUID.randomUUID().toString();
        Request.Builder request = new Request.Builder().url(url)
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", requestId)
                .header("X-Pausa-Version", appVersion)
                .method(method, body == null ? null : RequestBody.create(body.toString(), JSON));
        long started = System.nanoTime();
        try (Response response = http.newCall(request.build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            String serverId = response.header("X-Request-Id", requestId);
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (response.isSuccessful()) {
                log.event("debug", "http", new JSONObject().put("method", method).put("path", url.encodedPath())
                        .put("status", response.code()).put("ms", ms).put("request_id", serverId));
                return text.isEmpty() ? new JSONObject() : new JSONObject(text);
            }
            JSONObject error = parse(text);
            String code = error.optString("code", "http_" + response.code());
            String message = error.optString("error", response.message());
            log.event(response.code() >= 500 ? "error" : "warn", "http_failed", new JSONObject().put("method", method)
                    .put("path", url.encodedPath()).put("status", response.code()).put("code", code).put("ms", ms).put("request_id", serverId));
            if (response.code() == 401) throw new AuthFailure(code, message);
            throw new HttpFailure(response.code(), code, serverId, message);
        }
    }

    private static JSONObject parse(String text) {
        try { return new JSONObject(text); } catch (Exception notJson) { return new JSONObject(); }
    }
}
