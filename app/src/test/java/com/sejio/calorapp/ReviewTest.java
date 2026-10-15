package com.sejio.calorapp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReviewTest {
    private static int day(String iso) { return Ledger.parseIso(iso); }

    @Test public void weeksRunMondayToSundayAndMonthsFollowTheCalendar() {
        assertArrayEquals(new int[]{day("2026-10-12"), day("2026-10-18")}, Review.range(day("2026-10-15"), Review.WEEK, 0));
        assertArrayEquals(new int[]{day("2026-10-12"), day("2026-10-18")}, Review.range(day("2026-10-18"), Review.WEEK, 0));
        assertArrayEquals(new int[]{day("2026-10-05"), day("2026-10-11")}, Review.range(day("2026-10-15"), Review.WEEK, -1));
        assertArrayEquals(new int[]{day("2026-10-01"), day("2026-10-31")}, Review.range(day("2026-10-15"), Review.MONTH, 0));
        assertArrayEquals(new int[]{day("2026-02-01"), day("2026-02-28")}, Review.range(day("2026-03-31"), Review.MONTH, -1));
        assertArrayEquals(new int[]{day("2025-12-01"), day("2025-12-31")}, Review.range(day("2026-01-10"), Review.MONTH, -1));
    }

    @Test public void labelsReadLikeSpanish() {
        assertEquals("12–18 oct", Review.rangeLabel(day("2026-10-12"), day("2026-10-18"), Review.WEEK));
        assertEquals("28 sep – 4 oct", Review.rangeLabel(day("2026-09-28"), day("2026-10-04"), Review.WEEK));
        assertEquals("Octubre 2026", Review.rangeLabel(day("2026-10-01"), day("2026-10-31"), Review.MONTH));
        assertEquals("Esta semana", Review.title(Review.WEEK, 0));
        assertEquals("El mes pasado", Review.title(Review.MONTH, -1));
        assertEquals("1,5", Review.oneDecimal(1.5));
    }

    @Test public void countersAverageOnlyTheDaysWithSomethingLogged() {
        Review.Summary s = summary("2026-10-12", "2026-10-18", "2026-10-15");
        Map<String, Integer> totals = new HashMap<>();
        totals.put("2026-10-12|calories", 1700);
        totals.put("2026-10-12|protein", 130);
        totals.put("2026-10-13|calories", 2600);
        totals.put("2026-10-13|cigarettes", 6);
        Map<String, Integer> previous = new HashMap<>();
        previous.put("2026-10-05|cigarettes", 10);
        Review.counters(s, totals, previous, day("2026-10-05"), day("2026-10-11"));

        assertEquals(2, s.daysLogged);
        assertEquals(2150, s.averageCalories);
        assertEquals(65, s.averageProtein);
        assertEquals(3.0, s.cigarettesPerDay, 1e-9);
        assertEquals(10.0, s.previousCigarettesPerDay, 1e-9);
        assertEquals(1, s.daysWithinCalories);
        assertEquals(1, s.daysReachingProtein);
        assertEquals(7, s.days.length);
        assertEquals(2600, s.days[1][0]);
        assertEquals(4, s.elapsed());
        // Wednesday was skipped; Thursday (today) is still running and does not count as missing.
        assertEquals(1, s.daysMissing());
        assertTrue(s.proteinLogged());
    }

    @Test public void habitTargetsScaleToTheMonthAndTheWeakestComeFirst() {
        Review.Summary s = summary("2026-10-01", "2026-10-31", "2026-10-31");
        List<String[]> habits = Arrays.asList(new String[]{"a", "Gym", "4"}, new String[]{"b", "Leer", "1"}, new String[]{"c", "Roto", "x"});
        List<String[]> checks = Arrays.asList(new String[]{"a", "2026-10-05"}, new String[]{"b", "2026-10-02"},
                new String[]{"b", "2026-10-09"}, new String[]{"b", "2026-10-16"}, new String[]{"b", "2026-10-23"}, new String[]{"b", "2026-10-23"});
        Review.habits(s, habits, checks);

        assertEquals("Roto", s.habits.get(0).name); // An unreadable goal counts as once a week.
        assertEquals(4, s.habits.get(0).target);
        Review.HabitLine gym = s.habits.get(1);
        assertEquals("Gym", gym.name);
        assertEquals(18, gym.target);
        assertEquals(1, gym.done);
        assertFalse(gym.met());
        Review.HabitLine leer = s.habits.get(2);
        assertEquals("Leer", leer.name);
        assertEquals(4, leer.target);
        assertEquals(4, leer.done);
        assertTrue(leer.met());
        assertEquals(1, s.habitsMet());
    }

    private static Review.Summary summary(String start, String end, String today) {
        Review.Summary s = new Review.Summary();
        s.start = day(start); s.end = day(end); s.today = day(today);
        s.period = s.end - s.start == 6 ? Review.WEEK : Review.MONTH;
        s.calorieGoal = 2450; s.proteinGoal = 120; s.cigaretteGoal = 12;
        return s;
    }
}
