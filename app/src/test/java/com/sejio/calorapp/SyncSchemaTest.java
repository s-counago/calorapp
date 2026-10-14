package com.sejio.calorapp;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class SyncSchemaTest {
    @Test public void localTaskRowBecomesTheBackendRecord() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("local_id", 1700000000000L);
        row.put("title", "Llamar a Ana");
        row.put("status", "todo");
        row.put("created_at", 1700000000000L);
        row.put("following", 1L);
        row.put("waiting_for", "Ana");
        row.put("position", 2.0);
        JSONObject data = SyncSchema.toWire("tasks", row);
        assertEquals("Llamar a Ana", data.getString("title"));
        assertTrue(data.getBoolean("following"));
        assertEquals(2.0, data.getDouble("position"), 0);
        assertTrue(data.isNull("plan_date"));
        assertFalse("local ids never leave the phone", data.has("local_id"));
        assertEquals(SyncSchema.TABLES.get("tasks").size(), data.length());
    }

    @Test public void remoteRecordReplacesFieldsAndKeepsWhatAnOlderServerDoesNotSend() throws Exception {
        Map<String, Object> local = new HashMap<>();
        local.put("title", "Viejo");
        local.put("following", 1L);
        local.put("waiting_for", "Ana");
        JSONObject remote = new JSONObject().put("title", "Nuevo").put("status", "done").put("plan_date", JSONObject.NULL)
                .put("completed_at", 1700000000500L).put("created_at", 1700000000000L);
        Map<String, Object> row = SyncSchema.fromWire("tasks", remote, local);
        assertEquals("Nuevo", row.get("title"));
        assertEquals(1700000000500L, row.get("completed_at"));
        assertNull(row.get("plan_date"));
        assertEquals("missing in an old backend: keep the local follow-up", 1L, row.get("following"));
        assertEquals("Ana", row.get("waiting_for"));
    }

    @Test public void newRemoteRowGetsBooleanDefaults() {
        Map<String, Object> row = SyncSchema.fromWire("habits", new JSONObject(), null);
        assertEquals(0L, row.get("archived"));
        assertNull(row.get("name"));
    }

    @Test public void booleansArriveAsJsonBooleans() throws Exception {
        Map<String, Object> row = SyncSchema.fromWire("tasks", new JSONObject().put("following", true), null);
        assertEquals(1L, row.get("following"));
    }

    @Test public void lastWriteWinsWithTiesGoingToTheServer() {
        assertTrue(SyncSchema.remoteWins(null, 5));
        assertTrue(SyncSchema.remoteWins(5L, 5));
        assertTrue(SyncSchema.remoteWins(5L, 6));
        assertFalse(SyncSchema.remoteWins(6L, 5));
    }

    @Test public void localEditsAlwaysMoveForward() {
        assertEquals(100, SyncSchema.nextUpdatedAt(null, 100));
        assertEquals(100, SyncSchema.nextUpdatedAt(50L, 100));
        // A record that came from a device whose clock runs ahead still takes a newer local edit.
        assertEquals(201, SyncSchema.nextUpdatedAt(200L, 100));
    }

    @Test public void tablesMatchTheBackendContract() {
        assertEquals("[tasks, habits, habit_checks, metric_entries, settings]", SyncSchema.TABLES.keySet().toString());
        assertEquals("[day, kind, amount, at]", SyncSchema.TABLES.get("metric_entries").keySet().toString());
    }
}
