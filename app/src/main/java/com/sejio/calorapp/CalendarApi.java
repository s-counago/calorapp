package com.sejio.calorapp;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class CalendarApi {
    static final class Failure extends IOException {
        final int status;
        Failure(int status) { super("Calendar HTTP " + status); this.status = status; }
    }

    private final OkHttpClient http;
    private final HttpUrl base;
    private final String token;

    CalendarApi(String token) { this(token, HttpUrl.get("https://www.googleapis.com/calendar/v3/")); }

    CalendarApi(String token, HttpUrl base) {
        this.token = token; this.base = base;
        http = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build();
    }

    String findCalendar(String marker) throws Exception {
        String page = "";
        for (int i = 0; i < 20; i++) {
            HttpUrl.Builder url = base.newBuilder().addPathSegments("users/me/calendarList").addQueryParameter("maxResults", "250");
            if (!page.isEmpty()) url.addQueryParameter("pageToken", page);
            JSONObject result = request("GET", url.build(), null);
            JSONArray items = result.optJSONArray("items");
            if (items != null) for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                if (marker.equals(item.optString("description")) && "owner".equals(item.optString("accessRole"))) return item.getString("id");
            }
            page = result.optString("nextPageToken");
            if (page.isEmpty()) return "";
        }
        throw new IOException("Calendar list limit");
    }

    String createCalendar(String marker, String zone) throws Exception {
        return request("POST", base.newBuilder().addPathSegment("calendars").build(),
                new JSONObject().put("summary", "Pausa").put("description", marker).put("timeZone", zone)).getString("id");
    }

    void upsert(String calendar, JSONObject event) throws Exception {
        HttpUrl collection = base.newBuilder().addPathSegment("calendars").addPathSegment(calendar).addPathSegment("events")
                .addQueryParameter("sendUpdates", "none").build();
        HttpUrl item = base.newBuilder().addPathSegment("calendars").addPathSegment(calendar).addPathSegment("events")
                .addPathSegment(event.getString("id")).addQueryParameter("sendUpdates", "none").build();
        try { request("POST", collection, event); }
        catch (Failure failure) {
            if (failure.status != 409) throw failure;
            request("PATCH", item, event);
        }
    }

    void delete(String calendar, String id) throws Exception {
        HttpUrl item = base.newBuilder().addPathSegment("calendars").addPathSegment(calendar).addPathSegment("events")
                .addPathSegment(id).addQueryParameter("sendUpdates", "none").build();
        try { request("DELETE", item, null); }
        catch (Failure failure) { if (failure.status != 404 && failure.status != 410) throw failure; }
    }

    private JSONObject request(String method, HttpUrl url, JSONObject body) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        RequestBody payload = body == null ? null : RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(url).header("Authorization", "Bearer " + token).method(method, payload).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Failure(response.code());
            if (response.code() == 204 || response.body() == null) return new JSONObject();
            String text = response.peekBody(1024 * 1024 + 1).string();
            if (text.length() > 1024 * 1024) throw new IOException("Calendar response limit");
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        }
    }
}
