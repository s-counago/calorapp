package com.sejio.calorapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Local source of truth for everything that syncs with the backend (tasks, habits, checks,
 * counters and goals), plus the outbox of changes waiting for coverage and the sync state.
 * Every write goes through {@link #put}, which stamps `updated_at`, queues the record and asks
 * for a sync. The views never wait for the network.
 */
final class PausaDb extends SQLiteOpenHelper {
    static final String NAME = "pausa.db";
    private static final int VERSION = 1;
    private static PausaDb instance;

    private final Context context;

    static synchronized PausaDb get(Context context) {
        if (instance == null) instance = new PausaDb(context.getApplicationContext());
        return instance;
    }

    private PausaDb(Context context) {
        super(context, NAME, null, VERSION);
        this.context = context;
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        String sync = "updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0";
        db.execSQL("CREATE TABLE tasks (local_id INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, title TEXT NOT NULL, notes TEXT, "
                + "status TEXT NOT NULL DEFAULT 'todo', project TEXT, area TEXT, priority INTEGER, due_date TEXT, plan_date TEXT, "
                + "plan_slot TEXT, position REAL, completed_at INTEGER, source TEXT, source_id TEXT, created_at INTEGER NOT NULL, "
                + "following INTEGER NOT NULL DEFAULT 0, waiting_for TEXT, expected_date TEXT, review_date TEXT, " + sync + ")");
        db.execSQL("CREATE INDEX tasks_plan ON tasks (plan_date, plan_slot, position)");
        db.execSQL("CREATE TABLE habits (local_id INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, name TEXT NOT NULL, "
                + "weekly_goal INTEGER NOT NULL DEFAULT 1, created_day TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0, position REAL, " + sync + ")");
        db.execSQL("CREATE TABLE habit_checks (id TEXT PRIMARY KEY, habit_id TEXT NOT NULL, day TEXT NOT NULL, " + sync + ")");
        db.execSQL("CREATE INDEX habit_checks_habit ON habit_checks (habit_id, day)");
        db.execSQL("CREATE TABLE metric_entries (id TEXT PRIMARY KEY, day TEXT NOT NULL, kind TEXT NOT NULL, amount REAL NOT NULL, "
                + "at INTEGER NOT NULL, " + sync + ")");
        db.execSQL("CREATE INDEX metric_entries_day ON metric_entries (day, kind)");
        db.execSQL("CREATE TABLE settings (id TEXT PRIMARY KEY, value TEXT NOT NULL, " + sync + ")");
        // Local only: habit points are derived on the phone from the checks (see HabitStore).
        db.execSQL("CREATE TABLE habit_scores (habit_id TEXT PRIMARY KEY, json TEXT NOT NULL)");
        // One entry per record with unsent changes. `rev` grows on every edit, so a record edited
        // while its previous version was in flight stays queued.
        db.execSQL("CREATE TABLE outbox (tbl TEXT NOT NULL, id TEXT NOT NULL, rev INTEGER NOT NULL, queued_at INTEGER NOT NULL, PRIMARY KEY (tbl, id))");
        db.execSQL("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE log_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, level TEXT NOT NULL, "
                + "tag TEXT NOT NULL, message TEXT NOT NULL, data TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future versions add ALTER TABLE steps here; never drop synced data.
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ rows

    /** The synced columns of one record (also tombstones), or null. */
    Map<String, Object> row(String table, String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM " + table + " WHERE id = ?", new String[]{id})) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    List<Map<String, Object>> rows(String sql, String... args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) rows.add(read(cursor));
        }
        return rows;
    }

    static Map<String, Object> read(Cursor cursor) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            switch (cursor.getType(i)) {
                case Cursor.FIELD_TYPE_NULL: row.put(cursor.getColumnName(i), null); break;
                case Cursor.FIELD_TYPE_INTEGER: row.put(cursor.getColumnName(i), cursor.getLong(i)); break;
                case Cursor.FIELD_TYPE_FLOAT: row.put(cursor.getColumnName(i), cursor.getDouble(i)); break;
                default: row.put(cursor.getColumnName(i), cursor.getString(i));
            }
        }
        return row;
    }

    /**
     * Writes a local edit: merges `changes` over the current record, stamps `updated_at`, queues it
     * for the backend and schedules a sync. Returns the record's local id for tasks and habits.
     */
    long put(String table, String id, Map<String, Object> changes, boolean deleted) {
        return put(table, id, changes, deleted, null);
    }

    long put(String table, String id, Map<String, Object> changes, boolean deleted, Long localId) {
        SQLiteDatabase db = getWritableDatabase();
        long result;
        db.beginTransaction();
        try {
            result = write(db, table, id, changes, deleted, localId, System.currentTimeMillis());
            enqueue(db, table, id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        PausaSync.requestSoon(context);
        return result;
    }

    /** Same as {@link #put} for many records at once, used by the first import. Does not schedule a sync. */
    long write(SQLiteDatabase db, String table, String id, Map<String, Object> changes, boolean deleted, Long localId, long now) {
        Map<String, Object> current = rowIn(db, table, id);
        ContentValues values = new ContentValues();
        for (Map.Entry<String, SyncSchema.Type> field : SyncSchema.TABLES.get(table).entrySet()) {
            String name = field.getKey();
            Object value = changes.containsKey(name) ? changes.get(name) : current == null ? null : current.get(name);
            if (value == null && field.getValue() == SyncSchema.Type.BOOL) value = 0;
            putValue(values, name, value);
        }
        values.put("updated_at", SyncSchema.nextUpdatedAt(current == null ? null : (Long) current.get("updated_at"), now));
        values.put("deleted", deleted ? 1 : 0);
        if (current != null) {
            db.update(table, values, "id = ?", new String[]{id});
            return SyncSchema.WITH_LOCAL_ID.contains(table) ? (Long) current.get("local_id") : -1;
        }
        values.put("id", id);
        if (localId != null) values.put("local_id", localId);
        long rowId = db.insertOrThrow(table, null, values);
        return SyncSchema.WITH_LOCAL_ID.contains(table) ? rowId : -1;
    }

    private static Map<String, Object> rowIn(SQLiteDatabase db, String table, String id) {
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + table + " WHERE id = ?", new String[]{id})) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    static void enqueue(SQLiteDatabase db, String table, String id) {
        db.execSQL("INSERT INTO outbox (tbl, id, rev, queued_at) VALUES (?, ?, 1, ?) "
                + "ON CONFLICT(tbl, id) DO UPDATE SET rev = rev + 1", new Object[]{table, id, System.currentTimeMillis()});
    }

    static void putValue(ContentValues values, String key, Object value) {
        if (value == null) values.putNull(key);
        else if (value instanceof Boolean) values.put(key, (Boolean) value ? 1 : 0);
        else if (value instanceof Integer || value instanceof Long) values.put(key, ((Number) value).longValue());
        else if (value instanceof Number) values.put(key, ((Number) value).doubleValue());
        else values.put(key, value.toString());
    }

    // ------------------------------------------------------------------ sync state

    String state(String key, String fallback) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT value FROM sync_state WHERE key = ?", new String[]{key})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : fallback;
        }
    }

    void setState(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        getWritableDatabase().insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    int count(String sql, String... args) {
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    // ------------------------------------------------------------------ SyncEngine.Storage

    /** The outbox seen by the sync engine. */
    final class Storage implements SyncEngine.Storage {
        @Override public List<SyncEngine.Pending> pending(int limit) throws Exception {
            List<SyncEngine.Pending> result = new ArrayList<>();
            List<String[]> stale = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT tbl, id, rev FROM outbox ORDER BY queued_at LIMIT " + limit, null)) {
                while (cursor.moveToNext()) {
                    String table = cursor.getString(0), id = cursor.getString(1);
                    Map<String, Object> row = SyncSchema.isTable(table) ? row(table, id) : null;
                    if (row == null) { stale.add(new String[]{table, id}); continue; }
                    result.add(new SyncEngine.Pending(table, id, cursor.getLong(2), (Long) row.get("updated_at"),
                            ((Long) row.get("deleted")) == 1, SyncSchema.toWire(table, row)));
                }
            }
            for (String[] entry : stale) getWritableDatabase().delete("outbox", "tbl = ? AND id = ?", entry);
            return result;
        }

        @Override public void acknowledge(List<SyncEngine.Pending> sent) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (SyncEngine.Pending change : sent) {
                    db.delete("outbox", "tbl = ? AND id = ? AND rev = ?", new String[]{change.table, change.id, String.valueOf(change.rev)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        @Override public long cursor() {
            return Long.parseLong(state("cursor", "0"));
        }

        @Override public int applyPage(List<SyncEngine.Change> changes, long cursor) {
            SQLiteDatabase db = getWritableDatabase();
            int applied = 0;
            boolean tasksChanged = false;
            db.beginTransaction();
            try {
                for (SyncEngine.Change change : changes) {
                    Map<String, Object> local = rowIn(db, change.table, change.id);
                    if (!SyncSchema.remoteWins(local == null ? null : (Long) local.get("updated_at"), change.updatedAt)) continue;
                    // Our own push coming back: same version, nothing to apply.
                    if (local != null && change.updatedAt == (Long) local.get("updated_at") && (change.deleted ? 1L : 0L) == (Long) local.get("deleted")) continue;
                    Map<String, Object> merged = SyncSchema.fromWire(change.table, change.data, local);
                    ContentValues values = new ContentValues();
                    for (Map.Entry<String, Object> field : merged.entrySet()) putValue(values, field.getKey(), field.getValue());
                    values.put("updated_at", change.updatedAt);
                    values.put("deleted", change.deleted ? 1 : 0);
                    if (local == null) {
                        values.put("id", change.id);
                        // A new row from the web or an agent may miss required local columns.
                        if (!values.containsKey("created_at") || values.get("created_at") == null) {
                            if (change.table.equals("tasks")) values.put("created_at", change.updatedAt);
                        }
                        db.insertWithOnConflict(change.table, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                    } else {
                        db.update(change.table, values, "id = ?", new String[]{change.id});
                    }
                    // A remote version that wins replaces whatever local edit was still queued.
                    db.delete("outbox", "tbl = ? AND id = ?", new String[]{change.table, change.id});
                    applied++;
                    tasksChanged |= change.table.equals("tasks");
                }
                ContentValues state = new ContentValues();
                state.put("key", "cursor");
                state.put("value", String.valueOf(cursor));
                db.insertWithOnConflict("sync_state", null, state, SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (tasksChanged) CalendarSync.request(context);
            return applied;
        }
    }

    // ------------------------------------------------------------------ log queue

    void queueLog(long at, String level, String tag, String message, JSONObject data) {
        ContentValues values = new ContentValues();
        values.put("at", at);
        values.put("level", level);
        values.put("tag", tag);
        values.put("message", message.length() > 4000 ? message.substring(0, 4000) : message);
        values.put("data", data == null ? null : data.toString());
        SQLiteDatabase db = getWritableDatabase();
        db.insert("log_queue", null, values);
        // Keep the newest 2000 entries if the phone stays offline for a long time.
        db.execSQL("DELETE FROM log_queue WHERE id <= (SELECT MAX(id) - 2000 FROM log_queue)");
    }

    /** Erases all local Pausa data. Instrumented tests only; never call it from the app. */
    static void resetForTests(Context context) {
        PausaDb db = get(context);
        SQLiteDatabase sql = db.getWritableDatabase();
        for (String table : new String[]{"tasks", "habits", "habit_checks", "metric_entries", "settings", "habit_scores", "outbox", "sync_state", "log_queue"}) {
            sql.delete(table, null, null);
        }
        db.setState("imported", "1");
        LegacyImport.markDone();
    }
}
