package com.sejio.calorapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every task in one calm list: today first, then pending, planned and a folded archive of done ones. */
final class TaskListView extends LinearLayout {
    static final String ACTION_NOTION_REVIEW_RESET = "com.sejio.calorapp.ACTION_NOTION_REVIEW_RESET";

    private final EditText taskInput;
    private final ImageButton sendButton;
    private final LinearLayout taskRows;
    private final TextView summary;
    private final ScrollView taskScroll;
    private static boolean completedOpen;
    private long lastAdded = -1;
    private String displayedDay;
    private final Runnable dayBoundary = new Runnable() {
        @Override public void run() {
            if (!DayPlanStore.day(0).equals(displayedDay)) refresh();
            postDelayed(this, 1000);
        }
    };

    TaskListView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(20), dp(10), dp(20), 0);

        addView(PausaUi.editorial(context, "Tu lista", 28), full());
        summary = PausaUi.text(context, "", 13, PausaUi.MUTED, false);
        summary.setPadding(0, dp(6), 0, 0);
        addView(summary, full());

        LinearLayout composer = new LinearLayout(context);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setBackground(PausaUi.outline(context, PausaUi.SURFACE, PausaUi.LINE, 22));
        composer.setPadding(dp(6), dp(4), dp(6), dp(4));
        taskInput = new EditText(context);
        PausaUi.input(taskInput);
        taskInput.setBackground(null);
        taskInput.setHint(R.string.new_task_hint);
        taskInput.setSingleLine(true);
        taskInput.setTextSize(17);
        taskInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        taskInput.setCompoundDrawables(new PausaUi.Symbol(context, "plus", PausaUi.MUTED, 20), null, null, null);
        taskInput.setCompoundDrawablePadding(dp(12));
        taskInput.setPadding(dp(12), dp(10), dp(8), dp(10));
        taskInput.setOnEditorActionListener((view, actionId, event) -> {
            if (PausaUi.isSubmit(actionId, event)) { addTask(); return true; }
            return false;
        });
        composer.addView(taskInput, new LayoutParams(0, -2, 1));
        sendButton = PausaUi.iconButton(context, "arrow", "Añadir tarea", PausaUi.SURFACE, this::addTask);
        sendButton.setBackground(PausaUi.ripple(context, PausaUi.GREEN, 22));
        sendButton.setScaleX(0); sendButton.setScaleY(0);
        sendButton.setVisibility(INVISIBLE);
        composer.addView(sendButton, new LayoutParams(dp(44), dp(44)));
        taskInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { showSend(s.toString().trim().length() > 0); }
        });
        LayoutParams composerParams = full();
        composerParams.topMargin = dp(14);
        addView(composer, composerParams);

        taskScroll = new PausaUi.Scroll(context);
        taskScroll.setTag(PausaUi.SCROLL_TAG);
        taskScroll.setFillViewport(true);
        taskScroll.setVerticalScrollBarEnabled(false);
        taskScroll.setClipToPadding(false);
        taskRows = new LinearLayout(context);
        taskRows.setOrientation(VERTICAL);
        taskRows.setPadding(0, dp(6), 0, 0);
        taskScroll.addView(taskRows, new ScrollView.LayoutParams(-1, -2));
        addView(taskScroll, new LayoutParams(-1, 0, 1));
        refresh();
    }

    private void showSend(boolean show) {
        boolean shown = sendButton.getVisibility() == VISIBLE && sendButton.getScaleX() > .5f;
        if (show == shown) return;
        sendButton.animate().cancel();
        if (show) sendButton.setVisibility(VISIBLE);
        if (!PausaUi.motion(getContext())) {
            sendButton.setScaleX(show ? 1 : 0); sendButton.setScaleY(show ? 1 : 0);
            if (!show) sendButton.setVisibility(INVISIBLE);
            return;
        }
        sendButton.animate().scaleX(show ? 1 : 0).scaleY(show ? 1 : 0).setDuration(show ? 320 : 160)
                .setInterpolator(show ? PausaUi.SPRING : PausaUi.EASE)
                .withEndAction(() -> { if (!show) sendButton.setVisibility(INVISIBLE); }).start();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(dayBoundary, 1000);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(dayBoundary);
        super.onDetachedFromWindow();
    }

    void focusComposer() {
        taskScroll.smoothScrollTo(0, 0);
        taskInput.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(taskInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void addTask() {
        String text = taskInput.getText().toString();
        if (text.trim().isEmpty()) return;
        lastAdded = TaskStore.add(getContext(), text);
        taskInput.setText("");
        refresh();
    }

    void refresh() {
        displayedDay = DayPlanStore.day(0);
        taskRows.removeAllViews();
        List<TaskStore.Task> tasks = TaskStore.getAll(getContext());
        Map<Long, TaskStore.Task> byId = new HashMap<>();
        for (TaskStore.Task task : tasks) byId.put(task.id, task);
        Map<Long, String> planned = plannedLabels();
        List<List<Long>> today = DayPlanStore.read(getContext(), DayPlanStore.day(0));

        List<View> todayRows = new ArrayList<>();
        for (int section = 0; section < 3; section++) {
            for (long id : today.get(section)) {
                TaskStore.Task task = byId.get(id);
                if (task != null) todayRows.add(row(task, "Hoy · " + DayPlanStore.LABELS[section].toLowerCase(PausaUi.SPANISH),
                        TaskSheets.FRANJA_ICONS[section]));
            }
        }
        List<View> pending = new ArrayList<>(), scheduled = new ArrayList<>(), completed = new ArrayList<>();
        int pendingCount = 0, doneCount = 0;
        for (TaskStore.Task task : tasks) {
            if (task.done) doneCount++; else pendingCount++;
            if (DayPlanStore.contains(today, task.id)) continue;
            if (task.done) completed.add(row(task, null, null));
            else if (planned.containsKey(task.id)) scheduled.add(row(task, planned.get(task.id), "calendar"));
            else pending.add(row(task, null, null));
        }
        summary.setText(tasks.isEmpty() ? "" : pendingCount + (pendingCount == 1 ? " pendiente" : " pendientes")
                + (doneCount > 0 ? " · " + doneCount + (doneCount == 1 ? " hecha" : " hechas") : ""));

        group("Hoy", todayRows, null);
        group("Pendientes", pending, null);
        group("Planificadas", scheduled, null);
        if (!completed.isEmpty()) group("Hechas", completed, () -> { completedOpen = !completedOpen; refresh(); });
        if (tasks.isEmpty()) {
            TextView empty = PausaUi.editorial(getContext(), "Un poco de espacio mental.", 20);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(56), dp(20), dp(8));
            empty.setCompoundDrawables(null, new PausaUi.Symbol(getContext(), "sunrise", PausaUi.SUN, 40), null, null);
            empty.setCompoundDrawablePadding(dp(18));
            taskRows.addView(empty, full());
            TextView hint = PausaUi.text(getContext(), "Escribe arriba tu primera tarea.", 14, PausaUi.MUTED, false);
            hint.setGravity(Gravity.CENTER);
            taskRows.addView(hint, full());
        }
    }

    private void group(String label, List<View> rows, Runnable toggle) {
        if (rows.isEmpty()) return;
        boolean collapsible = toggle != null;
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(18), dp(4), dp(8));
        TextView name = PausaUi.eyebrow(getContext(), label, PausaUi.MUTED);
        header.addView(name, new LayoutParams(-2, -2));
        TextView count = PausaUi.text(getContext(), "  " + rows.size(), 12, 0x99626960, true);
        header.addView(count, new LayoutParams(0, -2, 1));
        if (collapsible) {
            ImageView chevron = new ImageView(getContext());
            chevron.setImageDrawable(new PausaUi.Symbol(getContext(), "down", PausaUi.MUTED, 18));
            chevron.setRotation(completedOpen ? 180 : 0);
            header.addView(chevron, new LayoutParams(dp(18), dp(18)));
            header.setMinimumHeight(dp(48));
            header.setBackground(PausaUi.ripple(getContext(), android.graphics.Color.TRANSPARENT, 14));
            header.setContentDescription((completedOpen ? "Ocultar " : "Mostrar ") + "tareas hechas, " + rows.size());
            header.setOnClickListener(v -> {
                chevron.animate().rotation(completedOpen ? 0 : 180).setDuration(240).setInterpolator(PausaUi.EASE).start();
                postDelayed(toggle, PausaUi.motion(getContext()) ? 120 : 0);
            });
        }
        taskRows.addView(header, full());
        if (collapsible && !completedOpen) return;
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setBackground(PausaUi.card(getContext()));
        card.setClipToOutline(true);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                View line = PausaUi.hairline(getContext());
                LayoutParams lp = new LayoutParams(-1, dp(1));
                lp.leftMargin = dp(56);
                card.addView(line, lp);
            }
            card.addView(rows.get(i), full());
        }
        taskRows.addView(card, full());
        if (collapsible) PausaUi.rise(card, 0);
    }

    private View row(TaskStore.Task task, String meta, String metaIcon) {
        Context c = getContext();
        LinearLayout front = new LinearLayout(c);
        front.setGravity(Gravity.CENTER_VERTICAL);
        front.setMinimumHeight(dp(60));
        front.setPadding(dp(4), dp(6), dp(16), dp(6));
        front.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14214E3B), new ColorDrawable(PausaUi.SURFACE), null));

        PausaUi.Check check = new PausaUi.Check(c, PausaUi.SAGE);
        check.setChecked(task.done);
        check.setContentDescription(task.text);
        front.addView(check, new LayoutParams(dp(48), dp(48)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        texts.setPadding(dp(4), 0, 0, 0);
        TextView title = PausaUi.text(c, task.text, 16, PausaUi.INK, false);
        title.setMaxLines(3);
        title.setLineSpacing(0, 1.1f);
        setCompletedAppearance(title, task.done);
        texts.addView(title, full());
        if (meta != null && !task.done) {
            TextView detail = PausaUi.text(c, meta, 12, PausaUi.MUTED, false);
            if (metaIcon != null) {
                detail.setCompoundDrawables(new PausaUi.Symbol(c, metaIcon, PausaUi.MUTED, 14), null, null, null);
                detail.setCompoundDrawablePadding(dp(5));
            }
            LayoutParams detailParams = full();
            detailParams.topMargin = dp(4);
            texts.addView(detail, detailParams);
        }
        front.addView(texts, new LayoutParams(0, -2, 1));

        check.setListener(done -> {
            setCompletedAppearance(title, done);
            TaskStore.setDone(c, task.id, done);
            postDelayed(this::refresh, PausaUi.motion(c) ? 480 : 0);
        });
        front.setContentDescription(task.text + (task.done ? ", hecha" : "") + ". Toca para editar.");
        front.setOnClickListener(v -> TaskSheets.edit(c, task, this::refresh));

        PausaUi.SwipeRow swipe = new PausaUi.SwipeRow(c, front);
        swipe.setListener(() -> TaskSheets.deleteWithUndo(c, task, this::refresh));
        if (task.id == lastAdded) {
            lastAdded = -1;
            PausaUi.rise(swipe, 0);
        }
        return swipe;
    }

    private Map<Long, String> plannedLabels() {
        Map<Long, String> labels = new HashMap<>();
        List<List<Long>> tomorrow = DayPlanStore.read(getContext(), DayPlanStore.day(1));
        for (int section = 0; section < 3; section++) {
            for (long id : tomorrow.get(section)) {
                labels.put(id, "Mañana · " + DayPlanStore.LABELS[section].toLowerCase(PausaUi.SPANISH));
            }
        }
        SimpleDateFormat format = new SimpleDateFormat("EEE d · HH:mm", PausaUi.SPANISH);
        Map<Long, Long> firstSlots = new HashMap<>();
        for (Map.Entry<Long, Long> entry : PlannerStore.getAssignments(getContext(), PlannerStore.currentSlotStart()).entrySet()) {
            Long first = firstSlots.get(entry.getValue());
            if (first == null || entry.getKey() < first) firstSlots.put(entry.getValue(), entry.getKey());
        }
        for (Map.Entry<Long, Long> entry : firstSlots.entrySet()) {
            String existing = labels.get(entry.getKey());
            labels.put(entry.getKey(), (existing == null ? "" : existing + " · ") + format.format(new Date(entry.getValue())));
        }
        return labels;
    }

    private void setCompletedAppearance(TextView view, boolean done) {
        view.setPaintFlags(done
                ? view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : view.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        view.setTextColor(done ? PausaUi.MUTED : PausaUi.INK);
    }

    private LayoutParams full() { return new LayoutParams(-1, -2); }
    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
