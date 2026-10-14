package com.sejio.calorapp;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CalendarApiTest {
    private final String id = "0123456789abcdef0123456789abcdef";

    @Test public void retryUpdatesTheSameEventInsteadOfCreatingAnother() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(409));
            server.enqueue(new MockResponse().setBody("{}"));
            JSONObject event = CalendarEvent.body(id, "Revisar informe", 0, 45, "Europe/Madrid");
            new CalendarApi("test-token", server.url("/calendar/v3/")).upsert("pausa@example.test", event);
            RecordedRequest create = server.takeRequest(), update = server.takeRequest();
            assertEquals("POST", create.getMethod());
            assertEquals("PATCH", update.getMethod());
            assertTrue(update.getPath().contains("/events/" + id));
            assertEquals(event.toString(), update.getBody().readUtf8());
            assertEquals("1970-01-01T00:45:00Z", event.getJSONObject("end").getString("dateTime"));
            assertEquals("Europe/Madrid", event.getJSONObject("start").getString("timeZone"));
        }
    }

    @Test public void deletedRemoteEventIsAlreadyRemoved() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(410));
            new CalendarApi("test-token", server.url("/")).delete("pausa", id);
            assertEquals("DELETE", server.takeRequest().getMethod());
        }
    }

    @Test public void calendarRecoveryUsesMarkerAndOwnershipAcrossPages() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"items\":[{\"id\":\"wrong\",\"description\":\"marker\",\"accessRole\":\"reader\"}],\"nextPageToken\":\"two\"}"));
            server.enqueue(new MockResponse().setBody("{\"items\":[{\"id\":\"pausa\",\"description\":\"marker\",\"accessRole\":\"owner\"}]}"));
            assertEquals("pausa", new CalendarApi("test-token", server.url("/")).findCalendar("marker"));
            server.takeRequest();
            assertTrue(server.takeRequest().getPath().contains("pageToken=two"));
        }
    }

    @Test public void failedWritePreservesStatusForRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("private server detail"));
            try {
                new CalendarApi("test-token", server.url("/")).upsert("pausa", CalendarEvent.body(id, "Task", 0, 30, "UTC"));
                fail();
            } catch (CalendarApi.Failure failure) {
                assertEquals(503, failure.status);
                assertFalse(failure.getMessage().contains("private"));
                assertEquals(1, server.getRequestCount());
            }
        }
    }
}
