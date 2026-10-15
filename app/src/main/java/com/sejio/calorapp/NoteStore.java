package com.sejio.calorapp;

import android.content.Context;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The focus note: one short phrase or plan Sejio keeps in sight at the top of Hoy for the next
 * days. It is the synced setting `focus_note` holding JSON { text, until }, the same as
 * get_note / set_note in the backend (backend/src/domain.ts), so an agent can write it too.
 */
final class NoteStore {
    static final String SETTING = "focus_note";
    static final int MAX = 280;

    static final class Note {
        final String text;
        /** Last day it shows (YYYY-MM-DD), or null to keep it until it is changed. */
        final String until;
        Note(String text, String until) { this.text = text; this.until = until; }
    }

    private NoteStore() {}

    /** The note if there is one, also past its last day (the editor shows it); see {@link #active}. */
    static Note get(Context context) {
        LegacyImport.runOnce(context);
        Map<String, Object> row = PausaDb.get(context).row("settings", SETTING);
        if (row == null || Long.valueOf(1).equals(row.get("deleted"))) return null;
        try {
            JSONObject json = new JSONObject((String) row.get("value"));
            String text = json.optString("text", "").trim();
            String until = json.isNull("until") ? null : json.optString("until", null);
            return text.isEmpty() ? null : new Note(text, until);
        } catch (Exception broken) {
            PausaLog.w("note", "focus_note is not valid JSON");
            return null;
        }
    }

    /** The note to show on `day`, or null. */
    static Note active(Context context, String day) {
        Note note = get(context);
        return note == null || (note.until != null && note.until.compareTo(day) < 0) ? null : note;
    }

    /** Saves the note; an empty text removes it. */
    static void set(Context context, String text, String until) {
        String clean = text == null ? "" : text.trim();
        Map<String, Object> data = new HashMap<>();
        try {
            data.put("value", clean.isEmpty() ? "{}" : new JSONObject().put("text", clean.length() > MAX ? clean.substring(0, MAX) : clean)
                    .put("until", until == null ? JSONObject.NULL : until).toString());
        } catch (Exception impossible) {
            return;
        }
        PausaDb.get(context).put("settings", SETTING, data, clean.isEmpty());
        PausaLog.i("note", clean.isEmpty() ? "cleared" : "set" + (until == null ? "" : " until " + until));
    }
}
