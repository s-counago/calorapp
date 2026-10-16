package com.sejio.calorapp;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El cajón: links, ideas and notes dumped in two taps (Compartir, the Apuntar sheet) and sorted
 * later. Each capture is a synced row of `captures` (backend migration 0008) with a status: new,
 * kept (in the library), filed (turned into a task, `task_id`) or archived. An agent can leave a
 * `suggestion` (JSON) with what it thinks the capture is; the phone shows it and one tap accepts it.
 */
final class CaptureStore {
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    static final class Capture {
        final String id, text, url, title, suggestion;
        final long createdAt;
        /** new, kept, filed or archived; the kind (note, idea, link…) and comma-separated tags, set when it is sorted. */
        String status = "new", kind, tags;
        Capture(String id, String text, String url, String title, String suggestion, long createdAt) {
            this.id = id; this.text = text; this.url = url; this.title = title; this.suggestion = suggestion; this.createdAt = createdAt;
        }

        static Capture fromRow(Map<String, Object> row) {
            Capture capture = new Capture((String) row.get("id"), (String) row.get("text"), (String) row.get("url"),
                    (String) row.get("title"), (String) row.get("suggestion"), row.get("created_at") == null ? 0 : (Long) row.get("created_at"));
            if (row.get("status") != null) capture.status = (String) row.get("status");
            capture.kind = (String) row.get("kind");
            capture.tags = (String) row.get("tags");
            return capture;
        }

        /** What the agent suggested, as a short line ("Tarea · Prioridad · jueves"), or null. */
        String suggestionLine() {
            Suggestion s = suggested();
            if (s == null) return null;
            StringBuilder line = new StringBuilder(kindLabel(s.kind));
            if (s.lane != null) line.append(" · ").append(TaskStore.laneLabel(s.lane));
            if (s.day != null) line.append(" · ").append(Planning.dayName(s.day));
            if (s.reason != null && !s.reason.isEmpty()) line.append(". ").append(s.reason);
            return line.toString();
        }

        Suggestion suggested() {
            if (suggestion == null || suggestion.isEmpty()) return null;
            try {
                JSONObject json = new JSONObject(suggestion);
                return new Suggestion(json.optString("kind", "other"), opt(json, "title"), opt(json, "lane"), opt(json, "day"), opt(json, "reason"));
            } catch (Exception broken) {
                return null;
            }
        }
    }

    static final class Suggestion {
        final String kind, title, lane, day, reason;
        Suggestion(String kind, String title, String lane, String day, String reason) {
            this.kind = kind; this.title = title; this.lane = lane; this.day = day; this.reason = reason;
        }
    }

    private CaptureStore() {}

    /** Saves a capture as new. Works without coverage: it waits in the outbox like any other edit. */
    static String add(Context context, String text, String source) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return null;
        LegacyImport.runOnce(context);
        Map<String, Object> data = new HashMap<>();
        data.put("text", clean.length() > 20000 ? clean.substring(0, 20000) : clean);
        data.put("url", firstUrl(clean));
        data.put("source", source);
        data.put("status", "new");
        data.put("created_at", System.currentTimeMillis());
        String id = PausaDb.uuid();
        PausaDb.get(context).put("captures", id, data, false);
        PausaLog.i("captures", "captured from " + source);
        return id;
    }

    static String firstUrl(String text) {
        Matcher matcher = URL.matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        String url = matcher.group();
        return url.length() > 2000 ? url.substring(0, 2000) : url;
    }

    /** New captures, oldest first, so the backlog empties in the order it arrived. */
    static List<Capture> fresh(Context context) {
        LegacyImport.runOnce(context);
        List<Capture> captures = new ArrayList<>();
        for (Map<String, Object> row : PausaDb.get(context).rows(
                "SELECT * FROM captures WHERE deleted = 0 AND status = 'new' ORDER BY created_at")) {
            captures.add(Capture.fromRow(row));
        }
        return captures;
    }

    /**
     * The library (`kept`) or the archive (`archived`), newest first, like the web's tabs. `query`
     * filters by text, title, link and tags; null or empty shows everything.
     */
    static List<Capture> sorted(Context context, String status, String query) {
        LegacyImport.runOnce(context);
        String like = "%" + (query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT)) + "%";
        List<Capture> captures = new ArrayList<>();
        for (Map<String, Object> row : PausaDb.get(context).rows("SELECT * FROM captures WHERE deleted = 0 AND status = ? AND ("
                + "LOWER(text) LIKE ? OR LOWER(IFNULL(title, '')) LIKE ? OR LOWER(IFNULL(tags, '')) LIKE ? OR LOWER(IFNULL(url, '')) LIKE ?) "
                + "ORDER BY updated_at DESC LIMIT 300", status, like, like, like, like)) {
            captures.add(Capture.fromRow(row));
        }
        return captures;
    }

    static int count(Context context, String status) {
        LegacyImport.runOnce(context);
        return PausaDb.get(context).count("SELECT COUNT(*) FROM captures WHERE deleted = 0 AND status = ?", status);
    }

    /** Deletes a capture everywhere (a tombstone). {@link #undelete} brings it back. */
    static void delete(Context context, Capture capture) {
        PausaDb.get(context).put("captures", capture.id, new HashMap<>(), true);
        PausaLog.i("captures", "deleted " + capture.status);
    }

    static void undelete(Context context, Capture capture) {
        PausaDb.get(context).put("captures", capture.id, new HashMap<>(), false);
    }

    static int freshCount(Context context) {
        LegacyImport.runOnce(context);
        return PausaDb.get(context).count("SELECT COUNT(*) FROM captures WHERE deleted = 0 AND status = 'new'");
    }

    /**
     * Turns a capture into a task in `lane` (optionally planned on `day`), as fileCapture does in the
     * backend: the title is the first line, and the full text and the link stay in the task's notes.
     */
    static long toTask(Context context, Capture capture, String title, String lane, String day) {
        String text = title == null || title.trim().isEmpty() ? defaultTitle(capture) : title.trim();
        long localId = TaskStore.add(context, text.length() > 500 ? text.substring(0, 500) : text, null);
        Map<String, Object> origin = new HashMap<>();
        origin.put("notes", notesFor(capture, text));
        origin.put("source", "capture");
        origin.put("source_id", capture.id);
        TaskStore.update(context, localId, origin);
        TaskStore.setLane(context, localId, lane);
        if (day != null) TaskStore.plan(context, localId, day, null);
        Map<String, Object> data = new HashMap<>();
        data.put("status", "filed");
        data.put("kind", "task");
        data.put("task_id", TaskStore.uid(context, localId));
        PausaDb.get(context).put("captures", capture.id, data, false);
        return localId;
    }

    /** The capture's title, or its first line. */
    static String defaultTitle(Capture capture) {
        if (capture.title != null && !capture.title.trim().isEmpty()) return capture.title.trim();
        String first = capture.text.split("\n", 2)[0].trim();
        return first.isEmpty() ? capture.text.trim() : first;
    }

    /** What the task keeps of the capture: the text when it is more than the title, and the link when the text lacks it. */
    static String notesFor(Capture capture, String title) {
        List<String> parts = new ArrayList<>();
        if (!capture.text.equals(title)) parts.add(capture.text);
        if (capture.url != null && !capture.text.contains(capture.url)) parts.add(capture.url);
        String notes = String.join("\n\n", parts);
        if (notes.isEmpty()) return null;
        return notes.length() > 20000 ? notes.substring(0, 20000) : notes;
    }

    static void keep(Context context, Capture capture) { setStatus(context, capture, "kept"); }

    static void archive(Context context, Capture capture) { setStatus(context, capture, "archived"); }

    /** Back to new: Deshacer, or «Al cajón» from the library or the archive. */
    static void reopen(Context context, Capture capture) { setStatus(context, capture, "new"); }

    /** Back to where it was (Deshacer after a move between library, archive and cajón). Keeps its kind. */
    static void restore(Context context, Capture capture) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", capture.status);
        data.put("kind", capture.kind);
        PausaDb.get(context).put("captures", capture.id, data, false);
    }

    private static void setStatus(Context context, Capture capture, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        if ("kept".equals(status) && (capture.kind == null || "task".equals(capture.kind))) {
            Suggestion s = capture.suggested();
            data.put("kind", s != null && !"task".equals(s.kind) ? s.kind : capture.url != null ? "link" : "note");
        }
        if ("new".equals(status)) { data.put("kind", null); data.put("task_id", null); }
        PausaDb.get(context).put("captures", capture.id, data, false);
    }

    static String kindLabel(String kind) {
        switch (kind == null ? "" : kind) {
            case "task": return "Tarea";
            case "link": return "Enlace";
            case "idea": return "Idea";
            case "note": return "Nota";
            default: return "Otra cosa";
        }
    }

    private static String opt(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }
}
