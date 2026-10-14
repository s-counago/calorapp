package com.sejio.calorapp;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Task capture and editing sheets, shared by the dashboard, the list and the FAB. */
final class TaskSheets {
    static final String[] FRANJAS = {"Mañana", "Tarde", "Noche"};
    static final String[] FRANJA_ICONS = {"sun", "sunset", "moon"};
    private static final String[] DAYS = {"Sin día", "Hoy", "Mañana", "Semana"};

    private TaskSheets() { }

    /** Where a task lives: day -1 means unplanned, 0 today, 1 tomorrow. */
    static final class Plan {
        int day, franja;
        int minute = -1, minutes = 30;
        String scheduleId;
        Plan(int day, int franja) { this.day = day; this.franja = franja; }
        String describe() {
            return day < 0 ? "tu lista" : (day == 0 ? "Hoy" : day == 1 ? "Mañana" : Budget.date(Ledger.parseIso(DayPlanStore.day(day))))
                    + (minute >= 0 ? String.format(java.util.Locale.ROOT, ", %02d:%02d", minute / 60, minute % 60) : ", " + DayPlanStore.LABELS[franja].toLowerCase(PausaUi.SPANISH));
        }
    }

    static int sectionOf(List<List<Long>> board, long id) {
        for (int i = 0; i < board.size(); i++) if (board.get(i).contains(id)) return i;
        return -1;
    }

    static Plan planOf(Context c, long id) {
        for (int day = 0; day < 7; day++) {
            int section = sectionOf(DayPlanStore.read(c, DayPlanStore.day(day)), id);
            if (section >= 0) return planFor(c, id, day, section);
        }
        return new Plan(-1, PausaUi.currentFranja());
    }

    static Plan planFor(Context c, long id, int day, int section) {
        Plan plan = new Plan(day, section);
        TaskScheduleStore.Schedule schedule = TaskScheduleStore.first(c, id, DayPlanStore.day(day));
        if (schedule != null) { plan.minute = schedule.minute(); plan.minutes = schedule.minutes; plan.scheduleId = schedule.id; }
        return plan;
    }

    /** Moves the task to the chosen day and franja, keeping its position when nothing changed. */
    static void applyPlan(Context c, long id, Plan plan) {
        if (plan.day >= 0 && plan.minute >= 0) {
            plan.franja = plan.minute < 720 ? 0 : plan.minute < 1200 ? 1 : 2;
            TaskScheduleStore.put(c, plan.scheduleId, id, TaskScheduleStore.start(DayPlanStore.day(plan.day), plan.minute), plan.minutes);
        } else if (plan.scheduleId != null) TaskScheduleStore.remove(c, plan.scheduleId);
        for (int day = 0; day < 7; day++) {
            String key = DayPlanStore.day(day);
            List<List<Long>> board = DayPlanStore.read(c, key);
            int current = sectionOf(board, id);
            boolean wanted = plan.day == day;
            if (wanted && current == plan.franja) continue;
            if (!wanted && current < 0) continue;
            for (List<Long> section : board) section.remove(id);
            if (wanted) board.get(plan.franja).add(id);
            DayPlanStore.save(c, key, board);
        }
    }

    /** Chips for day and franja. The franja row only appears once a day is chosen. */
    static LinearLayout planPicker(Context c, Plan plan, Runnable changed) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(PausaUi.eyebrow(c, "Cuándo", PausaUi.MUTED), spaced(c, 8));
        LinearLayout days = new LinearLayout(c);
        LinearLayout franjaBlock = new LinearLayout(c);
        franjaBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout franjas = new LinearLayout(c);
        TextView[] dayChips = new TextView[4];
        TextView[] franjaChips = new TextView[3];
        Runnable render = () -> {
            for (int i = 0; i < 4; i++) PausaUi.setChip(dayChips[i], i == 3 ? plan.day > 1 : plan.day == i - 1);
            for (int i = 0; i < 3; i++) {
                boolean on = plan.franja == i;
                PausaUi.setChip(franjaChips[i], on);
                franjaChips[i].setCompoundDrawables(new PausaUi.Symbol(c, FRANJA_ICONS[i],
                        on ? PausaUi.SURFACE : PausaUi.MUTED, 18), null, null, null);
            }
            boolean show = plan.day >= 0;
            if (show && franjaBlock.getVisibility() != View.VISIBLE) {
                franjaBlock.setVisibility(View.VISIBLE);
                PausaUi.rise(franjaBlock, 0);
            } else if (!show) franjaBlock.setVisibility(View.GONE);
        };
        for (int i = 0; i < 4; i++) {
            final int day = i - 1;
            dayChips[i] = PausaUi.chip(c, DAYS[i], false, () -> {
                if (day == 2) {
                    java.util.Calendar now = java.util.Calendar.getInstance();
                    android.app.DatePickerDialog picker = new android.app.DatePickerDialog(c, (view, year, month, date) -> {
                        plan.day = Ledger.epochDay(year, month + 1, date) - Ledger.parseIso(DayPlanStore.day(0));
                        dayChips[3].setText(Budget.date(Ledger.parseIso(DayPlanStore.day(plan.day))));
                        render.run(); if (changed != null) changed.run();
                    }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH));
                    picker.getDatePicker().setMinDate(TaskScheduleStore.start(DayPlanStore.day(0), 0));
                    picker.getDatePicker().setMaxDate(TaskScheduleStore.start(DayPlanStore.day(6), 1439));
                    picker.show();
                } else { plan.day = day; render.run(); if (changed != null) changed.run(); }
            });
            dayChips[i].setContentDescription("Planificar: " + DAYS[i]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) p.leftMargin = PausaUi.dp(c, 8);
            days.addView(dayChips[i], p);
        }
        column.addView(days, spaced(c, 14));
        franjaBlock.addView(PausaUi.eyebrow(c, "Franja", PausaUi.MUTED), spaced(c, 8));
        for (int i = 0; i < 3; i++) {
            final int franja = i;
            franjaChips[i] = PausaUi.chip(c, FRANJAS[i], false, () -> { plan.franja = franja; render.run(); if (changed != null) changed.run(); });
            franjaChips[i].setCompoundDrawablePadding(PausaUi.dp(c, 6));
            franjaChips[i].setPadding(PausaUi.dp(c, 10), 0, PausaUi.dp(c, 12), 0);
            franjaChips[i].setContentDescription("Franja: " + DayPlanStore.LABELS[i]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) p.leftMargin = PausaUi.dp(c, 8);
            franjas.addView(franjaChips[i], p);
        }
        franjaBlock.addView(franjas, spaced(c, 6));
        franjaBlock.addView(timePicker(c, plan, () -> { render.run(); if (changed != null) changed.run(); }), spaced(c, 0));
        column.addView(franjaBlock, spaced(c, 0));
        franjaBlock.setVisibility(plan.day >= 0 ? View.VISIBLE : View.GONE);
        render.run();
        return column;
    }

    static LinearLayout timePicker(Context c, Plan plan, Runnable changed) {
        LinearLayout column = new LinearLayout(c); column.setOrientation(LinearLayout.VERTICAL);
        TextView time = PausaUi.quiet(c, "", PausaUi.GREEN, null);
        TextView clear = PausaUi.quiet(c, "Quitar hora", PausaUi.MUTED, null);
        TextView duration = PausaUi.quiet(c, "", PausaUi.GREEN, null);
        Runnable render = () -> {
            time.setText(plan.minute < 0 ? "Añadir hora · opcional" : String.format(java.util.Locale.ROOT, "Hora · %02d:%02d", plan.minute / 60, plan.minute % 60));
            clear.setVisibility(plan.minute >= 0 ? View.VISIBLE : View.GONE);
            duration.setVisibility(plan.minute >= 0 ? View.VISIBLE : View.GONE);
            duration.setText("Duración · " + plan.minutes + " min");
            if (changed != null) changed.run();
        };
        time.setOnClickListener(v -> new android.app.TimePickerDialog(c, (picker, hour, minute) -> {
            plan.minute = hour * 60 + minute; plan.franja = hour < 12 ? 0 : hour < 20 ? 1 : 2; render.run();
        }, plan.minute < 0 ? 9 : plan.minute / 60, plan.minute < 0 ? 0 : plan.minute % 60, true).show());
        clear.setOnClickListener(v -> { plan.minute = -1; render.run(); });
        duration.setOnClickListener(v -> {
            PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Duración de la tarea");
            EditText input = new EditText(c); PausaUi.input(input);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setText(String.valueOf(plan.minutes));
            input.setContentDescription("Duración en minutos"); sheet.add(input, 12);
            sheet.footer(null, PausaUi.action(c, "Guardar", true, () -> {
                try {
                    int minutes = Integer.parseInt(input.getText().toString().trim());
                    if (minutes < 5 || minutes > 1440) throw new IllegalArgumentException();
                    plan.minutes = minutes; sheet.dismiss(); render.run();
                } catch (Exception invalid) { input.setError("Entre 5 y 1440 minutos"); }
            }));
            sheet.show();
        });
        column.addView(time); column.addView(duration); column.addView(clear);
        render.run();
        return column;
    }

    static boolean validTime(Plan plan, EditText input) {
        if (plan.day < 0 || plan.minute < 0) return true;
        try { TaskScheduleStore.start(DayPlanStore.day(plan.day), plan.minute); return true; }
        catch (IllegalArgumentException invalid) { input.setError("Esa hora no existe por el cambio de horario. Elige otra."); return false; }
    }

    /** Continuous capture: the sheet stays open so several tasks can be added in a row. */
    static void capture(Context c, int day, int franja, Runnable changed) {
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Nueva tarea");
        Plan plan = new Plan(day, franja);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(c);
        PausaUi.input(input);
        input.setHint("¿Qué quieres hacer?");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView feedback = PausaUi.text(c, "", 13, PausaUi.SAGE, true);
        Runnable updateHint = () -> feedback.setText("Se añadirá a " + plan.describe() + ".");
        Runnable add = () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("Escribe una tarea"); return; }
            if (!validTime(plan, input)) return;
            long id = TaskStore.add(c, value);
            if (plan.day >= 0) applyPlan(c, id, plan);
            input.setText("");
            input.requestFocus();
            feedback.setText("Añadida a " + plan.describe() + ". Puedes escribir otra.");
            feedback.announceForAccessibility("Tarea añadida");
            PausaUi.pop(feedback);
            if (changed != null) changed.run();
        };
        Button addButton = PausaUi.action(c, "Añadir", true, add);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, PausaUi.dp(c, 52));
        ap.leftMargin = PausaUi.dp(c, 8);
        row.addView(addButton, ap);
        input.setOnEditorActionListener((v, id, event) -> { if (PausaUi.isSubmit(id, event)) { add.run(); return true; } return false; });
        sheet.add(row, 18);
        sheet.add(planPicker(c, plan, updateHint), 12);
        sheet.add(feedback, 4);
        updateHint.run();
        sheet.footer(null, PausaUi.quiet(c, "Listo", PausaUi.GREEN, sheet::dismiss));
        showWithKeyboard(sheet);
        input.requestFocus();
    }

    /** Edit text, plan and deletion from a single sheet. */
    static Dialog edit(Context c, TaskStore.Task task, Runnable changed) {
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Editar tarea");
        EditText input = new EditText(c);
        PausaUi.input(input);
        input.setText(task.text);
        input.setSingleLine(true);
        input.setSelection(input.length());
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        sheet.add(input, 18);
        Plan plan = planOf(c, task.id);
        sheet.add(planPicker(c, plan, null), 4);
        if (!task.done) sheet.add(PausaUi.quiet(c, "Pasar a seguimiento", PausaUi.GREEN, () -> {
            sheet.dismiss(); FollowupSheets.edit(c, task, changed == null ? () -> {} : changed);
        }), 8);
        Runnable save = () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("Escribe una tarea"); return; }
            if (!validTime(plan, input)) return;
            TaskStore.setText(c, task.id, value);
            applyPlan(c, task.id, plan);
            sheet.dismiss();
            if (changed != null) changed.run();
        };
        input.setOnEditorActionListener((v, id, event) -> { if (PausaUi.isSubmit(id, event)) { save.run(); return true; } return false; });
        sheet.footer(PausaUi.quiet(c, "Eliminar", PausaUi.TERRACOTTA, () -> {
            sheet.dismiss();
            deleteWithUndo(c, task, changed);
        }), PausaUi.action(c, "Guardar", true, save));
        sheet.show();
        return sheet.dialog;
    }

    static void deleteWithUndo(Context c, TaskStore.Task task, Runnable changed) {
        int index = TaskStore.indexOf(c, task.id);
        TaskStore.delete(c, task.id);
        if (changed != null) changed.run();
        PausaUi.snack(c, "«" + task.text + "» eliminada", "Deshacer", () -> {
            TaskStore.restore(c, task, index);
            if (changed != null) changed.run();
        });
    }

    static void showWithKeyboard(PausaUi.Sheet sheet) {
        Window window = sheet.dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        sheet.show();
    }

    private static LinearLayout.LayoutParams spaced(Context c, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = PausaUi.dp(c, bottom);
        return p;
    }
}
