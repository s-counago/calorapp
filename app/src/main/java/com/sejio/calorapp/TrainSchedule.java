package com.sejio.calorapp;

import org.json.JSONException;
import org.json.JSONObject;

final class TrainSchedule {
    final String departure;
    final String arrival;
    final int durationMinutes;
    final String service;

    TrainSchedule(String departure, String arrival, int durationMinutes, String service) {
        this.departure = departure;
        this.arrival = arrival;
        this.durationMinutes = durationMinutes;
        this.service = service;
    }

    String displayName() {
        return departure + "  →  " + arrival + "   ·   " + service
                + "   ·   " + durationMinutes + " min";
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("departure", departure);
        json.put("arrival", arrival);
        json.put("durationMinutes", durationMinutes);
        json.put("service", service);
        return json;
    }

    static TrainSchedule fromJson(JSONObject json) throws JSONException {
        return new TrainSchedule(
                json.getString("departure"),
                json.getString("arrival"),
                json.getInt("durationMinutes"),
                json.getString("service")
        );
    }
}
