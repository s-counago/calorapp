package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Run only on a disposable emulator: replaces the weekly habit preferences. */
public final class HabitStoreSmokeTest extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            context.getSharedPreferences("weekly_habits", 0).edit().clear().commit();
            String currentWeek = HabitStore.weekStart(HabitStore.today());
            String previousWeek = HabitStore.addDays(currentWeek, -7);

            long id = HabitStore.add(context, "Llamar a mis abuelas", 2);
            HabitStore.ToggleResult first = HabitStore.setDoneOnDay(context, id, previousWeek, true);
            HabitStore.ToggleResult firstMinimum = HabitStore.setDoneOnDay(
                    context, id, HabitStore.addDays(previousWeek, 1), true);
            check(first.pointChange == 0, "first mark has no points");
            check(firstMinimum.pointChange == 5 && firstMinimum.minimumReached,
                    "first weekly minimum gives five");

            HabitStore.setDoneOnDay(context, id, currentWeek, true);
            HabitStore.ToggleResult secondMinimum = HabitStore.setDoneOnDay(
                    context, id, HabitStore.addDays(currentWeek, 1), true);
            HabitStore.ToggleResult extra = HabitStore.setDoneOnDay(
                    context, id, HabitStore.addDays(currentWeek, 2), true);
            check(secondMinimum.pointChange == 6, "consecutive minimum grows to six");
            check(extra.pointChange == 2, "twenty-percent bonus rounds upward");
            check(HabitStore.totalPoints(HabitStore.getAll(context).get(0)) == 13,
                    "rewards accumulate");
            HabitStore.ToggleResult undo = HabitStore.setDoneOnDay(
                    context, id, HabitStore.addDays(currentWeek, 2), false);
            check(undo.pointChange == -2, "same-day reward can be undone");
            check(HabitStore.totalPoints(HabitStore.getAll(context).get(0)) == 11,
                    "undo restores total");

            int[] expectedRewards = {5, 6, 7, 9, 11, 13, 15, 18, 21, 25};
            int reward = 5;
            for (int expected : expectedRewards) {
                check(reward == expected, "geometric reward " + expected);
                reward = HabitStore.nextBaseReward(reward);
            }

            int elapsed = HabitStore.daysElapsedThisWeek();
            int target = Math.min(3, elapsed);
            int goal = Math.min(2, elapsed);
            long correctedId = HabitStore.add(context, "Marcas olvidadas", goal);
            HabitStore.update(context, correctedId, "Marcas olvidadas", goal, target);
            HabitStore.Habit corrected = HabitStore.getAll(context).get(1);
            int correctedPoints = 5 + target - goal;
            check(HabitStore.completedThisWeek(corrected) == target, "manual weekly count persists");
            check(HabitStore.totalPoints(corrected) == correctedPoints, "correction awards minimum and extras");
            HabitStore.update(context, correctedId, "Marcas olvidadas", goal, target);
            check(HabitStore.totalPoints(HabitStore.getAll(context).get(1)) == correctedPoints,
                    "repeated correction does not duplicate rewards");
            if (target < elapsed) {
                check(!HabitStore.isDoneToday(corrected), "backfill does not mark today unnecessarily");
                HabitStore.setDoneToday(context, correctedId, true);
                check(HabitStore.completedThisWeek(HabitStore.getAll(context).get(1)) == target + 1,
                        "daily checkbox increments corrected count");
                HabitStore.setDoneToday(context, correctedId, false);
                check(HabitStore.totalPoints(HabitStore.getAll(context).get(1)) == correctedPoints,
                        "checkbox undo preserves corrected past marks");
            } else {
                check(HabitStore.isDoneToday(corrected), "full elapsed count includes today");
            }
            HabitStore.update(context, correctedId, "Marcas olvidadas", goal, 0);
            corrected = HabitStore.getAll(context).get(1);
            check(HabitStore.completedThisWeek(corrected) == 0 && !HabitStore.isDoneToday(corrected),
                    "zero correction clears today's checkbox");
            check(HabitStore.totalPoints(corrected) == 0, "dropping below minimum retracts current rewards");
            HabitStore.update(context, correctedId, "Marcas olvidadas", 1, 1);
            check(HabitStore.totalPoints(HabitStore.getAll(context).get(1)) == 5,
                    "restoring minimum awards only once");

            check(HabitStore.graphWeeks(context) == 10, "chart defaults to ten weeks");
            HabitStore.setGraphWeeks(context, 52);
            check(HabitStore.graphWeeks(context) == 52, "chart window persists");
            List<Integer> year = HabitStore.sparkline(HabitStore.getAll(context).get(1), 52);
            check(year.size() == 52 && year.get(51) == 5, "year chart ends at current score");
            check(HabitStore.sparkline(HabitStore.getAll(context).get(1), 104).size() == 104,
                    "two-year chart includes every selected week");
            HabitStore.update(context, id, "Llamar a mis abuelas", 2, 0);
            check(HabitStore.totalPoints(HabitStore.getAll(context).get(0)) == 5,
                    "current corrections preserve closed-week points");
            long growthId = HabitStore.add(context, "Crecimiento", 1);
            int[] weeklyRewards = {5, 6, 7, 9};
            for (int index = 0; index < weeklyRewards.length; index++) {
                HabitStore.ToggleResult weekResult = HabitStore.setDoneOnDay(
                        context, growthId, HabitStore.addDays(currentWeek, index * 7), true);
                check(weekResult.pointChange == weeklyRewards[index], "stored weekly growth " + index);
            }

            // One completed week worth five, followed by two missed weeks: 5 -> 4 -> 3.
            context.getSharedPreferences("weekly_habits", 0).edit().clear().commit();
            String earnedWeek = HabitStore.addDays(currentWeek, -21);
            String earnedDay = HabitStore.addDays(earnedWeek, 1);
            JSONObject habit = new JSONObject();
            habit.put("id", 99L);
            habit.put("name", "Fixture de caída");
            habit.put("weeklyGoal", 2);
            habit.put("createdDay", earnedWeek);
            habit.put("completedDays", new JSONArray().put(earnedWeek).put(earnedDay));
            habit.put("rewardsByDay", new JSONObject().put(earnedWeek, 0).put(earnedDay, 5));
            habit.put("baseRewards", new JSONObject().put(earnedWeek, 5));
            habit.put("baseAwardDays", new JSONObject().put(earnedWeek, earnedDay));
            context.getSharedPreferences("weekly_habits", 0).edit()
                    .putString("habits_v1", new JSONArray().put(habit).toString()).commit();
            List<HabitStore.Habit> decayed = HabitStore.getAll(context);
            check(HabitStore.totalPoints(decayed.get(0)) == 3,
                    "two missed weeks each decay the then-current score by fifteen percent");
            check(HabitStore.previousWeekPenalty(decayed.get(0)) == 1,
                    "last weekly penalty is recorded once");
            check(HabitStore.totalPoints(HabitStore.getAll(context).get(0)) == 3,
                    "settling a week is idempotent");

            result.putString("stream", "PASS: " + checks + " habit checks.");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
