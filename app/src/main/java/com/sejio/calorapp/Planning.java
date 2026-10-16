package com.sejio.calorapp;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Day names and offsets for Planificar and Apuntar. Days are YYYY-MM-DD, as in DayPlanStore. */
final class Planning {
    static final String[] WEEKDAYS = {"domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"};

    private Planning() {}

    /** Days from today to `day` (negative in the past). */
    static int offset(String day) {
        return (int) (Ledger.parseIso(day) - Ledger.parseIso(DayPlanStore.day(0)));
    }

    /** Hoy, Mañana, Ayer, a weekday within the week around today, or a short date. */
    static String dayName(String day) {
        int offset;
        try { offset = offset(day); } catch (RuntimeException invalid) { return day; }
        if (offset == 0) return "Hoy";
        if (offset == 1) return "Mañana";
        if (offset == -1) return "Ayer";
        if (offset > 1 && offset < 7) return PausaUi.capitalize(weekday(day));
        return Budget.date(Ledger.parseIso(day));
    }

    /** Three letters for the week strip: Hoy, Mañ, vie, sáb… */
    static String shortName(int offset) {
        if (offset == 0) return "Hoy";
        if (offset == 1) return "Mañ";
        return weekday(DayPlanStore.day(offset)).substring(0, 3);
    }

    static String weekday(String day) {
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(day));
            return WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception invalid) {
            return day;
        }
    }

    /** "jueves · tarde", "Mañana · en cualquier momento" for a task's cita. */
    static String describe(String day, String slot) {
        if (day == null) return "";
        int section = slot == null ? -1 : DayPlanStore.section(slot);
        return dayName(day) + " · " + (section < 0 ? "en cualquier momento" : TaskSheets.FRANJAS[section].toLowerCase(PausaUi.SPANISH));
    }
}
