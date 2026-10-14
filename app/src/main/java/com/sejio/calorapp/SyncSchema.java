package com.sejio.calorapp;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tables shared with the backend and their synced fields, mirroring backend/src/schema.ts.
 * Local SQLite uses the same column names, so a row converts to the wire format field by field.
 */
final class SyncSchema {
    enum Type { TEXT, INT, REAL, BOOL }

    static final Map<String, Map<String, Type>> TABLES;

    static {
        Map<String, Map<String, Type>> tables = new LinkedHashMap<>();
        tables.put("tasks", fields(
                "title", Type.TEXT, "notes", Type.TEXT, "status", Type.TEXT, "project", Type.TEXT, "area", Type.TEXT,
                "priority", Type.INT, "due_date", Type.TEXT, "plan_date", Type.TEXT, "plan_slot", Type.TEXT,
                "position", Type.REAL, "completed_at", Type.INT, "source", Type.TEXT, "source_id", Type.TEXT,
                "created_at", Type.INT, "following", Type.BOOL, "waiting_for", Type.TEXT,
                "expected_date", Type.TEXT, "review_date", Type.TEXT));
        tables.put("habits", fields(
                "name", Type.TEXT, "weekly_goal", Type.INT, "created_day", Type.TEXT, "archived", Type.BOOL, "position", Type.REAL));
        tables.put("habit_checks", fields("habit_id", Type.TEXT, "day", Type.TEXT));
        tables.put("metric_entries", fields("day", Type.TEXT, "kind", Type.TEXT, "amount", Type.REAL, "at", Type.INT));
        tables.put("settings", fields("value", Type.TEXT));
        TABLES = Collections.unmodifiableMap(tables);
    }

    /** Tables whose rows the views address by a local numeric id (old SharedPreferences ids). */
    static final List<String> WITH_LOCAL_ID = Arrays.asList("tasks", "habits");

    static final String[] SLOTS = {"morning", "afternoon", "evening"};

    private SyncSchema() {}

    private static Map<String, Type> fields(Object... pairs) {
        Map<String, Type> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Type) pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }

    static boolean isTable(String table) {
        return TABLES.containsKey(table);
    }

    /** A local row (column → SQLite value) as the backend's `data` object. Booleans travel as JSON booleans. */
    static JSONObject toWire(String table, Map<String, Object> row) throws Exception {
        JSONObject data = new JSONObject();
        for (Map.Entry<String, Type> field : TABLES.get(table).entrySet()) {
            Object value = row.get(field.getKey());
            if (value == null) { data.put(field.getKey(), JSONObject.NULL); continue; }
            switch (field.getValue()) {
                case BOOL: data.put(field.getKey(), asLong(value) != 0); break;
                case INT: data.put(field.getKey(), asLong(value)); break;
                case REAL: data.put(field.getKey(), ((Number) value).doubleValue()); break;
                default: data.put(field.getKey(), value.toString());
            }
        }
        return data;
    }

    /**
     * Merges a remote `data` object over the local row and returns the column values to store.
     * A field the server did not send (an older backend) keeps its local value instead of being cleared.
     */
    static Map<String, Object> fromWire(String table, JSONObject data, Map<String, Object> local) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, Type> field : TABLES.get(table).entrySet()) {
            String name = field.getKey();
            Object value = data.has(name) ? data.opt(name) : local == null ? null : local.get(name);
            if (value == null || value == JSONObject.NULL) { row.put(name, field.getValue() == Type.BOOL ? 0L : null); continue; }
            switch (field.getValue()) {
                case BOOL: row.put(name, value instanceof Boolean ? ((Boolean) value ? 1L : 0L) : asLong(value) != 0 ? 1L : 0L); break;
                case INT: row.put(name, asLong(value)); break;
                case REAL: row.put(name, ((Number) value).doubleValue()); break;
                default: row.put(name, value.toString());
            }
        }
        return row;
    }

    /** Last write wins: a remote version replaces the local one unless the local one is strictly newer. */
    static boolean remoteWins(Long localUpdatedAt, long remoteUpdatedAt) {
        return localUpdatedAt == null || remoteUpdatedAt >= localUpdatedAt;
    }

    /** Timestamp for a local edit: now, but never behind the version it replaces, so the edit always wins locally. */
    static long nextUpdatedAt(Long current, long now) {
        return current == null ? now : Math.max(now, current + 1);
    }

    private static long asLong(Object value) {
        if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }
}
