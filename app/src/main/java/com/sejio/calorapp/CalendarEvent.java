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
}
