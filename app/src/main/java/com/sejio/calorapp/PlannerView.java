package com.sejio.calorapp;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Paint;
import android.os.Build;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Day board with three flexible franjas. Long-press drags between them; tap edits. */
final class PlannerView extends LinearLayout {
    interface Clock { Calendar now(); }
    private static final int[] TINTS = {PausaUi.SUN_SOFT, PausaUi.PEACH_SOFT, PausaUi.SAGE_SOFT};
    private static final int[] ACCENTS = {0xFFC98A1C, PausaUi.TERRACOTTA, PausaUi.GREEN};
    private final int dayOffset;
    private final Clock clock;
    private final TextView title;
    private final TextView dateLine;
    private final ScrollView scroll;
    private final LinearLayout board;
    private final LinearLayout[] zones = new LinearLayout[3];
    private final LinearLayout[] cards = new LinearLayout[3];
    private final View gap;
    private String date;
    private List<List<Long>> plan;
    private View dragged;
    private long draggedId;
    private int dropSection, dropIndex, scrollStep;
    private float pointerY;
    private Dialog sheet;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!dayKey(targetDay()).equals(date) && dragged == null) {
                refresh();
            }
            if (isShown()) postDelayed(this, 1000);
        }
    };
    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (dragged == null || scrollStep == 0) return;
            scroll.scrollBy(0, scrollStep);
            placeGap(pointerY + scroll.getScrollY());
            postDelayed(this, 32);
        }
    };

    PlannerView(Context context) {
        this(context, 1);
    }

    PlannerView(Context context, int dayOffset) {
        this(context, dayOffset, Calendar::getInstance);
    }

    PlannerView(Context context, int dayOffset, Clock clock) {
        super(context);
        this.dayOffset = dayOffset;
        this.clock = clock;
        setOrientation(VERTICAL);
        setPadding(dp(20), dp(10), dp(20), 0);
        title = PausaUi.editorial(context, "", 28);
        addView(title, full());
        dateLine = PausaUi.text(context, "", 13, PausaUi.MUTED, false);
        dateLine.setLineSpacing(0, 1.12f);
        dateLine.setPadding(0, dp(6), 0, dp(14));
        addView(dateLine, full());
        scroll = new PausaUi.Scroll(context);
        scroll.setTag(PausaUi.SCROLL_TAG);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        board = column();
        board.setPadding(0, 0, 0, dp(8));
        scroll.addView(board);
        addView(scroll, new LayoutParams(-1, 0, 1));
        gap = new View(context);
        gap.setBackground(PausaUi.dashed(context, PausaUi.SAGE, 16));
        board.setOnDragListener((view, event) -> onBoardDrag(event));
        refresh();
    }

    void refresh() {
        if (dragged != null) return;
        Calendar target = targetDay();
        String nextDate = dayKey(target);
        if (date != null && !date.equals(nextDate)) {
            if (sheet != null) sheet.dismiss();
            scroll.scrollTo(0, 0);
        }
        date = nextDate;
        SimpleDateFormat longDate = new SimpleDateFormat("EEEE, d 'de' MMMM", PausaUi.SPANISH);
        longDate.setTimeZone(target.getTimeZone());
        title.setText(dayOffset == 0 ? "Hoy" : "Mañana");
        dateLine.setText(PausaUi.capitalize(longDate.format(target.getTime())) + "\nMantén pulsada una tarea para moverla");
        plan = DayPlanStore.read(getContext(), date);
        Map<Long, TaskStore.Task> tasks = new HashMap<>();
        for (TaskStore.Task task : TaskStore.getAll(getContext())) tasks.put(task.id, task);
        int hour = clock.now().get(Calendar.HOUR_OF_DAY);
        int now = dayOffset == 0 ? (hour < 12 ? 0 : hour < 20 ? 1 : 2) : -1;
        board.removeAllViews();
        for (int section = 0; section < 3; section++) {
            final int selected = section;
            boolean current = section == now;
            zones[section] = column();
            zones[section].setBackground(PausaUi.outline(getContext(), PausaUi.SURFACE,
                    current ? (ACCENTS[0] & 0x00FFFFFF) | 0x80000000 : PausaUi.LINE, 24));
            zones[section].setPadding(dp(14), dp(10), dp(8), dp(12));
            LinearLayout header = new LinearLayout(getContext());
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView badge = new TextView(getContext());
            badge.setBackground(PausaUi.surface(getContext(), TINTS[section], 20));
            badge.setGravity(Gravity.CENTER);
            badge.setCompoundDrawables(new PausaUi.Symbol(getContext(), TaskSheets.FRANJA_ICONS[section], ACCENTS[section], 20), null, null, null);
            badge.setPadding(dp(10), 0, 0, 0);
            header.addView(badge, new LayoutParams(dp(40), dp(40)));
            LinearLayout labels = column();
            labels.setPadding(dp(12), 0, 0, 0);
            LinearLayout nameRow = new LinearLayout(getContext());
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            nameRow.addView(PausaUi.editorial(getContext(), DayPlanStore.LABELS[section], 18));
            labels.addView(nameRow);
            int count = 0;
            for (long id : plan.get(section)) if (tasks.containsKey(id)) count++;
            LinearLayout countRow = new LinearLayout(getContext());
            countRow.setGravity(Gravity.CENTER_VERTICAL);
            countRow.setPadding(0, dp(3), 0, 0);
            TextView countLabel = PausaUi.text(getContext(), count == 0 ? "Libre" : count + (count == 1 ? " tarea" : " tareas"),
                    12, PausaUi.MUTED, false);
            countRow.addView(countLabel);
            if (current) {
                TextView pill = PausaUi.eyebrow(getContext(), "Ahora", PausaUi.TERRACOTTA);
                pill.setTextSize(9);
                pill.setBackground(PausaUi.surface(getContext(), PausaUi.SUN_SOFT, 8));
                pill.setPadding(dp(6), dp(3), dp(6), dp(3));
                LayoutParams pp = new LayoutParams(-2, -2);
                pp.leftMargin = dp(8);
                countRow.addView(pill, pp);
            }
            labels.addView(countRow);
            header.addView(labels, new LayoutParams(0, -2, 1));
            ImageButton add = PausaUi.iconButton(getContext(), "plus", "Añadir tareas: " + DayPlanStore.LABELS[section],
                    PausaUi.GREEN, () -> showPicker(selected));
            header.addView(add, new LayoutParams(dp(48), dp(48)));
            zones[section].addView(header, full());
            cards[section] = column();
            cards[section].setMinimumHeight(dp(48));
            cards[section].setPadding(0, dp(4), dp(6), 0);
            for (long id : plan.get(section)) {
                TaskStore.Task task = tasks.get(id);
                if (task != null) cards[section].addView(taskCard(task), cardParams());
            }
            if (cards[section].getChildCount() == 0) {
                TextView empty = PausaUi.text(getContext(), "Añadir tareas", 14, PausaUi.MUTED, false);
                empty.setGravity(Gravity.CENTER_VERTICAL);
                empty.setCompoundDrawables(new PausaUi.Symbol(getContext(), "plus", PausaUi.MUTED, 18), null, null, null);
                empty.setCompoundDrawablePadding(dp(10));
                empty.setPadding(dp(14), 0, dp(14), 0);
                empty.setBackground(PausaUi.dashed(getContext(), 0xFFCFC9BC, 16));
                empty.setOnClickListener(v -> showPicker(selected));
                LayoutParams ep = new LayoutParams(-1, dp(48));
                ep.topMargin = dp(6);
                cards[section].addView(empty, ep);
            }
            zones[section].addView(cards[section], full());
            LayoutParams zoneParams = full();
            zoneParams.bottomMargin = dp(12);
            board.addView(zones[section], zoneParams);
        }
    }

    private Calendar targetDay() {
        Calendar target = (Calendar) clock.now().clone();
        target.add(Calendar.DAY_OF_YEAR, dayOffset);
        return target;
    }

    private String dayKey(Calendar day) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(day.getTimeZone());
        return format.format(day.getTime());
    }

    private View taskCard(TaskStore.Task task) {
        LinearLayout card = new LinearLayout(getContext());
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setTag(task.id);
        card.setMinimumHeight(dp(52));
        card.setPadding(dayOffset == 0 ? dp(2) : dp(16), dp(2), dp(6), dp(2));
        card.setBackground(PausaUi.ripple(getContext(), PausaUi.CREAM, 16));
        TextView text = PausaUi.text(getContext(), task.text, 16, PausaUi.INK, false);
        text.setLineSpacing(0, 1.1f);
        text.setPadding(0, dp(10), dp(8), dp(10));
        if (dayOffset == 0) {
            PausaUi.Check check = new PausaUi.Check(getContext(), PausaUi.SAGE);
            check.setChecked(task.done);
            check.setContentDescription(task.text);
            check.setListener(done -> {
                TaskStore.setDone(getContext(), task.id, done);
                strike(text, done);
            });
            card.addView(check, new LayoutParams(dp(44), dp(44)));
        }
        strike(text, task.done);
        card.addView(text, new LayoutParams(0, -2, 1));
        ImageView grip = new ImageView(getContext());
        grip.setImageDrawable(new PausaUi.Symbol(getContext(), "grip", PausaUi.MUTED, 20));
        grip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(grip, new LayoutParams(dp(24), dp(24)));
        card.setContentDescription(task.text + ". Toca para editar o mantén pulsado para mover.");
        card.setOnClickListener(v -> editTask(task));
        card.setOnLongClickListener(v -> {
            dragged = v;
            draggedId = task.id;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            v.setElevation(dp(8));
            ClipData data = ClipData.newPlainText("task", String.valueOf(task.id));
            boolean started = Build.VERSION.SDK_INT >= 24
                    ? v.startDragAndDrop(data, new DragShadowBuilder(v), this, 0)
                    : v.startDrag(data, new DragShadowBuilder(v), this, 0);
            if (started) v.setVisibility(GONE);
            else { v.setElevation(0); dragged = null; }
            return started;
        });
        return card;
    }

    private void strike(TextView text, boolean done) {
        text.setPaintFlags(done ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        text.setTextColor(done ? PausaUi.MUTED : PausaUi.INK);
    }

    private boolean onBoardDrag(DragEvent event) {
        if (event.getLocalState() != this) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                pointerY = event.getY() - scroll.getScrollY();
                placeGap(event.getY());
                scrollStep = pointerY < dp(64) ? -dp(12)
                        : pointerY > scroll.getHeight() - scroll.getPaddingBottom() - dp(64) ? dp(12) : 0;
                removeCallbacks(autoScroll);
                if (scrollStep != 0) post(autoScroll);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                scrollStep = 0;
                removeCallbacks(autoScroll);
                return true;
            case DragEvent.ACTION_DROP:
                placeGap(event.getY());
                for (List<Long> section : plan) section.remove(draggedId);
                plan.get(dropSection).add(Math.min(dropIndex, plan.get(dropSection).size()), draggedId);
                DayPlanStore.save(getContext(), date, plan);
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                removeCallbacks(autoScroll);
                scrollStep = 0;
                detachGap();
                if (dragged != null) { dragged.setVisibility(VISIBLE); dragged.setElevation(0); }
                dragged = null;
                refresh();
                return true;
            default: return true;
        }
    }

    private void placeGap(float y) {
        int section = 2;
        for (int i = 0; i < 3; i++) {
            if (y < zones[i].getBottom()) { section = i; break; }
        }
        LinearLayout target = cards[section];
        float localY = y - zones[section].getTop() - target.getTop();
        int index = 0;
        for (int i = 0; i < target.getChildCount(); i++) {
            View child = target.getChildAt(i);
            if (child == gap) {
                // Keep the current target stable while the pointer is inside its placeholder.
                if (localY >= child.getTop() && localY <= child.getBottom()) return;
                continue;
            }
            if (!(child.getTag() instanceof Long) || child == dragged) continue;
            if (localY > (child.getTop() + child.getBottom()) / 2f) index++;
        }
        dropSection = section;
        dropIndex = index;
        detachGap();
        int insertion = target.getChildCount();
        int count = 0;
        for (int i = 0; i < target.getChildCount(); i++) {
            View child = target.getChildAt(i);
            if (child.getTag() instanceof Long && child != dragged && count++ == index) {
                insertion = i; break;
            }
        }
        LayoutParams params = new LayoutParams(-1, dragged == null ? dp(56) : dragged.getHeight());
        params.topMargin = dp(6);
        target.addView(gap, insertion, params);
    }

    private void detachGap() {
        if (gap.getParent() != null) ((ViewGroup) gap.getParent()).removeView(gap);
    }

    void openPicker(int section) { showPicker(section); }

    private void showPicker(int section) {
        final String targetDate = date;
        PausaUi.Sheet s = new PausaUi.Sheet(getContext(), DayPlanStore.LABELS[section]).tall();
        Dialog dialog = s.dialog;
        sheet = dialog;
        SimpleDateFormat longDate = new SimpleDateFormat("EEEE d 'de' MMMM", PausaUi.SPANISH);
        s.subtitle((dayOffset == 0 ? "Hoy, " : "Mañana, ") + longDate.format(targetDay().getTime()));
        s.trailing(PausaUi.quiet(getContext(), "Listo", PausaUi.GREEN, dialog::dismiss));
        LinearLayout inputRow = new LinearLayout(getContext());
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(getContext());
        PausaUi.input(input);
        input.setHint("Escribe una tarea…");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        inputRow.addView(input, new LayoutParams(0, -2, 1));
        TextView feedback = PausaUi.text(getContext(), "Toca tareas para añadirlas al instante", 12, PausaUi.MUTED, false);
        feedback.setPadding(dp(4), dp(10), 0, dp(4));
        Runnable addText = () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            long id = TaskStore.add(getContext(), value);
            addToDay(targetDate, section, id);
            input.setText("");
            input.requestFocus();
            feedback.setText("Añadida. Puedes escribir otra");
            feedback.setTextColor(PausaUi.SAGE);
            PausaUi.pop(feedback);
            feedback.announceForAccessibility("Tarea añadida");
        };
        Button addButton = PausaUi.action(getContext(), "Añadir", true, addText);
        LayoutParams ap = new LayoutParams(-2, dp(52));
        ap.leftMargin = dp(8);
        inputRow.addView(addButton, ap);
        input.setOnEditorActionListener((v, action, event) -> {
            if (PausaUi.isSubmit(action, event)) { addText.run(); return true; }
            return false;
        });
        s.body.addView(inputRow, full());
        s.body.addView(feedback, full());
        ScrollView choicesScroll = new ScrollView(getContext());
        choicesScroll.setVerticalScrollBarEnabled(false);
        LinearLayout choices = column();
        Set<Long> todayIds = new HashSet<>();
        for (List<Long> ids : DayPlanStore.read(getContext(), DayPlanStore.day(0))) todayIds.addAll(ids);
        List<List<Long>> existing = DayPlanStore.read(getContext(), targetDate);
        List<TaskStore.Task> all = TaskStore.getAll(getContext());
        int count = 0;
        for (int group = 0; group < 2; group++) {
            boolean headingAdded = false;
            for (TaskStore.Task task : all) {
                boolean fromToday = todayIds.contains(task.id);
                if ((group == 0) != fromToday || DayPlanStore.contains(existing, task.id)
                        || (!fromToday && task.done)) continue;
                if (!headingAdded) {
                    TextView label = PausaUi.eyebrow(getContext(), group == 0 ? "De hoy" : "De tu lista", PausaUi.MUTED);
                    label.setPadding(dp(4), dp(18), 0, dp(6));
                    choices.addView(label);
                    headingAdded = true;
                }
                count++;
                TextView choice = PausaUi.text(getContext(), task.text, 16, PausaUi.INK, false);
                choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setMinHeight(dp(52));
                choice.setPadding(dp(8), dp(8), dp(12), dp(8));
                choice.setCompoundDrawables(new PausaUi.Symbol(getContext(), "plus", PausaUi.GREEN, 20), null, null, null);
                choice.setCompoundDrawablePadding(dp(14));
                choice.setBackground(PausaUi.ripple(getContext(), android.graphics.Color.TRANSPARENT, 14));
                choice.setContentDescription("Añadir " + task.text);
                choice.setOnClickListener(v -> {
                    if (!choice.isEnabled()) return;
                    // A completed task repeated tomorrow gets a fresh pending copy.
                    long id = task.done ? TaskStore.add(getContext(), task.text) : task.id;
                    addToDay(targetDate, section, id);
                    choice.setEnabled(false);
                    choice.setCompoundDrawables(new PausaUi.Symbol(getContext(), "check", PausaUi.SAGE, 20), null, null, null);
                    choice.setTextColor(PausaUi.SAGE);
                    PausaUi.pop(choice);
                    feedback.setText("Añadida. Puedes seleccionar más");
                    feedback.setTextColor(PausaUi.SAGE);
                });
                choices.addView(choice, full());
            }
        }
        if (count == 0) {
            TextView none = PausaUi.text(getContext(), "Todo listo. Puedes crear una tarea arriba.", 14, PausaUi.MUTED, false);
            none.setPadding(dp(4), dp(18), 0, 0);
            choices.addView(none);
        }
        choicesScroll.addView(choices);
        s.body.addView(choicesScroll, new LayoutParams(-1, 0, 1));
        dialog.setOnDismissListener(ignored -> { if (sheet == dialog) sheet = null; });
        s.show();
    }

    private void addToDay(String targetDate, int section, long id) {
        List<List<Long>> current = DayPlanStore.read(getContext(), targetDate);
        if (!DayPlanStore.contains(current, id)) {
            current.get(section).add(id);
            DayPlanStore.save(getContext(), targetDate, current);
        }
        refresh();
    }

    private void editTask(TaskStore.Task task) {
        final String targetDate = date;
        PausaUi.Sheet s = new PausaUi.Sheet(getContext(), "Editar tarea");
        EditText input = new EditText(getContext());
        PausaUi.input(input);
        input.setText(task.text);
        input.setSingleLine(true);
        input.setSelection(input.length());
        s.add(input, 18);
        // Chips are the keyboard and accessibility equivalent of dragging between franjas.
        int original = 0;
        for (int i = 0; i < 3; i++) if (plan.get(i).contains(task.id)) original = i;
        int[] destination = {original};
        s.add(PausaUi.eyebrow(getContext(), "Franja", PausaUi.MUTED), 8);
        LinearLayout franjas = new LinearLayout(getContext());
        TextView[] chips = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            chips[i] = PausaUi.chip(getContext(), TaskSheets.FRANJAS[i], i == original, () -> {
                destination[0] = index;
                for (int j = 0; j < 3; j++) PausaUi.setChip(chips[j], j == index);
            });
            chips[i].setContentDescription("Franja: " + DayPlanStore.LABELS[i]);
            LayoutParams p = new LayoutParams(0, -2, 1);
            if (i > 0) p.leftMargin = dp(8);
            franjas.addView(chips[i], p);
        }
        s.add(franjas, 6);
        Dialog dialog = s.dialog;
        sheet = dialog;
        dialog.setOnDismissListener(d -> { if (sheet == dialog) sheet = null; });
        Button save = PausaUi.action(getContext(), "Guardar", true, () -> {
            if (input.getText().toString().trim().isEmpty()) { input.setError("Escribe una tarea"); return; }
            TaskStore.setText(getContext(), task.id, input.getText().toString());
            List<List<Long>> current = DayPlanStore.read(getContext(), targetDate);
            if (!current.get(destination[0]).contains(task.id)) {
                for (List<Long> ids : current) ids.remove(task.id);
                current.get(destination[0]).add(task.id);
                DayPlanStore.save(getContext(), targetDate, current);
            }
            dialog.dismiss();
            refresh();
        });
        s.footer(PausaUi.quiet(getContext(), "Quitar del plan", PausaUi.TERRACOTTA, () -> {
            dialog.dismiss();
            removeTask(targetDate, task.id);
        }), save);
        s.show();
    }

    private void removeTask(String targetDate, long id) {
        List<List<Long>> current = DayPlanStore.read(getContext(), targetDate);
        for (int i = 0; i < 3; i++) {
            int position = current.get(i).indexOf(id);
            if (position < 0) continue;
            final int section = i;
            current.get(i).remove(position);
            DayPlanStore.save(getContext(), targetDate, current);
            PausaUi.snack(getContext(), "Quitada del plan", "Deshacer", () -> {
                List<List<Long>> latest = DayPlanStore.read(getContext(), targetDate);
                if (!DayPlanStore.contains(latest, id)) {
                    latest.get(section).add(Math.min(position, latest.get(section).size()), id);
                    DayPlanStore.save(getContext(), targetDate, latest);
                }
                refresh();
            });
            break;
        }
        refresh();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (board == null) return;
        removeCallbacks(clockTick);
        if (isShown()) { refresh(); postDelayed(clockTick, 1000); }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(clockTick);
        postDelayed(clockTick, 1000);
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(clockTick);
        removeCallbacks(autoScroll);
        if (sheet != null) sheet.dismiss();
        super.onDetachedFromWindow();
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext()); layout.setOrientation(VERTICAL); return layout;
    }
    private LayoutParams full() { return new LayoutParams(-1, -2); }
    private LayoutParams cardParams() { LayoutParams p = full(); p.topMargin = dp(6); return p; }
    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
