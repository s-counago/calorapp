package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class TicketPlan {
    static final String EXTRA_PLAN = "ticket_plan";
    static final String TRAVELER_SERGIO = "Sergio";
    static final String TRAVELER_MIRIAM = "Miriam";

    boolean sergio;
    boolean miriam;
    final List<Trip> trips = new ArrayList<>();

    String toJsonString() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("sergio", sergio);
        json.put("miriam", miriam);
        JSONArray tripArray = new JSONArray();
        for (Trip trip : trips) {
            tripArray.put(trip.toJson());
        }
        json.put("trips", tripArray);
        return json.toString();
    }

    static TicketPlan fromJsonString(String raw) throws JSONException {
        JSONObject json = new JSONObject(raw);
        TicketPlan plan = new TicketPlan();
        plan.sergio = json.getBoolean("sergio");
        plan.miriam = json.getBoolean("miriam");
        JSONArray tripArray = json.getJSONArray("trips");
        for (int index = 0; index < tripArray.length(); index++) {
            plan.trips.add(Trip.fromJson(tripArray.getJSONObject(index)));
        }
        return plan;
    }

    int travelerCount() {
        return (sergio ? 1 : 0) + (miriam ? 1 : 0);
    }

    static final class Trip {
        final String date;
        final boolean outbound;
        final TrainSchedule train;

        Trip(String date, boolean outbound, TrainSchedule train) {
            this.date = date;
            this.outbound = outbound;
            this.train = train;
        }

        String directionLabel() {
            return outbound ? TrainSchedules.OUTBOUND : TrainSchedules.RETURN;
        }

        /**
         * La casa está en Santiago: el tren que llega a Santiago es la vuelta y el
         * que sale hacia A Coruña es la ida.
         */
        String tripLabel() {
            return outbound ? "Vuelta" : "Ida";
        }

        String key() {
            return date + "|" + (outbound ? "outbound" : "return") + "|" + train.departure;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("date", date);
            json.put("outbound", outbound);
            json.put("train", train.toJson());
            return json;
        }

        static Trip fromJson(JSONObject json) throws JSONException {
            return new Trip(
                    json.getString("date"),
                    json.getBoolean("outbound"),
                    TrainSchedule.fromJson(json.getJSONObject("train"))
            );
        }
    }
}
