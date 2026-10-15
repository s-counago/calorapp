package com.sejio.calorapp;

import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class CalendarEvent {
    static JSONObject body(String id, String title, long start, int minutes, String zone) throws Exception {
        if (minutes < 5 || minutes > 1440 || !id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return new JSONObject().put("id", id).put("summary", title)
                .put("start", new JSONObject().put("dateTime", format.format(new Date(start))).put("timeZone", zone))
                .put("end", new JSONObject().put("dateTime", format.format(new Date(start + minutes * 60000L))).put("timeZone", zone))
                .put("extendedProperties", new JSONObject().put("private", new JSONObject().put("pausaId", id)));
    }

    /** An all-day event on `day` (YYYY-MM-DD), for tasks planned for any time that day. */
    static JSONObject allDay(String id, String title, String day) throws Exception {
        if (!id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        Date start = format.parse(day);
        String end = format.format(new Date(start.getTime() + 86_400_000L));
        return new JSONObject().put("id", id).put("summary", title)
                .put("start", new JSONObject().put("date", day))
                .put("end", new JSONObject().put("date", end))
                .put("transparency", "transparent")
                .put("extendedProperties", new JSONObject().put("private", new JSONObject().put("pausaId", id)));
    }

    /** A Calendar event id (lowercase hex, 32 characters) that stays the same for a task. */
    static String idFor(String taskId) {
        String hex = taskId.replace("-", "").toLowerCase(Locale.ROOT);
        return hex.matches("[0-9a-f]{32}") ? hex : okio.ByteString.encodeUtf8(taskId).sha256().hex().substring(0, 32);
    }
}
