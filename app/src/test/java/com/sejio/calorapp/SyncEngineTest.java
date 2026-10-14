package com.sejio.calorapp;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class SyncEngineTest {
    /** In-memory stand-in for PausaDb: records, an outbox with revisions, and the cursor. */
    static final class FakeStorage implements SyncEngine.Storage {
        final Map<String, Long> updatedAt = new LinkedHashMap<>();
        final Map<String, JSONObject> data = new LinkedHashMap<>();
        final Map<String, Long> outbox = new LinkedHashMap<>();
        long cursor;
        Runnable beforeAcknowledge;

        void edit(String id, long at, JSONObject record) {
            updatedAt.put(id, at);
            data.put(id, record);
            outbox.merge(id, 1L, Long::sum);
        }

        @Override public List<SyncEngine.Pending> pending(int limit) {
            List<SyncEngine.Pending> result = new ArrayList<>();
            for (Map.Entry<String, Long> entry : outbox.entrySet()) {
                if (result.size() == limit) break;
                String id = entry.getKey();
                result.add(new SyncEngine.Pending("tasks", id, entry.getValue(), updatedAt.get(id), false, data.get(id)));
            }
            return result;
        }

        @Override public void acknowledge(List<SyncEngine.Pending> sent) {
            if (beforeAcknowledge != null) { beforeAcknowledge.run(); beforeAcknowledge = null; }
            for (SyncEngine.Pending change : sent) outbox.remove(change.id, change.rev);
        }

        @Override public long cursor() { return cursor; }

        @Override public int applyPage(List<SyncEngine.Change> changes, long next) {
            int applied = 0;
            for (SyncEngine.Change change : changes) {
                if (!SyncSchema.remoteWins(updatedAt.get(change.id), change.updatedAt)) continue;
                updatedAt.put(change.id, change.updatedAt);
                data.put(change.id, change.data);
                outbox.remove(change.id);
                applied++;
            }
            cursor = next;
            return applied;
        }
    }

    private static final List<String> events = new ArrayList<>();

    private static SyncEngine engine(MockWebServer server) {
        events.clear();
        return new SyncEngine(server.url("/"), "pd_test", "6.9.0", (level, message, data) -> events.add(level + " " + message));
    }

    private static JSONObject task(String title) throws Exception {
        return new JSONObject().put("title", title).put("status", "todo").put("created_at", 1);
    }

    private static MockResponse json(Object body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setHeader("X-Request-Id", "server-id").setBody(body.toString());
    }

    private static JSONObject pullPage(long cursor, boolean more, JSONObject... changes) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject change : changes) list.put(change);
        return new JSONObject().put("cursor", cursor).put("more", more).put("changes", list);
    }

    private static JSONObject change(String table, String id, long updatedAt, JSONObject data) throws Exception {
        return new JSONObject().put("table", table).put("id", id).put("updated_at", updatedAt).put("deleted", false).put("data", data);
    }

    @Test public void pushesTheOutboxThenPullsAfterTheCursor() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(new JSONObject().put("applied", new JSONArray().put("t1")).put("cursor", 1)));
            server.enqueue(json(pullPage(1, false, change("tasks", "t1", 100, task("Pan")))));
            FakeStorage storage = new FakeStorage();
            storage.edit("t1", 100, task("Pan"));

            SyncEngine.Result result = engine(server).run(storage);

            RecordedRequest push = server.takeRequest(), pull = server.takeRequest();
            assertEquals("POST", push.getMethod());
            assertEquals("/api/sync", push.getPath());
            assertEquals("Bearer pd_test", push.getHeader("Authorization"));
            assertEquals("6.9.0", push.getHeader("X-Pausa-Version"));
            assertNotNull(push.getHeader("X-Request-Id"));
            assertNotEquals("every request has its own id", push.getHeader("X-Request-Id"), pull.getHeader("X-Request-Id"));
            JSONObject sent = new JSONObject(push.getBody().readUtf8()).getJSONArray("changes").getJSONObject(0);
            assertEquals("tasks", sent.getString("table"));
            assertEquals(100, sent.getLong("updated_at"));
            assertEquals("Pan", sent.getJSONObject("data").getString("title"));
            assertEquals("/api/sync?since=0", pull.getPath());
            assertTrue(storage.outbox.isEmpty());
            assertEquals(1, storage.cursor);
            assertEquals(1, result.pushed);
            assertEquals(1, result.cursor);
        }
    }

    @Test public void sendsLargeOutboxesInBatches() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            FakeStorage storage = new FakeStorage();
            for (int i = 0; i < 450; i++) storage.edit("t" + i, 100, task("Tarea " + i));
            for (int i = 0; i < 3; i++) server.enqueue(json(new JSONObject().put("applied", new JSONArray()).put("cursor", 0)));
            server.enqueue(json(pullPage(0, false)));

            engine(server).run(storage);

            int[] sizes = new int[3];
            for (int i = 0; i < 3; i++) sizes[i] = new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("changes").length();
            assertArrayEquals(new int[]{200, 200, 50}, sizes);
            assertTrue(storage.outbox.isEmpty());
        }
    }

    @Test public void aRecordEditedWhileItWasInFlightIsSentAgain() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            FakeStorage storage = new FakeStorage();
            storage.edit("t1", 100, task("Primera"));
            storage.beforeAcknowledge = () -> {
                try { storage.edit("t1", 200, task("Segunda")); } catch (Exception e) { throw new AssertionError(e); }
            };
            server.enqueue(json(new JSONObject().put("applied", new JSONArray().put("t1")).put("cursor", 1)));
            server.enqueue(json(new JSONObject().put("applied", new JSONArray().put("t1")).put("cursor", 2)));
            server.enqueue(json(pullPage(2, false)));

            engine(server).run(storage);

            server.takeRequest();
            JSONObject second = new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("changes").getJSONObject(0);
            assertEquals("Segunda", second.getJSONObject("data").getString("title"));
            assertTrue(storage.outbox.isEmpty());
        }
    }

    @Test public void staleChangesLeaveTheOutboxAndTheServerVersionComesBack() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            FakeStorage storage = new FakeStorage();
            storage.edit("t1", 100, task("Mía"));
            server.enqueue(json(new JSONObject().put("applied", new JSONArray()).put("cursor", 5)));
            server.enqueue(json(pullPage(5, false, change("tasks", "t1", 300, task("De la web")))));

            SyncEngine.Result result = engine(server).run(storage);

            assertEquals(1, result.stale);
            assertEquals("De la web", storage.data.get("t1").getString("title"));
            assertTrue(storage.outbox.isEmpty());
        }
    }

    @Test public void pullFollowsPagesAndSkipsTablesThisVersionDoesNotKnow() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            FakeStorage storage = new FakeStorage();
            storage.cursor = 10;
            server.enqueue(json(pullPage(12, true, change("tasks", "a", 1, task("A")), change("future_table", "x", 1, new JSONObject()))));
            server.enqueue(json(pullPage(13, false, change("tasks", "b", 1, task("B")))));

            SyncEngine.Result result = engine(server).run(storage);

            assertEquals("/api/sync?since=10", server.takeRequest().getPath());
            assertEquals("/api/sync?since=12", server.takeRequest().getPath());
            assertEquals(13, storage.cursor);
            assertEquals(new HashSet<>(List.of("a", "b")), storage.data.keySet());
            assertEquals(3, result.pulled);
            assertEquals(2, result.applied);
        }
    }

    @Test public void aNewerLocalEditSurvivesAnOlderRemoteVersion() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            FakeStorage storage = new FakeStorage();
            storage.updatedAt.put("t1", 500L);
            storage.data.put("t1", task("Local"));
            server.enqueue(json(pullPage(1, false, change("tasks", "t1", 400, task("Remota")))));

            engine(server).run(storage);

            assertEquals("Local", storage.data.get("t1").getString("title"));
        }
    }

    @Test public void rejectedTokenIsAnAuthFailureWithTheServerCode() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"Device token revoked\",\"code\":\"invalid_device_token\",\"request_id\":\"r1\"}"));
            try {
                engine(server).run(new FakeStorage());
                fail("expected AuthFailure");
            } catch (SyncEngine.AuthFailure failure) {
                assertEquals("invalid_device_token", failure.code);
            }
            assertTrue(events.contains("warn http_failed"));
        }
    }

    @Test public void serverErrorsKeepTheirRequestIdAndLeaveTheOutboxIntact() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setHeader("X-Request-Id", "srv-42").setBody("not json"));
            FakeStorage storage = new FakeStorage();
            storage.edit("t1", 100, task("Pan"));
            try {
                engine(server).run(storage);
                fail("expected HttpFailure");
            } catch (SyncEngine.HttpFailure failure) {
                assertEquals(503, failure.status);
                assertEquals("srv-42", failure.requestId);
                assertEquals("http_503", failure.code);
            }
            assertEquals(Set.of("t1"), storage.outbox.keySet());
            assertTrue(events.contains("error http_failed"));
        }
    }

    @Test public void logsAreUploadedWithTheAppVersion() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(new JSONObject().put("stored", 1)));
            engine(server).uploadLogs(new JSONArray().put(new JSONObject().put("at", 1).put("level", "warn").put("tag", "sync").put("message", "x")));
            RecordedRequest request = server.takeRequest();
            assertEquals("/api/logs", request.getPath());
            JSONObject body = new JSONObject(request.getBody().readUtf8());
            assertEquals("6.9.0", body.getString("app_version"));
            assertEquals(1, body.getJSONArray("entries").length());
        }
    }
}
