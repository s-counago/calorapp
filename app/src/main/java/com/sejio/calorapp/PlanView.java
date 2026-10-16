package com.sejio.calorapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * Planificar (pausa_v7): one screen instead of Lista, Hoy, Mañana, Semana and Kanban. Each task
 * answers two questions only: its lane (what it is for me) and its cita (when I do it). The week
 * strip on top is where tasks go; the lanes below are where they come from. Tapping a day opens its
 * plan; tapping a task plans it on a day or moves it to another lane in one more tap.
 */
final class PlanView extends LinearLayout {
    private static final int PAGE = 50;
    private static final int DAYS = 7;

    private static String lane = "inbox";
    private final TodayView.Host host;
    private final TextView doneCount;
    private final LinearLayout week;
    private final LinearLayout chips;
    private final EditText input;
    private final LinearLayout list;
    private final ScrollView scroll;
    private int shown = PAGE;
    private int lastDone = -1;

    PlanView(Context context, TodayView.Host host) {
        super(context);
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(dp(20), dp(10), dp(20), 0);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.BOTTOM);
        TextView title = PausaUi.editorial(context, "Planificar", 28);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LayoutParams(0, -2, 1));
        LinearLayout done = new LinearLayout(context);
        done.setOrientation(VERTICAL);
        done.setGravity(Gravity.END);
        doneCount = PausaUi.editorial(context, "", 24);
        doneCount.setTextColor(PausaUi.TERRACOTTA);
        doneCount.setGravity(Gravity.END);
        done.addView(doneCount);
        done.addView(PausaUi.eyebrow(context, "Hechas", PausaUi.MUTED));
        header.addView(done, new LayoutParams(-2, -2));
        addView(header, full());

        week = new LinearLayout(context);
        LayoutParams weekParams = full();
        weekParams.topMargin = dp(14);
        addView(week, weekParams);

        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chips = new LinearLayout(context);
        chipScroll.addView(chips);
        LayoutParams chipParams = full();
        chipParams.topMargin = dp(16);
        addView(chipScroll, chipParams);

        LinearLayout composer = new LinearLayout(context);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setBackground(PausaUi.outline(context, PausaUi.SURFACE, PausaUi.LINE, 22));
        composer.setPadding(dp(6), dp(4), dp(6), dp(4));
        input = new EditText(context);
        PausaUi.input(input);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setTextSize(17);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setCompoundDrawables(new PausaUi.Symbol(context, "plus", PausaUi.MUTED, 20), null, null, null);
        input.setCompoundDrawablePadding(dp(12));
        input.setPadding(dp(12), dp(10), dp(8), dp(10));
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (PausaUi.isSubmit(actionId, event)) { addTask(); return true; }
            return false;
        });
        composer.addView(input, new LayoutParams(0, -2, 1));
        composer.addView(PausaUi.iconButton(context, "arrow", "Añadir", PausaUi.GREEN, this::addTask), new LayoutParams(dp(44), dp(44)));
        LayoutParams composerParams = full();
        composerParams.topMargin = dp(12);
        addView(composer, composerParams);

        scroll = new PausaUi.Scroll(context);
        scroll.setTag(PausaUi.SCROLL_TAG);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        list.setPadding(0, dp(12), 0, 0);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1));
        refresh();
    }

    void focusComposer() {
        scroll.smoothScrollTo(0, 0);
        input.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    /** Opens on a lane, e.g. the Bandeja from the «Cajón» line in Hoy. */
    void showLane(String target) {
        lane = target;
        shown = PAGE;
        scroll.scrollTo(0, 0);
        refresh();
    }

    /** The composer understands the Apuntar line too, with the lane on screen as its default. */
    private void addTask() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        QuickCapture.Parsed parsed = QuickCapture.parse(text, lane, QuickCapture.todayWeekday());
        String done = QuickCapture.apply(getContext(), parsed);
        input.setText("");
        refresh();
        if (!"task".equals(parsed.kind) || !lane.equals(parsed.lane) || parsed.day != null) PausaUi.snack(getContext(), done, null, null);
    }

    void refresh() {
        Context c = getContext();
        int done = TaskStore.doneTotal(c);
        if (lastDone >= 0 && lastDone != done) PausaUi.countTo(doneCount, lastDone, done, "");
        else doneCount.setText(PausaUi.number(done));
        doneCount.setContentDescription(done + " tareas hechas");
        lastDone = done;
        renderWeek();
        renderChips();
        input.setHint("Añadir a " + TaskStore.laneLabel(lane).toLowerCase(PausaUi.SPANISH));
        renderList();
    }

    // ------------------------------------------------------------ week strip

    private void renderWeek() {
        Context c = getContext();
        week.removeAllViews();
        for (int offset = 0; offset < DAYS; offset++) {
            final int day = offset;
            int count = TaskStore.openOn(c, DayPlanStore.day(offset));
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setMinimumHeight(dp(58));
            cell.setPadding(0, dp(6), 0, dp(6));
            cell.setBackground(PausaUi.ripple(c, offset == 0 ? PausaUi.SUN_SOFT : PausaUi.NEUTRAL, 16));
            TextView name = PausaUi.text(c, Planning.shortName(offset), 12, offset == 0 ? PausaUi.TERRACOTTA : PausaUi.MUTED, true);
            name.setGravity(Gravity.CENTER);
            cell.addView(name, new LayoutParams(-1, -2));
            TextView number = PausaUi.editorial(c, count == 0 ? "·" : String.valueOf(count), 18);
            number.setGravity(Gravity.CENTER);
            number.setPadding(0, dp(4), 0, 0);
            cell.addView(number, new LayoutParams(-1, -2));
            cell.setContentDescription(Planning.dayName(DayPlanStore.day(offset)) + ": " + count + (count == 1 ? " tarea" : " tareas") + ". Abrir");
            cell.setClickable(true);
            cell.setOnClickListener(v -> openDay(day));
            LayoutParams params = new LayoutParams(0, -2, 1);
            if (offset > 0) params.leftMargin = dp(5);
            week.addView(cell, params);
        }
    }

    /** Hoy and Mañana open their boards (drag between franjas); other days open a sheet. */
    private void openDay(int offset) {
        if (offset == 0) { host.open(6); return; }
        if (offset == 1) { host.open(4); return; }
        daySheet(DayPlanStore.day(offset));
    }

    private void daySheet(String day) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, Planning.dayName(day) + " · " + Budget.date(Ledger.parseIso(day)));
        List<TaskStore.Task> tasks = TaskStore.plannedOn(c, day);
        if (tasks.isEmpty()) {
            sheet.add(PausaUi.text(c, "Nada planificado. Toca una tarea de un carril para traerla a este día.", 14, PausaUi.MUTED, false), 12);
        }
        String lastSlot = "-";
        for (TaskStore.Task task : tasks) {
            String slot = task.planSlot == null ? "" : task.planSlot;
            if (!slot.equals(lastSlot)) {
                int section = task.planSlot == null ? -1 : DayPlanStore.section(task.planSlot);
                sheet.add(PausaUi.eyebrow(c, section < 0 ? "En cualquier momento" : DayPlanStore.LABELS[section], PausaUi.MUTED), 4);
                lastSlot = slot;
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            PausaUi.Check check = new PausaUi.Check(c, PausaUi.SAGE);
            check.setChecked(task.done);
            check.setContentDescription("Hecha: " + task.text);
            check.setListener(checked -> { TaskStore.setDone(c, task.id, checked); refresh(); });
            row.addView(check, new LayoutParams(dp(48), dp(48)));
            TextView text = PausaUi.text(c, task.text, 15, task.done ? PausaUi.MUTED : PausaUi.INK, false);
            text.setMaxLines(3);
            row.addView(text, new LayoutParams(0, -2, 1));
            row.addView(PausaUi.iconButton(c, "close", "Quitar de este día: " + task.text, PausaUi.MUTED, () -> {
                TaskStore.plan(c, task.id, null, null);
                row.setVisibility(View.GONE);
                refresh();
            }), new LayoutParams(dp(44), dp(44)));
            sheet.add(row, 2);
        }
        sheet.footer(null, PausaUi.quiet(c, "Listo", PausaUi.GREEN, sheet::dismiss));
        sheet.show();
    }

    // ------------------------------------------------------------ lanes

    private void renderChips() {
        Context c = getContext();
        Map<String, Integer> counts = TaskStore.laneCounts(c);
        int fresh = Features.isOn(c, Features.CAPTURES) ? CaptureStore.freshCount(c) : 0;
        chips.removeAllViews();
        for (int i = 0; i < TaskStore.LANES.length; i++) {
            final String key = TaskStore.LANES[i];
            int count = counts.get(key) + ("inbox".equals(key) ? fresh : 0);
            TextView chip = PausaUi.chip(c, TaskStore.LANE_LABELS[i] + "  " + PausaUi.number(count), key.equals(lane), () -> select(key));
            chip.setContentDescription(TaskStore.LANE_LABELS[i] + ", " + count);
            LayoutParams params = new LayoutParams(-2, -2);
            if (i > 0) params.leftMargin = dp(8);
            chips.addView(chip, params);
        }
    }

    private void select(String key) {
        if (key.equals(lane)) return;
        lane = key;
        shown = PAGE;
        scroll.scrollTo(0, 0);
        refresh();
        PausaUi.stagger(list, 8);
    }

    private void renderList() {
        Context c = getContext();
        list.removeAllViews();
        if ("inbox".equals(lane) && Features.isOn(c, Features.CAPTURES)) renderCajon();
        List<TaskStore.Task> tasks = TaskStore.lane(c, lane);
        if (tasks.isEmpty()) {
            if (list.getChildCount() > 0) return;
            TextView empty = PausaUi.text(c, emptyLine(), 14, PausaUi.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.15f);
            empty.setPadding(dp(12), dp(40), dp(12), 0);
            list.addView(empty, full());
            return;
        }
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setBackground(PausaUi.card(c));
        card.setClipToOutline(true);
        int visible = Math.min(shown, tasks.size());
        for (int i = 0; i < visible; i++) {
            if (i > 0) {
                LayoutParams line = new LayoutParams(-1, dp(1));
                line.leftMargin = dp(56);
                card.addView(PausaUi.hairline(c), line);
            }
            card.addView(row(tasks.get(i)), full());
        }
        list.addView(card, full());
        if (visible < tasks.size()) {
            int rest = tasks.size() - visible;
            TextView more = PausaUi.quiet(c, "Mostrar " + Math.min(PAGE, rest) + " más · quedan " + PausaUi.number(rest),
                    PausaUi.GREEN, () -> { shown += PAGE; refresh(); });
            LayoutParams params = full();
            params.topMargin = dp(8);
            list.addView(more, params);
        }
    }

    private String emptyLine() {
        switch (lane) {
            case "inbox": return "La bandeja está vacía. Lo que apuntes con + llega aquí hasta que decidas qué es.";
            case "priority": return "Nada en Prioridad. Sube algo del Stack cuando quieras hacerlo pronto.";
            case "waiting": return "No esperas nada de nadie.";
            case "failed": return "Ningún fracaso a la vista.";
            default: return "Nada por aquí.";
        }
    }

    private View row(TaskStore.Task task) {
        Context c = getContext();
        LinearLayout front = new LinearLayout(c);
        front.setGravity(Gravity.CENTER_VERTICAL);
        front.setMinimumHeight(dp(60));
        front.setPadding(dp(4), dp(6), dp(16), dp(6));
        front.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14214E3B), new ColorDrawable(PausaUi.SURFACE), null));
        PausaUi.Check check = new PausaUi.Check(c, PausaUi.SAGE);
        check.setContentDescription("Marcar como hecha: " + task.text);
        front.addView(check, new LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        texts.setPadding(dp(4), 0, 0, 0);
        TextView title = PausaUi.text(c, task.text, 16, PausaUi.INK, false);
        title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.1f);
        texts.addView(title, full());
        String detail = detail(task);
        if (!detail.isEmpty()) {
            TextView when = PausaUi.text(c, detail, 12, task.planDate != null && Planning.offset(task.planDate) < 0 ? PausaUi.TERRACOTTA : PausaUi.SAGE, true);
            when.setPadding(0, dp(4), 0, 0);
            texts.addView(when, full());
        }
        front.addView(texts, new LayoutParams(0, -2, 1));
        check.setListener(done -> {
            if (!done) return;
            TaskStore.setDone(c, task.id, true);
            postDelayed(this::refresh, PausaUi.motion(c) ? 420 : 0);
            PausaUi.snack(c, "Hecha.", "Deshacer", () -> { TaskStore.setDone(c, task.id, false); refresh(); });
        });
        front.setContentDescription(task.text + (detail.isEmpty() ? "" : ". " + detail) + ". Toca para planificarla o moverla.");
        front.setOnClickListener(v -> actions(task));
        PausaUi.SwipeRow swipe = new PausaUi.SwipeRow(c, front);
        swipe.setListener(() -> TaskSheets.deleteWithUndo(c, task, this::refresh));
        return swipe;
    }

    private static String detail(TaskStore.Task task) {
        String when = Planning.describe(task.planDate, task.planSlot);
        if (task.following && !task.waitingFor.isEmpty()) return "Esperando a " + task.waitingFor + (when.isEmpty() ? "" : " · " + when);
        return when;
    }

    /** Plan on a day (one tap), move to another lane (one tap), or edit the details. */
    private void actions(TaskStore.Task task) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Tarea");
        TextView text = PausaUi.text(c, task.text, 15, PausaUi.INK, false);
        text.setMaxLines(8);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setLineSpacing(0, 1.12f);
        sheet.add(text, 16);

        sheet.add(PausaUi.eyebrow(c, "Cuándo", PausaUi.MUTED), 8);
        LinearLayout days = new LinearLayout(c);
        if (task.planDate != null) {
            days.addView(choice(c, "Sin día", false, () -> {
                sheet.dismiss();
                TaskStore.plan(c, task.id, null, null);
                refresh();
                PausaUi.snack(c, "Ya no está planificada.", "Deshacer", () -> { TaskStore.plan(c, task.id, task.planDate, task.planSlot); refresh(); });
            }));
        }
        for (int offset = 0; offset < DAYS; offset++) {
            String day = DayPlanStore.day(offset);
            boolean current = day.equals(task.planDate);
            days.addView(choice(c, offset < 2 ? Planning.dayName(day) : Planning.shortName(offset), current, () -> {
                sheet.dismiss();
                if (current) return;
                TaskStore.plan(c, task.id, day, null);
                refresh();
                PausaUi.snack(c, "Planificada: " + Planning.dayName(day).toLowerCase(PausaUi.SPANISH) + ".", "Deshacer", () -> {
                    TaskStore.plan(c, task.id, task.planDate, task.planSlot); refresh();
                });
            }));
        }
        sheet.add(scrolling(days), 14);

        sheet.add(PausaUi.eyebrow(c, "Carril", PausaUi.MUTED), 8);
        LinearLayout lanes = new LinearLayout(c);
        String from = task.lane();
        for (int i = 0; i < TaskStore.LANES.length; i++) {
            final String to = TaskStore.LANES[i];
            final String label = TaskStore.LANE_LABELS[i];
            boolean current = to.equals(from);
            lanes.addView(choice(c, label, current, () -> {
                sheet.dismiss();
                if (current) return;
                TaskStore.setLane(c, task.id, to);
                refresh();
                PausaUi.snack(c, "Movida a " + label.toLowerCase(PausaUi.SPANISH) + ".", "Deshacer", () -> { TaskStore.setLane(c, task.id, from); refresh(); });
            }));
        }
        sheet.add(scrolling(lanes), 10);
        sheet.footer(
                PausaUi.quiet(c, "Borrar", PausaUi.TERRACOTTA, () -> { sheet.dismiss(); TaskSheets.deleteWithUndo(c, task, this::refresh); }),
                PausaUi.action(c, "Editar, franja u hora", false, () -> { sheet.dismiss(); TaskSheets.edit(c, task, this::refresh); }));
        sheet.show();
    }

    private View choice(Context c, String label, boolean selected, Runnable action) {
        TextView chip = PausaUi.chip(c, label, selected, action);
        LayoutParams params = new LayoutParams(-2, -2);
        params.rightMargin = dp(8);
        chip.setLayoutParams(params);
        return chip;
    }

    private HorizontalScrollView scrolling(LinearLayout row) {
        HorizontalScrollView scroller = new HorizontalScrollView(getContext());
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(row);
        return scroller;
    }

    // ------------------------------------------------------------ cajón

    /** New captures on top of the Bandeja: to a task, to the library, or archived. */
    private void renderCajon() {
        Context c = getContext();
        List<CaptureStore.Capture> captures = CaptureStore.fresh(c);
        if (captures.isEmpty()) return;
        TextView label = PausaUi.eyebrow(c, "Cajón · " + captures.size(), PausaUi.MUTED);
        label.setPadding(dp(4), 0, 0, dp(8));
        list.addView(label, full());
        int visible = Math.min(captures.size(), 20);
        for (int i = 0; i < visible; i++) {
            LayoutParams params = full();
            params.bottomMargin = dp(10);
            list.addView(captureCard(captures.get(i)), params);
        }
        if (captures.size() > visible) {
            TextView more = PausaUi.text(c, "Y " + (captures.size() - visible) + " más, en la web o cuando vacíes estas.", 13, PausaUi.MUTED, false);
            more.setPadding(dp(4), 0, 0, dp(12));
            list.addView(more, full());
        }
        TextView tasks = PausaUi.eyebrow(c, "Tareas", PausaUi.MUTED);
        tasks.setPadding(dp(4), dp(8), 0, dp(8));
        list.addView(tasks, full());
    }

    private View captureCard(CaptureStore.Capture capture) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setBackground(PausaUi.surface(c, PausaUi.SAGE_SOFT, 20));
        card.setPadding(dp(16), dp(12), dp(10), dp(6));
        TextView text = PausaUi.text(c, capture.title != null ? capture.title : capture.text, 15, PausaUi.INK, false);
        text.setMaxLines(4);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setLineSpacing(0, 1.12f);
        card.addView(text, full());
        if (capture.url != null) {
            TextView link = PausaUi.text(c, host(capture.url), 12, PausaUi.SAGE, true);
            link.setPadding(0, dp(4), 0, 0);
            link.setContentDescription("Abrir enlace");
            link.setOnClickListener(v -> {
                try { c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(capture.url))); }
                catch (RuntimeException none) { PausaUi.snack(c, "No hay app para abrir este enlace.", null, null); }
            });
            card.addView(link, full());
        }
        String suggestion = capture.suggestionLine();
        if (suggestion != null) {
            TextView hint = PausaUi.text(c, "Sugerencia: " + suggestion, 12, PausaUi.MUTED, false);
            hint.setPadding(0, dp(6), 0, 0);
            hint.setLineSpacing(0, 1.12f);
            card.addView(hint, full());
        }
        LinearLayout actions = new LinearLayout(c);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        CaptureStore.Suggestion s = capture.suggested();
        if (s != null) actions.addView(PausaUi.quiet(c, "Aceptar", PausaUi.GREEN, () -> accept(capture, s)));
        actions.addView(PausaUi.quiet(c, "Tarea", PausaUi.GREEN, () -> toTask(capture)));
        actions.addView(PausaUi.quiet(c, "Guardar", PausaUi.GREEN, () -> {
            CaptureStore.keep(c, capture);
            refresh();
            PausaUi.snack(c, "Guardada en la biblioteca.", "Deshacer", () -> { CaptureStore.reopen(c, capture); refresh(); });
        }));
        actions.addView(PausaUi.quiet(c, "Archivar", PausaUi.MUTED, () -> {
            CaptureStore.archive(c, capture);
            refresh();
            PausaUi.snack(c, "Archivada.", "Deshacer", () -> { CaptureStore.reopen(c, capture); refresh(); });
        }));
        card.addView(actions, full());
        return card;
    }

    private void accept(CaptureStore.Capture capture, CaptureStore.Suggestion s) {
        Context c = getContext();
        if ("task".equals(s.kind)) {
            CaptureStore.toTask(c, capture, s.title, s.lane == null ? "inbox" : s.lane, s.day);
            refresh();
            PausaUi.snack(c, "Ahora es una tarea de " + TaskStore.laneLabel(s.lane).toLowerCase(PausaUi.SPANISH) + ".", null, null);
        } else {
            CaptureStore.keep(c, capture);
            refresh();
            PausaUi.snack(c, "Guardada en la biblioteca.", "Deshacer", () -> { CaptureStore.reopen(c, capture); refresh(); });
        }
    }

    /** Which lane the new task goes to; the title can be edited first. */
    private void toTask(CaptureStore.Capture capture) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Convertir en tarea");
        EditText title = new EditText(c);
        PausaUi.input(title);
        title.setText(capture.title != null ? capture.title : capture.text);
        title.setSelection(title.length());
        title.setMaxLines(4);
        title.setContentDescription("Título de la tarea");
        sheet.add(title, 14);
        sheet.add(PausaUi.eyebrow(c, "A qué carril", PausaUi.MUTED), 8);
        LinearLayout lanes = new LinearLayout(c);
        for (int i = 0; i < TaskStore.LANES.length; i++) {
            final String to = TaskStore.LANES[i];
            lanes.addView(choice(c, TaskStore.LANE_LABELS[i], false, () -> {
                String value = title.getText().toString().trim();
                if (value.isEmpty()) { title.setError("Escribe un título"); return; }
                sheet.dismiss();
                CaptureStore.toTask(c, capture, value, to, null);
                refresh();
                PausaUi.snack(c, "Ahora es una tarea de " + TaskStore.laneLabel(to).toLowerCase(PausaUi.SPANISH) + ".", null, null);
            }));
        }
        sheet.add(scrolling(lanes), 8);
        sheet.footer(null, PausaUi.quiet(c, "Cancelar", PausaUi.MUTED, sheet::dismiss));
        sheet.show();
    }

    private static String host(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            return host == null ? url : host.startsWith("www.") ? host.substring(4) : host;
        } catch (RuntimeException invalid) {
            return url;
        }
    }

    private LayoutParams full() { return new LayoutParams(-1, -2); }
    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
