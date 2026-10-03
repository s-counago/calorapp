package com.sejio.calorapp;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class TrainSchedules {
    static final String OUTBOUND = "A Coruña → Santiago";
    static final String RETURN = "Santiago → A Coruña";

    static final List<TrainSchedule> A_CORUNA_TO_SANTIAGO = Collections.unmodifiableList(
            Arrays.asList(
                    train("05:14", "05:44", 30, "AVANT"),
                    train("05:30", "06:08", 38, "REGIONAL"),
                    train("06:00", "06:29", 29, "AVANT"),
                    train("07:00", "07:29", 29, "MD"),
                    train("07:10", "07:40", 30, "AVANT"),
                    train("07:45", "08:14", 29, "AVANT"),
                    train("08:00", "08:29", 29, "MD"),
                    train("09:00", "09:29", 29, "MD"),
                    train("09:35", "10:16", 41, "REGIONAL"),
                    train("10:00", "10:29", 29, "AVANT"),
                    train("10:50", "11:19", 29, "MD"),
                    train("12:00", "12:29", 29, "MD"),
                    train("12:45", "13:26", 41, "REGIONAL"),
                    train("13:35", "14:04", 29, "AVANT"),
                    train("14:05", "14:34", 29, "MD"),
                    train("15:00", "15:29", 29, "MD"),
                    train("15:08", "15:37", 29, "AVANT"),
                    train("15:39", "16:20", 41, "REGIONAL"),
                    train("15:54", "16:23", 29, "AVANT"),
                    train("17:00", "17:29", 29, "MD"),
                    train("17:15", "17:44", 29, "AVANT"),
                    train("18:00", "18:29", 29, "MD"),
                    train("19:08", "19:49", 41, "REGIONAL"),
                    train("19:33", "20:02", 29, "AVANT"),
                    train("21:00", "21:29", 29, "AVANT"),
                    train("21:40", "22:12", 32, "MD"),
                    train("22:10", "22:46", 36, "REGIONAL")
            )
    );

    static final List<TrainSchedule> SANTIAGO_TO_A_CORUNA = Collections.unmodifiableList(
            Arrays.asList(
                    train("06:35", "07:18", 43, "REGIONAL"),
                    train("07:12", "07:43", 31, "AVANT"),
                    train("07:42", "08:18", 36, "MD"),
                    train("08:27", "08:58", 31, "AVANT"),
                    train("08:58", "09:29", 31, "MD"),
                    train("10:01", "10:33", 32, "AVANT"),
                    train("10:37", "11:08", 31, "AVANT"),
                    train("10:57", "11:35", 38, "REGIONAL"),
                    train("11:38", "12:19", 41, "MD"),
                    train("13:22", "13:53", 31, "AVANT"),
                    train("14:10", "14:49", 39, "REGIONAL"),
                    train("14:53", "15:29", 36, "MD"),
                    train("15:07", "15:38", 31, "AVANT"),
                    train("15:33", "16:13", 40, "MD"),
                    train("16:12", "16:43", 31, "AVANT"),
                    train("16:38", "17:17", 39, "REGIONAL"),
                    train("17:22", "17:53", 31, "AVANT"),
                    train("17:28", "17:59", 31, "MD"),
                    train("17:45", "18:21", 36, "AVANT"),
                    train("18:36", "19:08", 32, "MD"),
                    train("19:13", "19:52", 39, "MD"),
                    train("19:42", "20:13", 31, "AVANT"),
                    train("20:33", "21:06", 33, "MD"),
                    train("22:07", "22:45", 38, "REGIONAL"),
                    train("22:22", "22:54", 32, "AVANT"),
                    train("22:58", "23:29", 31, "MD"),
                    train("23:10", "23:49", 39, "REGIONAL"),
                    train("23:28", "23:59", 31, "AVANT")
            )
    );

    private TrainSchedules() {
    }

    private static TrainSchedule train(String departure, String arrival, int duration, String service) {
        return new TrainSchedule(departure, arrival, duration, service);
    }
}
