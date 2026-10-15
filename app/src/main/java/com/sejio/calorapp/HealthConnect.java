package com.sejio.calorapp;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.RestingHeartRateRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads sleep, steps, exercise and resting heart rate from Health Connect, where the Pixel Watch
 * (through Google's health app) writes them, and keeps one `health_days` row per day that syncs
 * with the backend. Uses the Health Connect built into Android 14+, so older phones skip it.
 *
 * Reads happen while the app is open (Hoy refreshes them); Health Connect does not let apps read
 * in the background without an extra permission. Days close at 03:00 like the counters: steps and
 * exercise count from 03:00 to 03:00, and the sleep of a day is the night that ends on it
 * (sessions ending between 18:00 the day before and 18:00 that day, naps included).
 */
@android.annotation.TargetApi(34)
final class HealthConnect {
    static final int REQUEST_PERMISSIONS = 41;
    static final String SOURCE = "health_connect";
    /** How many days back each read refreshes, today included. Late syncs from the watch fill in past days. */
    static final int DAYS = 7;
    private static final long MIN_INTERVAL_MS = 5 * 60_000L;

    static final String[] PERMISSIONS = {
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_EXERCISE",
            "android.permission.health.READ_RESTING_HEART_RATE",
    };

    // SleepSessionRecord.StageType values that are not sleep: awake, out of bed, awake in bed.
    private static final int[] AWAKE_STAGES = {1, 3, 7};

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastRead;
    private static boolean reading;

    /** One day as the views need it. Any value can be null when the watch had nothing. */
    static final class Day {
        final String day;
        final Long sleepMinutes, steps, exerciseMinutes, restingHr;
        Day(String day, Long sleepMinutes, Long steps, Long exerciseMinutes, Long restingHr) {
            this.day = day; this.sleepMinutes = sleepMinutes; this.steps = steps;
            this.exerciseMinutes = exerciseMinutes; this.restingHr = restingHr;
        }
        boolean empty() { return sleepMinutes == null && steps == null && exerciseMinutes == null && restingHr == null; }
    }

    private HealthConnect() {}

    /** True on Android 14+ where Health Connect is part of the system. */
    static boolean available(Context context) {
        return Build.VERSION.SDK_INT >= 34 && manager(context) != null;
    }

    static boolean granted(Context context) {
        if (!available(context)) return false;
        for (String permission : PERMISSIONS) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Opens Health Connect's own permission screen. The answer arrives in MainActivity.onRequestPermissionsResult. */
    static void requestPermissions(Activity activity) {
        if (!available(activity)) return;
        PausaLog.i("health", "requesting permissions");
        activity.requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS);
    }

    /** The stored row of a day, or null. */
    static Day stored(Context context, String day) {
        Map<String, Object> row = PausaDb.get(context).row("health_days", day);
        if (row == null || Long.valueOf(1).equals(row.get("deleted"))) return null;
        return new Day(day, (Long) row.get("sleep_minutes"), (Long) row.get("steps"), (Long) row.get("exercise_minutes"), (Long) row.get("resting_hr"));
    }

    /**
     * Reads the last {@link #DAYS} days in the background and stores the ones that changed, then
     * runs `done` on the main thread if anything did. At most one read every few minutes.
     */
    static void refresh(Context context, Runnable done) {
        if (!granted(context)) return;
        long now = System.currentTimeMillis();
        synchronized (HealthConnect.class) {
            if (reading || now - lastRead < MIN_INTERVAL_MS) return;
            reading = true;
            lastRead = now;
        }
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            int changed = 0;
            try {
                changed = readAndStore(app);
            } catch (Exception e) {
                PausaLog.w("health", "read failed", e);
            } finally {
                synchronized (HealthConnect.class) { reading = false; }
            }
            if (changed > 0 && done != null) MAIN.post(done);
        });
    }

    private static int readAndStore(Context context) throws Exception {
        HealthConnectManager manager = manager(context);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.parse(CalorieStore.today());
        Instant now = Instant.now();
        int changed = 0;
        JSONObject summary = new JSONObject();
        for (int back = DAYS - 1; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            Instant from = day.atTime(CalorieStore.DAY_START_HOUR, 0).atZone(zone).toInstant();
            Instant to = min(day.plusDays(1).atTime(CalorieStore.DAY_START_HOUR, 0).atZone(zone).toInstant(), now);
            Long steps = aggregate(manager, from, to, StepsRecord.STEPS_COUNT_TOTAL);
            Long exerciseMs = aggregate(manager, from, to, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL);
            Long restingHr = aggregate(manager, from, to, RestingHeartRateRecord.BPM_AVG);
            Instant sleepFrom = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant();
            Instant sleepTo = min(day.atTime(18, 0).atZone(zone).toInstant(), now);
            Map<String, Object> data = new HashMap<>();
            data.put("day", day.toString());
            data.put("steps", steps == null || steps == 0 ? null : steps);
            data.put("exercise_minutes", exerciseMs == null ? null : Math.round(exerciseMs / 60_000.0));
            data.put("resting_hr", restingHr);
            data.put("source", SOURCE);
            data.put("sleep_minutes", null);
            data.put("sleep_start", null);
            data.put("sleep_end", null);
            if (sleepTo.isAfter(sleepFrom)) readSleep(manager, sleepFrom, sleepTo, data);
            if (store(context, day.toString(), data)) changed++;
            if (back == 0) summary.put("today", new JSONObject(data));
        }
        summary.put("changed", changed);
        PausaLog.i("health", "read " + DAYS + " days", summary);
        return changed;
    }

    /** Sums the time asleep of the sessions that end in the window, and keeps the start and end of the longest. */
    private static void readSleep(HealthConnectManager manager, Instant from, Instant to, Map<String, Object> data) throws Exception {
        ReadRecordsRequestUsingFilters<SleepSessionRecord> request = new ReadRecordsRequestUsingFilters.Builder<>(SleepSessionRecord.class)
                .setTimeRangeFilter(new TimeInstantRangeFilter.Builder().setStartTime(from.minusSeconds(18 * 3600)).setEndTime(to).build())
                .setPageSize(50)
                .build();
        List<SleepSessionRecord> sessions = HealthConnect.<ReadRecordsResponse<SleepSessionRecord>>await((executor, receiver) -> manager.readRecords(request, executor,
                new Receiver<>(receiver))).getRecords();
        long asleep = 0, longest = 0;
        for (SleepSessionRecord session : sessions) {
            Instant end = session.getEndTime();
            if (end.isBefore(from) || !end.isBefore(to)) continue;
            long ms = asleepMillis(session);
            asleep += ms;
            long length = end.toEpochMilli() - session.getStartTime().toEpochMilli();
            if (length > longest) {
                longest = length;
                data.put("sleep_start", session.getStartTime().toEpochMilli());
                data.put("sleep_end", end.toEpochMilli());
            }
        }
        if (asleep > 0) data.put("sleep_minutes", Math.round(asleep / 60_000.0));
    }

    /** Time asleep in a session: its sleep stages, or the whole session when the watch sent no stages. */
    static long asleepMillis(SleepSessionRecord session) {
        List<SleepSessionRecord.Stage> stages = session.getStages();
        if (stages == null || stages.isEmpty()) return session.getEndTime().toEpochMilli() - session.getStartTime().toEpochMilli();
        long total = 0;
        for (SleepSessionRecord.Stage stage : stages) {
            if (isAwake(stage.getType())) continue;
            total += stage.getEndTime().toEpochMilli() - stage.getStartTime().toEpochMilli();
        }
        return total;
    }

    static boolean isAwake(int stageType) {
        for (int awake : AWAKE_STAGES) if (awake == stageType) return true;
        return false;
    }

    /** Writes the day only when a value changed, so reading every few minutes does not flood the sync. */
    private static boolean store(Context context, String day, Map<String, Object> data) {
        boolean hasData = data.get("steps") != null || data.get("exercise_minutes") != null
                || data.get("resting_hr") != null || data.get("sleep_minutes") != null;
        Map<String, Object> current = PausaDb.get(context).row("health_days", day);
        if (current == null && !hasData) return false;
        if (current != null && !Long.valueOf(1).equals(current.get("deleted"))) {
            boolean same = true;
            for (Map.Entry<String, Object> field : data.entrySet()) {
                if (!Objects.equals(asLong(field.getValue()), asLong(current.get(field.getKey())))) { same = false; break; }
            }
            if (same) return false;
        }
        PausaDb.get(context).put("health_days", day, data, false);
        return true;
    }

    private static Object asLong(Object value) {
        return value instanceof Number ? (Object) ((Number) value).longValue() : value;
    }

    private static Long aggregate(HealthConnectManager manager, Instant from, Instant to,
                                  android.health.connect.datatypes.AggregationType<Long> type) throws Exception {
        if (!to.isAfter(from)) return null;
        AggregateRecordsRequest<Long> request = new AggregateRecordsRequest.Builder<Long>(
                new TimeInstantRangeFilter.Builder().setStartTime(from).setEndTime(to).build())
                .addAggregationType(type)
                .build();
        AggregateRecordsResponse<Long> response = HealthConnect.<AggregateRecordsResponse<Long>>await((executor, receiver) -> manager.aggregate(request, executor,
                new Receiver<>(receiver)));
        return response.get(type);
    }

    // ------------------------------------------------------------ async → blocking (we are on EXECUTOR)

    private interface Call<T> { void start(java.util.concurrent.Executor executor, Result<T> result); }

    private static final class Result<T> {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
    }

    private static final class Receiver<T> implements OutcomeReceiver<T, HealthConnectException> {
        private final Result<T> result;
        Receiver(Result<T> result) { this.result = result; }
        @Override public void onResult(T value) { result.value.set(value); result.latch.countDown(); }
        @Override public void onError(HealthConnectException error) { result.error.set(error); result.latch.countDown(); }
    }

    private static <T> T await(Call<T> call) throws Exception {
        Result<T> result = new Result<>();
        call.start(Runnable::run, result);
        if (!result.latch.await(20, TimeUnit.SECONDS)) throw new java.util.concurrent.TimeoutException("Health Connect did not answer");
        if (result.error.get() != null) throw result.error.get();
        return result.value.get();
    }

    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

    private static HealthConnectManager manager(Context context) {
        if (Build.VERSION.SDK_INT < 34) return null;
        try {
            return context.getSystemService(HealthConnectManager.class);
        } catch (Exception e) {
            return null;
        }
    }
}
