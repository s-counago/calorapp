package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import java.util.List;

/**
 * Disposable emulator only: replaces counters, tasks, plans and habits with a realistic demo day
 * for visual review of the Amanecer redesign.
 */
public final class PausaDemoSeed extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context c = getTargetContext();
            for (String prefs : new String[]{"daily_tasks", "day_boards", "short_term_planner", "weekly_habits", "calories"}) {
                c.getSharedPreferences(prefs, 0).edit().clear().commit();
            }
            c.getSharedPreferences("day_boards", 0).edit().putBoolean("migrated", true).commit();
            CalorieStore.add(c, 1240);
            CalorieStore.addProtein(c, 85);
            CalorieStore.adjustCigaretteCount(c, 4);
            CalorieStore.setCigaretteGoal(c, 10);

            long informe = TaskStore.add(c, "Preparar el informe trimestral");
            long llamar = TaskStore.add(c, "Llamar al fontanero");
            long correr = TaskStore.add(c, "Salir a correr 5 km");
            long cena = TaskStore.add(c, "Comprar para la cena");
            long leer = TaskStore.add(c, "Leer 20 páginas");
            long propuesta = TaskStore.add(c, "Enviar propuesta a Laura");
            long docs = TaskStore.add(c, "Revisar documentación del proyecto");
            TaskStore.add(c, "Renovar el DNI");
            TaskStore.add(c, "Regalo de cumpleaños de mamá");
            long done = TaskStore.add(c, "Pagar la luz");
            TaskStore.setDone(c, done, true);
            TaskStore.setDone(c, correr, true);

            List<List<Long>> today = DayPlanStore.empty();
            today.get(0).add(informe); today.get(0).add(correr);
            today.get(1).add(llamar); today.get(1).add(cena);
            today.get(2).add(leer);
            DayPlanStore.save(c, DayPlanStore.day(0), today);
            List<List<Long>> tomorrow = DayPlanStore.empty();
            tomorrow.get(0).add(propuesta); tomorrow.get(0).add(docs);
            tomorrow.get(1).add(leer);
            DayPlanStore.save(c, DayPlanStore.day(1), tomorrow);
            long slot = PlannerStore.currentSlotStart() + 26L * 3600000L;
            PlannerStore.assign(c, slot - slot % 3600000L, propuesta);
            PlannerStore.assign(c, slot - slot % 3600000L + 1800000L, docs);

            long abuelas = HabitStore.add(c, "Llamar a mis abuelas", 2);
            long gym = HabitStore.add(c, "Entrenar fuerza", 3);
            long agua = HabitStore.add(c, "Beber dos litros de agua", 5);
            String week = HabitStore.weekStart(HabitStore.today());
            int elapsed = HabitStore.daysElapsedThisWeek();
            for (int back = 8; back >= 1; back--) {
                String start = HabitStore.addDays(week, -7 * back);
                HabitStore.setDoneOnDay(c, abuelas, HabitStore.addDays(start, 1), true);
                HabitStore.setDoneOnDay(c, abuelas, HabitStore.addDays(start, 5), true);
                if (back != 3) {
                    for (int d : new int[]{0, 2, 4}) HabitStore.setDoneOnDay(c, gym, HabitStore.addDays(start, d), true);
                }
                for (int d = 0; d < (back % 2 == 0 ? 6 : 4); d++) HabitStore.setDoneOnDay(c, agua, HabitStore.addDays(start, d), true);
            }
            for (int d = 0; d < elapsed - 1; d++) {
                if (d % 2 == 0) HabitStore.setDoneOnDay(c, gym, HabitStore.addDays(week, d), true);
                HabitStore.setDoneOnDay(c, agua, HabitStore.addDays(week, d), true);
            }
            if (elapsed > 1) HabitStore.setDoneOnDay(c, abuelas, HabitStore.addDays(week, 0), true);
            HabitStore.setGraphWeeks(c, 10);
            // Preferences are written asynchronously; let them reach disk before the process ends.
            android.os.SystemClock.sleep(2500);
            result.putString("stream", "SEEDED demo data");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
