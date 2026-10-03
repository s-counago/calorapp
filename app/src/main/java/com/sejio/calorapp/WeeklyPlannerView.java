package com.sejio.calorapp;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Half-hour agenda for the next seven days. Independent of the tomorrow board. */
final class WeeklyPlannerView extends PausaUi.Scroll {
    private static final long HALF_HOUR = 1800000L;
    private final LinearLayout content;
    private final LinearLayout strip;
    private final TextView dayTitle;
    private final TextView dayDetail;
    private final LinearLayout timeline;
    private int selectedDay;
    private Dialog sheet;

    WeeklyPlannerView(Context context) {
        super(context);
        setTag(PausaUi.SCROLL_TAG);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), 0);
        addView(content);
        content.addView(PausaUi.editorial(context, "Semana", 28), full());
        TextView sub = PausaUi.text(context, "Bloques de media hora para los próximos siete días", 13, PausaUi.MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(16));
        content.addView(sub, full());
        strip = new LinearLayout(context);
        content.addView(strip, full());
        dayTitle = PausaUi.editorial(context, "", 20);
        LinearLayout.LayoutParams tp = full();
        tp.topMargin = dp(22);
        content.addView(dayTitle, tp);
        dayDetail = PausaUi.text(context, "", 12, PausaUi.MUTED, false);
        dayDetail.setPadding(0, dp(4), 0, dp(12));
        content.addView(dayDetail, full());
        timeline = new LinearLayout(context);
        timeline.setOrientation(LinearLayout.VERTICAL);
        timeline.setBackground(PausaUi.card(context));
        timeline.setPadding(dp(10), dp(10), dp(10), dp(10));
        content.addView(timeline, full());
    }

    private Calendar dayStart(int index) {
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        day.add(Calendar.DAY_OF_YEAR, index);
        return day;
    }

    void refresh() { render(false); }

    private void render(boolean animateTimeline) {
        Map<Long, Long> assignments = PlannerStore.getAssignments(getContext(), PlannerStore.currentSlotStart());
        Map<Long, TaskStore.Task> tasks = new HashMap<>();
        for (TaskStore.Task task : TaskStore.getAll(getContext())) tasks.put(task.id, task);
        strip.removeAllViews();
        SimpleDateFormat weekday = new SimpleDateFormat("EEE", PausaUi.SPANISH);
        SimpleDateFormat longDay = new SimpleDateFormat("EEEE d 'de' MMMM", PausaUi.SPANISH);
        for (int index = 0; index < 7; index++) {
            final int position = index;
            Calendar day = dayStart(index);
            long start = day.getTimeInMillis(), end = dayStart(index + 1).getTimeInMillis();
            int count = 0;
            for (long time : assignments.keySet()) if (time >= start && time < end) count++;
            boolean selected = index == selectedDay;
            LinearLayout pill = new LinearLayout(getContext());
            pill.setOrientation(LinearLayout.VERTICAL);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(0, dp(8), 0, dp(8));
            pill.setBackground(PausaUi.ripple(getContext(), selected ? PausaUi.GREEN : index == 0 ? PausaUi.SUN_SOFT : PausaUi.NEUTRAL, 18));
            String name = index == 0 ? "hoy" : weekday.format(day.getTime()).replace(".", "");
            TextView label = PausaUi.eyebrow(getContext(), name, selected ? PausaUi.ON_NIGHT_MUTED : PausaUi.MUTED);
            label.setTextSize(10);
            label.setLetterSpacing(.06f);
            label.setGravity(Gravity.CENTER);
            pill.addView(label, new LinearLayout.LayoutParams(-2, -2));
            TextView number = PausaUi.editorial(getContext(), String.valueOf(day.get(Calendar.DAY_OF_MONTH)), 19);
            number.setTextColor(selected ? PausaUi.SURFACE : PausaUi.INK);
            number.setPadding(0, dp(4), 0, dp(4));
            number.setGravity(Gravity.CENTER);
            pill.addView(number, new LinearLayout.LayoutParams(-2, -2));
            View dot = new View(getContext());
            dot.setBackground(PausaUi.surface(getContext(), count > 0 ? (selected ? PausaUi.SUN : PausaUi.TERRACOTTA) : 0x00000000, 3));
            pill.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
            pill.setContentDescription("Ver " + longDay.format(day.getTime()) + ", " + count + (count == 1 ? " bloque" : " bloques"));
            pill.setSelected(selected);
            pill.setOnClickListener(v -> {
                if (selectedDay == position) return;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                selectedDay = position;
                render(true);
                post(this::scrollToFirstUseful);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (index > 0) p.leftMargin = dp(5);
            strip.addView(pill, p);
            if (selected) {
                dayTitle.setText(PausaUi.capitalize(index == 0 ? "Hoy, " + longDay.format(day.getTime()) : longDay.format(day.getTime())));
                dayDetail.setText(count == 0 ? "Sin bloques planificados. Toca un hueco para asignar una tarea."
                        : count + (count == 1 ? " bloque planificado" : " bloques planificados"));
                buildTimeline(start, end, assignments, tasks, longDay.format(day.getTime()));
            }
        }
        if (animateTimeline) { PausaUi.rise(timeline, 0); PausaUi.rise(dayTitle, 0); }
    }

    private void buildTimeline(long start, long end, Map<Long, Long> assignments, Map<Long, TaskStore.Task> tasks, String label) {
        timeline.removeAllViews();
        SimpleDateFormat hourFormat = new SimpleDateFormat("HH:mm", PausaUi.SPANISH);
        long first = Math.max(start, PlannerStore.currentSlotStart());
        long hourStart = first - ((first - start) % (2 * HALF_HOUR));
        for (long hour = hourStart; hour < end; hour += 2 * HALF_HOUR) {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTag(hour);
            TextView time = PausaUi.text(getContext(), hourFormat.format(new Date(hour)), 12, PausaUi.MUTED, true);
            time.setPadding(dp(4), 0, 0, 0);
            row.addView(time, new LinearLayout.LayoutParams(dp(50), -2));
            for (int half = 0; half < 2; half++) {
                long slot = hour + half * HALF_HOUR;
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(46), 1);
                if (half == 1) cp.leftMargin = dp(6);
                if (slot < first) { row.addView(new View(getContext()), cp); continue; }
                row.addView(cell(slot, tasks.get(assignments.get(slot)), label, hourFormat.format(new Date(slot))), cp);
            }
            LinearLayout.LayoutParams rp = full();
            rp.bottomMargin = dp(6);
            timeline.addView(row, rp);
        }
    }

    private View cell(long slot, TaskStore.Task task, String dayLabel, String hour) {
        TextView cell = PausaUi.text(getContext(), task == null ? hour.substring(2) : task.text, task == null ? 12 : 13,
                task == null ? 0xFFA9A69C : PausaUi.INK, task != null);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(12), 0, dp(8), 0);
        cell.setMaxLines(2);
        cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (task == null) {
            cell.setBackground(PausaUi.ripple(getContext(), PausaUi.CREAM, 14));
        } else {
            cell.setBackground(PausaUi.ripple(getContext(), task.done ? PausaUi.NEUTRAL : PausaUi.SAGE_SOFT, 14));
            if (task.done) {
                cell.setPaintFlags(cell.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                cell.setTextColor(PausaUi.MUTED);
            }
        }
        cell.setContentDescription("Bloque " + hour + ", " + (task == null ? "libre" : task.text + (task.done ? ", completada" : "")));
        cell.setOnClickListener(v -> pick(slot, task, PausaUi.capitalize(dayLabel) + " · " + hour));
        return cell;
    }

    private void scrollToFirstUseful() {
        View target = null;
        for (int i = 0; i < timeline.getChildCount(); i++) {
            View row = timeline.getChildAt(i);
            Calendar at = Calendar.getInstance();
            at.setTimeInMillis((Long) row.getTag());
            if (selectedDay > 0 && at.get(Calendar.HOUR_OF_DAY) == 8) { target = row; break; }
        }
        int y = target == null ? 0 : content.getTop() + timeline.getTop() + target.getTop() - dp(120);
        if (PausaUi.motion(getContext())) smoothScrollTo(0, Math.max(0, y)); else scrollTo(0, Math.max(0, y));
    }

    private void pick(long slot, TaskStore.Task assigned, String title) {
        if (slot < PlannerStore.currentSlotStart()) { refresh(); return; }
        PausaUi.Sheet s = new PausaUi.Sheet(getContext(), title).tall();
        if (assigned != null) s.subtitle("Ahora: " + assigned.text);
        Dialog dialog = s.dialog;
        sheet = dialog;
        dialog.setOnDismissListener(d -> { if (sheet == dialog) sheet = null; });
        LinearLayout inputRow = new LinearLayout(getContext());
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(getContext());
        PausaUi.input(input);
        input.setHint("Nueva tarea para este bloque");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Runnable create = () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            long id = TaskStore.add(getContext(), value);
            if (slot >= PlannerStore.currentSlotStart()) PlannerStore.assign(getContext(), slot, id);
            dialog.dismiss();
            refresh();
        };
        Button add = PausaUi.action(getContext(), "Asignar", true, create);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, dp(52));
        ap.leftMargin = dp(8);
        inputRow.addView(add, ap);
        input.setOnEditorActionListener((v, id, e) -> { if (PausaUi.isSubmit(id, e)) { create.run(); return true; } return false; });
        s.body.addView(inputRow, full());
        TextView label = PausaUi.eyebrow(getContext(), "Pendientes", PausaUi.MUTED);
        label.setPadding(dp(4), dp(20), 0, dp(6));
        s.body.addView(label, full());
        ScrollView listScroll = new ScrollView(getContext());
        listScroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        int pending = 0;
        for (TaskStore.Task task : TaskStore.getAll(getContext())) {
            if (task.done) continue;
            pending++;
            boolean current = assigned != null && assigned.id == task.id;
            TextView choice = PausaUi.text(getContext(), task.text, 16, current ? PausaUi.GREEN : PausaUi.INK, current);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setMinHeight(dp(52));
            choice.setPadding(dp(8), dp(8), dp(12), dp(8));
            choice.setCompoundDrawables(new PausaUi.Symbol(getContext(), current ? "check" : "arrow",
                    current ? PausaUi.SAGE : PausaUi.MUTED, 18), null, null, null);
            choice.setCompoundDrawablePadding(dp(14));
            choice.setBackground(PausaUi.ripple(getContext(), android.graphics.Color.TRANSPARENT, 14));
            choice.setOnClickListener(v -> {
                if (slot >= PlannerStore.currentSlotStart()) PlannerStore.assign(getContext(), slot, task.id);
                dialog.dismiss();
                refresh();
            });
            list.addView(choice, full());
        }
        if (pending == 0) {
            TextView none = PausaUi.text(getContext(), getContext().getString(R.string.planner_no_tasks), 14, PausaUi.MUTED, false);
            none.setPadding(dp(4), dp(8), 0, 0);
            list.addView(none, full());
        }
        listScroll.addView(list);
        s.body.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        s.footer(assigned == null ? null : PausaUi.quiet(getContext(), "Vaciar bloque", PausaUi.TERRACOTTA, () -> {
            PlannerStore.clear(getContext(), slot);
            dialog.dismiss();
            refresh();
            PausaUi.snack(getContext(), "Bloque vacío", "Deshacer", () -> {
                if (slot >= PlannerStore.currentSlotStart()) PlannerStore.assign(getContext(), slot, assigned.id);
                refresh();
            });
        }), PausaUi.quiet(getContext(), "Cerrar", PausaUi.GREEN, dialog::dismiss));
        s.show();
    }

    @Override protected void onDetachedFromWindow() {
        if (sheet != null) sheet.dismiss();
        super.onDetachedFromWindow();
    }

    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
