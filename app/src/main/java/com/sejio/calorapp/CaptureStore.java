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
        Capture(String id, String text, String url, String title, String suggestion, long createdAt) {
            this.id = id; this.text = text; this.url = url; this.title = title; this.suggestion = suggestion; this.createdAt = createdAt;
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
            captures.add(new Capture((String) row.get("id"), (String) row.get("text"), (String) row.get("url"),
                    (String) row.get("title"), (String) row.get("suggestion"), row.get("created_at") == null ? 0 : (Long) row.get("created_at")));
        }
        return captures;
    }

    static int freshCount(Context context) {
        LegacyImport.runOnce(context);
        return PausaDb.get(context).count("SELECT COUNT(*) FROM captures WHERE deleted = 0 AND status = 'new'");
    }

    /** Turns a capture into a task in `lane` (optionally planned on `day`), as file_capture does in the backend. */
    static long toTask(Context context, Capture capture, String title, String lane, String day) {
        String text = title == null || title.trim().isEmpty() ? capture.title != null ? capture.title : capture.text : title;
        long localId = TaskStore.add(context, text.length() > 500 ? text.substring(0, 500) : text, null);
        TaskStore.setLane(context, localId, lane);
        if (day != null) TaskStore.plan(context, localId, day, null);
        Map<String, Object> data = new HashMap<>();
        data.put("status", "filed");
        data.put("kind", "task");
        data.put("task_id", TaskStore.uid(context, localId));
        PausaDb.get(context).put("captures", capture.id, data, false);
        return localId;
    }

    static void keep(Context context, Capture capture) { setStatus(context, capture, "kept"); }

    static void archive(Context context, Capture capture) { setStatus(context, capture, "archived"); }

    /** Back to new, for Deshacer. */
    static void reopen(Context context, Capture capture) { setStatus(context, capture, "new"); }

    private static void setStatus(Context context, Capture capture, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        if ("kept".equals(status)) {
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
