package com.sejio.calorapp;

import android.app.DatePickerDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.TextView;
import java.util.Calendar;

final class FollowupSheets {
    static void edit(Context c, TaskStore.Task task, Runnable changed) {
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, task == null ? "Nuevo seguimiento" : "En seguimiento");
        sheet.subtitle("Tenlo a la vista mientras esperas. Cuando te toque actuar, pásalo a pendientes.");
        EditText title = input(c, sheet, "Qué estás esperando", task == null ? "" : task.text);
        EditText waiting = input(c, sheet, "De quién o de qué depende (opcional)", task == null ? "" : task.waitingFor);
        String[] expected = {task == null ? "" : task.expectedDate}, review = {task == null ? "" : task.reviewDate};
        sheet.add(date(c, "Fecha prevista", expected), 8);
        sheet.add(date(c, "Volver a revisar", review), 16);
        if (task != null) {
            sheet.add(PausaUi.quiet(c, "Ahora me toca a mí", PausaUi.GREEN, () -> {
                TaskStore.setFollowup(c, task.id, false, task.waitingFor, task.expectedDate, task.reviewDate);
                sheet.dismiss(); changed.run();
            }), 4);
            sheet.add(PausaUi.quiet(c, "Marcar como resuelto", PausaUi.GREEN, () -> {
                TaskStore.setDone(c, task.id, true);
                sheet.dismiss(); changed.run();
            }), 4);
        }
        sheet.footer(task == null ? null : PausaUi.quiet(c, "Eliminar", PausaUi.TERRACOTTA, () -> {
            sheet.dismiss(); TaskSheets.deleteWithUndo(c, task, changed);
        }), PausaUi.action(c, "Guardar", true, () -> {
            String value = title.getText().toString().trim();
            if (value.isEmpty()) { title.setError("Escribe qué estás esperando"); return; }
            long id = task == null ? TaskStore.add(c, value) : task.id;
            if (task != null) TaskStore.setText(c, id, value);
            TaskStore.setFollowup(c, id, true, waiting.getText().toString(), expected[0], review[0]);
            sheet.dismiss(); changed.run();
        }));
        sheet.show();
    }

    static String detail(TaskStore.Task task) {
        String text = task.waitingFor;
        if (!task.reviewDate.isEmpty()) {
            boolean due = task.reviewDate.compareTo(DayPlanStore.day(0)) <= 0;
            text += (text.isEmpty() ? "" : " · ") + (due ? "Revisar hoy" : "Revisar " + readable(task.reviewDate));
        } else if (!task.expectedDate.isEmpty()) text += (text.isEmpty() ? "" : " · ") + "Previsto " + readable(task.expectedDate);
        return text.isEmpty() ? "A la espera" : text;
    }

    private static TextView date(Context c, String label, String[] value) {
        TextView button = PausaUi.quiet(c, "", PausaUi.GREEN, null);
        Runnable render = () -> button.setText(label + " · " + (value[0].isEmpty() ? "Sin fecha" : readable(value[0])));
        button.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            int[] selected = value[0].isEmpty() ? new int[]{now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)}
                    : Ledger.civil(Ledger.parseIso(value[0]));
            DatePickerDialog picker = new DatePickerDialog(c, (view, year, month, day) -> {
                value[0] = Ledger.iso(Ledger.epochDay(year, month + 1, day)); render.run();
            }, selected[0], selected[1] - 1, selected[2]);
            picker.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Quitar fecha", (dialog, which) -> { value[0] = ""; render.run(); });
            picker.show();
        });
        render.run();
        return button;
    }

    private static String readable(String date) {
        try { return Budget.date(Ledger.parseIso(date)); } catch (Exception invalid) { return date; }
    }

    private static EditText input(Context c, PausaUi.Sheet sheet, String label, String value) {
        sheet.add(PausaUi.eyebrow(c, label, PausaUi.MUTED), 6);
        EditText input = new EditText(c);
        PausaUi.input(input); input.setSingleLine(true); input.setText(value); input.setContentDescription(label);
        sheet.add(input, 14);
        return input;
    }
}
