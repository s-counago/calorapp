package com.sejio.calorapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
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
 * The Kanban that replaced the Notion stack: stack infinito, prioridad, baúl and fracasos. On a phone
 * one column shows at a time and chips switch between them. Completing a card adds it to the running
 * total of tasks done instead of keeping a "Hechas" column. Planning a card puts it on a day plan too.
 */
final class KanbanView extends LinearLayout {
    /** Long columns (the stack has hundreds of cards) render in pages to stay smooth. */
    private static final int PAGE = 50;

    private static int column = 1; // Prioridad first: it is what matters today.
    private final TextView doneCount;
    private final LinearLayout chips;
    private final EditText input;
    private final LinearLayout cards;
    private final ScrollView scroll;
    private int shown = PAGE;
    private int lastDone = -1;

    KanbanView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(20), dp(10), dp(20), 0);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.BOTTOM);
        header.addView(PausaUi.editorial(context, "Tablero", 28), new LayoutParams(0, -2, 1));
        LinearLayout done = new LinearLayout(context);
        done.setOrientation(VERTICAL);
        done.setGravity(Gravity.END);
        doneCount = PausaUi.editorial(context, "", 26);
        doneCount.setTextColor(PausaUi.TERRACOTTA);
        doneCount.setGravity(Gravity.END);
        done.addView(doneCount);
        done.addView(PausaUi.eyebrow(context, "Hechas", PausaUi.MUTED));
        done.setContentDescription("Tareas hechas");
        header.addView(done, new LayoutParams(-2, -2));
        addView(header, full());

        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chips = new LinearLayout(context);
        chipScroll.addView(chips);
        LayoutParams chipParams = full();
        chipParams.topMargin = dp(14);
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
            if (PausaUi.isSubmit(actionId, event)) { addCard(); return true; }
            return false;
        });
        composer.addView(input, new LayoutParams(0, -2, 1));
        composer.addView(PausaUi.iconButton(context, "arrow", "Añadir al tablero", PausaUi.GREEN, this::addCard),
                new LayoutParams(dp(44), dp(44)));
        LayoutParams composerParams = full();
        composerParams.topMargin = dp(12);
        addView(composer, composerParams);

        scroll = new PausaUi.Scroll(context);
        scroll.setTag(PausaUi.SCROLL_TAG);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        cards = new LinearLayout(context);
        cards.setOrientation(VERTICAL);
        cards.setPadding(0, dp(12), 0, 0);
        scroll.addView(cards, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1));
        refresh();
    }

    void focusComposer() {
        scroll.smoothScrollTo(0, 0);
        input.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void addCard() {
        String text = input.getText().toString();
        if (text.trim().isEmpty()) return;
        TaskStore.add(getContext(), text, TaskStore.BOARD[column]);
        input.setText("");
        refresh();
    }

    private void select(int index) {
        if (index == column) return;
        column = index;
        shown = PAGE;
        scroll.scrollTo(0, 0);
        refresh();
        PausaUi.stagger(cards, 8);
    }

    void refresh() {
        Context c = getContext();
        int done = TaskStore.doneTotal(c);
        if (lastDone >= 0 && lastDone != done) PausaUi.countTo(doneCount, lastDone, done, "");
        else doneCount.setText(PausaUi.number(done));
        doneCount.setContentDescription(done + " tareas hechas");
        lastDone = done;

        Map<String, Integer> counts = TaskStore.boardCounts(c);
        chips.removeAllViews();
        for (int i = 0; i < TaskStore.BOARD.length; i++) {
            final int index = i;
            int count = counts.get(TaskStore.BOARD[i]);
            TextView chip = PausaUi.chip(c, TaskStore.BOARD_LABELS[i] + "  " + PausaUi.number(count), i == column, () -> select(index));
            chip.setContentDescription(TaskStore.BOARD_LABELS[i] + ", " + count);
            LayoutParams params = new LayoutParams(-2, -2);
            if (i > 0) params.leftMargin = dp(8);
            chips.addView(chip, params);
        }
        input.setHint("Añadir a " + TaskStore.BOARD_LABELS[column].toLowerCase(PausaUi.SPANISH));

        cards.removeAllViews();
        List<TaskStore.Task> tasks = TaskStore.board(c, TaskStore.BOARD[column]);
        if (tasks.isEmpty()) {
            TextView empty = PausaUi.text(c, "Nada por aquí.", 14, PausaUi.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            cards.addView(empty, full());
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
        cards.addView(card, full());
        if (visible < tasks.size()) {
            int rest = tasks.size() - visible;
            TextView more = PausaUi.quiet(c, "Mostrar " + Math.min(PAGE, rest) + " más · quedan " + PausaUi.number(rest),
                    PausaUi.GREEN, () -> { shown += PAGE; refresh(); });
            LayoutParams params = full();
            params.topMargin = dp(8);
            cards.addView(more, params);
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
        TextView title = PausaUi.text(c, task.text, 16, PausaUi.INK, false);
        title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.1f);
        title.setPadding(dp(4), 0, 0, 0);
        front.addView(title, new LayoutParams(0, -2, 1));

        check.setListener(done -> {
            if (!done) return;
            TaskStore.setDone(c, task.id, true);
            postDelayed(this::refresh, PausaUi.motion(c) ? 420 : 0);
            PausaUi.snack(c, "Hecha. Llevas " + PausaUi.number(TaskStore.doneTotal(c)) + ".", "Deshacer", () -> {
                TaskStore.setDone(c, task.id, false);
                refresh();
            });
        });
        front.setContentDescription(task.text + ". Toca para moverla o editarla.");
        front.setOnClickListener(v -> actions(task));

        PausaUi.SwipeRow swipe = new PausaUi.SwipeRow(c, front);
        swipe.setListener(() -> TaskSheets.deleteWithUndo(c, task, this::refresh));
        return swipe;
    }

    /** Move a card to another column, edit it (and plan it on a day), or delete it. */
    private void actions(TaskStore.Task task) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Tarjeta");
        TextView text = PausaUi.text(c, task.text, 15, PausaUi.INK, false);
        text.setMaxLines(8);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setLineSpacing(0, 1.12f);
        sheet.add(text, 16);
        sheet.add(PausaUi.eyebrow(c, "Mover a", PausaUi.MUTED), 8);
        LinearLayout moves = new LinearLayout(c);
        moves.setOrientation(VERTICAL);
        for (int i = 0; i < TaskStore.BOARD.length; i++) {
            if (TaskStore.BOARD[i].equals(task.board)) continue;
            final int index = i;
            TextView chip = PausaUi.chip(c, TaskStore.BOARD_LABELS[i], false, () -> {
                sheet.dismiss();
                TaskStore.setBoard(c, task.id, TaskStore.BOARD[index]);
                refresh();
                PausaUi.snack(c, "Movida a " + TaskStore.BOARD_LABELS[index].toLowerCase(PausaUi.SPANISH) + ".", "Deshacer", () -> {
                    TaskStore.setBoard(c, task.id, task.board);
                    refresh();
                });
            });
            chip.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LayoutParams params = full();
            params.bottomMargin = dp(8);
            moves.addView(chip, params);
        }
        sheet.add(moves, 10);
        sheet.footer(
                PausaUi.quiet(c, "Borrar", PausaUi.TERRACOTTA, () -> { sheet.dismiss(); TaskSheets.deleteWithUndo(c, task, this::refresh); }),
                PausaUi.action(c, "Editar o planificar", true, () -> { sheet.dismiss(); TaskSheets.edit(c, task, this::refresh); }));
        sheet.show();
    }

    private LayoutParams full() { return new LayoutParams(-1, -2); }
    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
