package com.sejio.calorapp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dinero: the payroll-to-payroll budget built from the banks already saved on the phone.
 * Mes shows what is fixed, what is free and how the days are going; Movimientos lets the owner
 * teach the app; Bancos keeps the existing connections. Reading never contacts a bank.
 */
final class BudgetView extends LinearLayout {
    interface Source {
        JSONArray movements(Context context) throws Exception;
        long lastSync(Context context);
    }

    /** Replaced by instrumentation tests with a fixture; production reads the encrypted ledger. */
    static Source source = new Source() {
        @Override public JSONArray movements(Context context) throws Exception { return BankingDatabase.get(context).movements(); }
        @Override public long lastSync(Context context) { return BankingDatabase.get(context).lastSync(); }
    };
    static int todayOverride = Integer.MIN_VALUE;

    private static final String[] FILTERS = {"Todo", "Libre", "Fijo", "Entradas", "No cuenta"};

    private final PausaUi.Segmented tabs;
    private final View[] pages;
    private final LinearLayout month, moves;
    private final BankingView banking;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<Ledger.Txn> txns;
    private Budget.Snapshot snapshot;
    private long lastSync, generation;
    private int page = -1, index = -1, filter;
    private boolean failed, animateNext = true;
    private long shownAvailable = Long.MIN_VALUE;

    BudgetView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context);
        header.setPadding(dp(20), dp(10), dp(20), dp(6));
        tabs = new PausaUi.Segmented(context, new String[]{"Mes", "Movimientos", "Bancos"}, this::show);
        header.addView(tabs, new LayoutParams(-1, -2));
        addView(header, new LayoutParams(-1, -2));
        FrameLayout frame = new FrameLayout(context);
        addView(frame, new LayoutParams(-1, 0, 1));
        PausaUi.Scroll monthScroll = new PausaUi.Scroll(context);
        month = column();
        month.setPadding(dp(20), dp(6), dp(20), 0);
        monthScroll.addView(month, new FrameLayout.LayoutParams(-1, -2));
        PausaUi.Scroll movesScroll = new PausaUi.Scroll(context);
        moves = column();
        moves.setPadding(dp(20), dp(6), dp(20), 0);
        movesScroll.addView(moves, new FrameLayout.LayoutParams(-1, -2));
        banking = new BankingView(context);
        pages = new View[]{monthScroll, movesScroll, banking};
        for (View view : pages) { view.setVisibility(GONE); frame.addView(view, new FrameLayout.LayoutParams(-1, -1)); }
        show(0);
    }

    // ------------------------------------------------------------ lifecycle

    void refresh() {
        if (page == 2) banking.refresh();
        load();
    }

    private void show(int next) {
        if (next == page) return;
        int previous = page;
        page = next;
        tabs.select(next, previous >= 0);
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == next ? VISIBLE : GONE);
        if (next == 2) banking.refresh();
        if (previous >= 0 && PausaUi.motion(getContext())) {
            View incoming = pages[next];
            incoming.setTranslationX(dp(next > previous ? 28 : -28)); incoming.setAlpha(0);
            incoming.animate().translationX(0).alpha(1).setDuration(300).setInterpolator(PausaUi.EASE).start();
        }
    }

    private void load() {
        long version = ++generation;
        Context app = getContext().getApplicationContext();
        if (txns == null) loading();
        worker.execute(() -> {
            List<Ledger.Txn> loaded = null;
            long synced = 0;
            try {
                loaded = Ledger.fromDatabase(source.movements(app), TimeZone.getDefault());
                synced = source.lastSync(app);
            } catch (Exception error) {
                // Shown below; nothing is replaced or deleted.
            }
            final List<Ledger.Txn> result = loaded;
            final long sync = synced;
            post(() -> {
                if (version != generation) return;
                failed = result == null;
                if (result != null) { txns = result; lastSync = sync; }
                recompute(true);
            });
        });
    }

    private void recompute(boolean fresh) {
        if (txns == null) { render(); return; }
        int today = today();
        boolean wasLast = snapshot == null || index == snapshot.last();
        snapshot = Budget.snapshot(txns, BudgetStore.load(getContext()), today);
        if (index < snapshot.first() || index > snapshot.last() || (fresh && wasLast)) index = snapshot.last();
        render();
    }

    static int today() {
        if (todayOverride != Integer.MIN_VALUE) return todayOverride;
        Calendar now = Calendar.getInstance();
        return Ledger.epochDay(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
    }

    private void render() {
        renderMonth();
        renderMoves();
        animateNext = false;
    }

    private void loading() {
        month.removeAllViews(); moves.removeAllViews();
        TextView text = PausaUi.text(getContext(), "Leyendo tus movimientos guardados…", 14, PausaUi.MUTED, false);
        text.setPadding(0, dp(24), 0, 0);
        month.addView(text, full());
    }

    // ------------------------------------------------------------ Mes

    private void renderMonth() {
        Context c = getContext();
        month.removeAllViews();
        if (txns == null || txns.isEmpty() || snapshot == null || snapshot.periods.isEmpty()) { empty(month); return; }
        Budget.Cycle cycle = Budget.cycle(snapshot, index);

        month.addView(cycleHeader(cycle), spaced(0, 2));
        TextView title = PausaUi.editorial(c, "Dinero", 34);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        month.addView(title, spaced(4, 4));
        String subtitle = cycle.current
                ? "Día " + cycle.elapsed() + " de " + cycle.period.days() + " · próxima nómina hacia el " + Budget.date(cycle.period.end)
                : "Ciclo cerrado · " + cycle.period.days() + " días";
        month.addView(PausaUi.text(c, subtitle, 13, PausaUi.MUTED, false), spaced(0, 18));

        month.addView(hero(cycle), spaced(0, 14));
        if (cycle.current) for (Budget.Suggestion suggestion : firstTwo(Budget.suggestions(snapshot))) month.addView(suggestion(suggestion), spaced(0, 10));
        fixed(cycle);
        everyday(cycle);
        footer();
        if (animateNext) PausaUi.stagger(month, 8);
    }

    private static List<Budget.Suggestion> firstTwo(List<Budget.Suggestion> all) { return all.subList(0, Math.min(2, all.size())); }

    private View cycleHeader(Budget.Cycle cycle) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean back = index > snapshot.first(), forward = index < snapshot.last();
        View previous = PausaUi.iconButton(c, "back", "Ciclo anterior", back ? PausaUi.INK : PausaUi.LINE, () -> { if (index > snapshot.first()) move(-1); });
        previous.setEnabled(back);
        row.addView(previous, new LayoutParams(dp(44), dp(44)));
        TextView label = PausaUi.eyebrow(c, (cycle.current ? "Este ciclo · " : "Ciclo · ") + Budget.date(cycle.period.start) + " – " + Budget.date(cycle.period.end - 1), PausaUi.MUTED);
        label.setGravity(Gravity.CENTER);
        row.addView(label, new LayoutParams(0, -2, 1));
        View next = PausaUi.iconButton(c, "chevron", "Ciclo siguiente", forward ? PausaUi.INK : PausaUi.LINE, () -> { if (index < snapshot.last()) move(1); });
        next.setEnabled(forward);
        row.addView(next, new LayoutParams(dp(44), dp(44)));
        return row;
    }

    private void move(int delta) {
        index += delta;
        animateNext = true;
        shownAvailable = Long.MIN_VALUE;
        render();
        View scroll = pages[0];
        scroll.scrollTo(0, 0);
        if (PausaUi.motion(getContext())) {
            month.setTranslationX(dp(delta > 0 ? 32 : -32)); month.setAlpha(0);
            month.animate().translationX(0).alpha(1).setDuration(320).setInterpolator(PausaUi.EASE).start();
        }
    }

    /** The night card: free money, the payroll capsule and the pace. */
    private View hero(Budget.Cycle cycle) {
        Context c = getContext();
        LinearLayout hero = column();
        hero.setBackground(PausaUi.surface(c, PausaUi.NIGHT, 28));
        hero.setPadding(dp(22), dp(20), dp(22), dp(20));
        long available = cycle.available();
        boolean over = available < 0;
        String eyebrow = cycle.current ? (over ? "Por encima de lo libre" : "Libre para vivir") : over ? "Te pasaste" : "Te sobró";
        hero.addView(PausaUi.eyebrow(c, eyebrow, PausaUi.ON_NIGHT_MUTED), full());
        TextView amount = PausaUi.editorial(c, Budget.money(available, false), 52);
        amount.setTextColor(over ? MoneyMeters.OVER_TONE : PausaUi.ON_NIGHT);
        amount.setPadding(0, dp(8), 0, dp(4));
        hero.addView(amount, full());
        countMoney(amount, shownAvailable == Long.MIN_VALUE ? 0 : shownAvailable, available);
        shownAvailable = available;
        String detail;
        if (cycle.current && !over && cycle.left() > 0)
            detail = "≈ " + Budget.money(cycle.dailyAllowance(), false) + " al día durante " + cycle.left() + (cycle.left() == 1 ? " día" : " días");
        else if (cycle.current && over) detail = "Lo gastado ya supera lo que queda libre tras lo fijo";
        else detail = "de " + Budget.money(cycle.freeBudget(), false) + " libres una vez pagado lo fijo";
        hero.addView(PausaUi.text(c, detail, 14, PausaUi.ON_NIGHT_MUTED, false), full());

        MoneyMeters.PaycheckBar bar = new MoneyMeters.PaycheckBar(c);
        bar.set(cycle.income(), cycle.fixedPaid, cycle.fixedPending, cycle.freeSpent, cycle.current ? cycle.paceTarget() : -1, animateNext);
        hero.addView(bar, spaced(16, 10));

        LinearLayout legend = new LinearLayout(c);
        legend.addView(legend(MoneyMeters.PAID_TONE, false, "Fijo", Budget.money(cycle.fixed(), false)), new LayoutParams(0, -2, 1));
        legend.addView(legend(over ? MoneyMeters.OVER_TONE : MoneyMeters.SPENT_TONE, false, "Gastado", Budget.money(cycle.freeSpent, false)), new LayoutParams(0, -2, 1));
        legend.addView(legend(0x24F8F5ED, true, "Entró", Budget.money(cycle.income(), false) + (cycle.salaryEstimated ? "*" : "")), new LayoutParams(0, -2, 1));
        hero.addView(legend, full());

        if (cycle.current) {
            long ahead = cycle.paceTarget() - cycle.freeSpent;
            boolean calm = ahead >= 0;
            TextView pace = PausaUi.text(c, (calm ? "Vas " + Budget.money(ahead, false) + " por debajo de tu ritmo"
                    : "Vas " + Budget.money(-ahead, false) + " por encima de tu ritmo"), 13, calm ? MoneyMeters.PAID_TONE : MoneyMeters.OVER_TONE, true);
            pace.setCompoundDrawables(new PausaUi.Symbol(c, calm ? "check" : "pulse", calm ? MoneyMeters.PAID_TONE : MoneyMeters.OVER_TONE, 16), null, null, null);
            pace.setCompoundDrawablePadding(dp(8));
            pace.setGravity(Gravity.CENTER_VERTICAL);
            pace.setBackground(PausaUi.surface(c, 0x1AF8F5ED, 16));
            pace.setPadding(dp(12), dp(10), dp(12), dp(10));
            hero.addView(pace, spaced(16, 0));
            long projection = cycle.projection();
            TextView end = PausaUi.text(c, "A este ritmo cerrarías el ciclo con " + (projection >= 0 ? "+" : "") + Budget.money(projection, false), 13, PausaUi.ON_NIGHT_MUTED, false);
            end.setPadding(dp(2), dp(10), 0, 0);
            hero.addView(end, full());
        }
        hero.setClickable(true);
        hero.setOnClickListener(v -> explain(cycle));
        hero.setContentDescription(eyebrow + ": " + Budget.money(available, false) + ". " + detail + ". Ver cómo se calcula");
        return hero;
    }

    private View legend(int color, boolean outlined, String label, String value) {
        Context c = getContext();
        LinearLayout item = column();
        TextView name = PausaUi.text(c, label, 12, PausaUi.ON_NIGHT_MUTED, false);
        android.graphics.drawable.GradientDrawable swatch = PausaUi.surface(c, color, 4);
        if (outlined) swatch.setStroke(dp(1), 0x66F8F5ED);
        swatch.setBounds(0, 0, dp(10), dp(10));
        name.setCompoundDrawables(swatch, null, null, null);
        name.setCompoundDrawablePadding(dp(6));
        item.addView(name, full());
        TextView amount = PausaUi.text(c, value, 15, PausaUi.ON_NIGHT, true);
        amount.setPadding(dp(16), dp(4), 0, 0);
        item.addView(amount, full());
        return item;
    }

    private void countMoney(TextView view, long from, long to) {
        if (!PausaUi.motion(getContext()) || from == to) { view.setText(Budget.money(to, false)); return; }
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(900); animator.setInterpolator(PausaUi.EASE);
        animator.addUpdateListener(a -> view.setText(Budget.money(from + Math.round((to - from) * (Float) a.getAnimatedValue()), false)));
        animator.start();
    }

    private void explain(Budget.Cycle cycle) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Cómo se calcula");
        sheet.subtitle("Primero se reserva todo lo fijo del ciclo, esté pagado o no. Lo demás que sale de tus cuentas viene de lo libre. "
                + "Los movimientos entre tus propias cuentas no cuentan.");
        sheet.add(sum("Entró este ciclo" + (cycle.salaryEstimated ? " (estimado)" : ""), cycle.income(), PausaUi.INK, false), 2);
        sheet.add(sum("Fijo ya pagado", -cycle.fixedPaid, PausaUi.MUTED, false), 2);
        sheet.add(sum("Fijo por pagar", -cycle.fixedPending, PausaUi.MUTED, false), 2);
        sheet.add(sum("Libre tras lo fijo", cycle.freeBudget(), PausaUi.INK, true), 2);
        sheet.add(sum("Gastado de lo libre", -cycle.freeSpent, PausaUi.MUTED, false), 2);
        sheet.add(sum(cycle.current ? "Te queda" : "Resultado", cycle.available(), cycle.available() < 0 ? PausaUi.TERRACOTTA : PausaUi.GREEN, true), 12);
        if (cycle.invested > 0) sheet.add(note("Además invertiste " + Budget.money(cycle.invested) + " que no forma parte de lo fijo."), 8);
        sheet.add(note("El triángulo sobre la barra marca dónde deberías ir hoy para llegar justo al final del ciclo."), 4);
        sheet.footer(null, PausaUi.action(c, "Entendido", true, sheet::dismiss));
        sheet.show();
    }

    private View sum(String label, long cents, int color, boolean strong) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(strong ? 52 : 40));
        row.setPadding(dp(16), 0, dp(16), 0);
        if (strong) row.setBackground(PausaUi.surface(c, PausaUi.CREAM, 16));
        row.addView(PausaUi.text(c, label, strong ? 15 : 14, strong ? PausaUi.INK : PausaUi.MUTED, strong), new LayoutParams(0, -2, 1));
        TextView value = strong ? PausaUi.editorial(c, Budget.money(cents, true), 20) : PausaUi.text(c, Budget.money(cents, true), 14, color, false);
        value.setTextColor(color);
        row.addView(value);
        return row;
    }

    private TextView note(String value) {
        TextView text = PausaUi.text(getContext(), value, 13, PausaUi.MUTED, false);
        text.setLineSpacing(0, 1.15f);
        text.setPadding(dp(4), dp(4), dp(4), dp(4));
        return text;
    }

    // ------------------------------------------------------------ suggestions

    private View suggestion(Budget.Suggestion suggestion) {
        Context c = getContext();
        LinearLayout card = column();
        card.setBackground(PausaUi.surface(c, PausaUi.SUN_SOFT, 22));
        card.setPadding(dp(16), dp(14), dp(10), dp(8));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(Ledger.category(suggestion.category).symbol, 0xFF9A6A12, 0x33E5AB35, 36), new LayoutParams(dp(36), dp(36)));
        LinearLayout labels = column();
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(PausaUi.eyebrow(c, "¿Es un pago fijo?", 0xFF9A6A12), full());
        TextView text = PausaUi.text(c, suggestion.name + " · " + Budget.money(suggestion.amount) + " hacia el día " + suggestion.dayOfMonth, 15, PausaUi.INK, false);
        text.setPadding(0, dp(4), 0, 0);
        labels.addView(text, full());
        labels.addView(PausaUi.text(c, "Se ha repetido en " + suggestion.cycles + " ciclos", 12, PausaUi.MUTED, false), full());
        top.addView(labels, new LayoutParams(0, -2, 1));
        card.addView(top, full());
        LinearLayout actions = new LinearLayout(c);
        actions.setGravity(Gravity.END);
        actions.addView(PausaUi.quiet(c, "No", PausaUi.MUTED, () -> change("Sugerencia descartada", s -> s.dismissed.add(suggestion.key))));
        actions.addView(PausaUi.quiet(c, "Sí, es fijo", PausaUi.GREEN, () -> change(suggestion.name + " ahora es fijo", s -> s.rules.add(suggestion.toRule()))));
        card.addView(actions, full());
        return card;
    }

    // ------------------------------------------------------------ fixed

    private void fixed(Budget.Cycle cycle) {
        Context c = getContext();
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.BOTTOM);
        TextView title = PausaUi.editorial(c, "Lo fijo", 24);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LayoutParams(0, -2, 1));
        header.addView(PausaUi.text(c, cycle.paidLines() + " de " + cycle.activeLines() + " pagados", 13, PausaUi.GREEN, true));
        month.addView(header, spaced(16, 4));
        String reserved = Budget.money(cycle.fixed(), false) + " reservados";
        if (cycle.fixedPending > 0) reserved += " · faltan " + Budget.money(cycle.fixedPending, false);
        month.addView(PausaUi.text(c, reserved, 13, PausaUi.MUTED, false), spaced(0, 10));
        MoneyMeters.StatusDots dots = new MoneyMeters.StatusDots(c);
        int[] statuses = new int[cycle.lines.size()];
        for (int i = 0; i < statuses.length; i++) statuses[i] = cycle.lines.get(i).status;
        dots.set(statuses, animateNext);
        month.addView(dots, spaced(0, 12));

        LinearLayout grid = column();
        LinearLayout row = null;
        List<View> tiles = new ArrayList<>();
        for (Budget.Line line : cycle.lines) tiles.add(tile(cycle, line, tiles.size()));
        tiles.add(addTile());
        for (int i = 0; i < tiles.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                grid.addView(row, spaced(0, 10));
            }
            boolean lone = i == tiles.size() - 1 && i % 2 == 0;
            LayoutParams p = new LayoutParams(0, lone ? dp(140) : -1, 1);
            if (i % 2 == 1) p.leftMargin = dp(10);
            row.addView(tiles.get(i), p);
        }
        if (tiles.size() % 2 == 1) row.addView(new View(c), withLeft(new LayoutParams(0, 1, 1), 10));
        month.addView(grid, spaced(0, 8));
    }

    private static LayoutParams withLeft(LayoutParams p, int margin) { p.leftMargin = margin; return p; }

    private View tile(Budget.Cycle cycle, Budget.Line line, int position) {
        Context c = getContext();
        int status = line.status;
        int fill = status == Budget.PAID ? PausaUi.SAGE_SOFT : status == Budget.LATE ? PausaUi.PEACH_SOFT
                : status == Budget.SOON || status == Budget.PARTIAL ? PausaUi.SUN_SOFT : PausaUi.SURFACE;
        int accent = status == Budget.PAID ? PausaUi.SAGE : status == Budget.LATE ? PausaUi.TERRACOTTA
                : status == Budget.SOON || status == Budget.PARTIAL ? 0xFFC98A1C : PausaUi.GREEN;
        LinearLayout tile = column();
        android.graphics.drawable.GradientDrawable shape = PausaUi.surface(c, fill, 22);
        if (fill == PausaUi.SURFACE) shape.setStroke(dp(1), PausaUi.LINE);
        tile.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14214E3B), shape, PausaUi.surface(c, Color.WHITE, 22)));
        tile.setPadding(dp(14), dp(12), dp(12), dp(14));
        tile.setMinimumHeight(dp(140));
        if (status == Budget.SKIPPED || status == Budget.MISSED) tile.setAlpha(.6f);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(line.rule.symbol, accent, status == Budget.UPCOMING ? PausaUi.NEUTRAL : 0x99FFFCF6, 38), new LayoutParams(dp(38), dp(38)));
        top.addView(new View(c), new LayoutParams(0, 1, 1));
        if (status == Budget.PAID) {
            PausaUi.Check check = new PausaUi.Check(c, PausaUi.SAGE);
            check.setDiameter(22);
            check.setClickable(false); check.setFocusable(false);
            check.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            top.addView(check, new LayoutParams(dp(30), dp(30)));
            if (animateNext && PausaUi.motion(c)) postDelayed(() -> check.setChecked(true, true), 380 + 70L * position);
            else check.setChecked(true);
        } else {
            TextView pill = PausaUi.eyebrow(c, pill(line), accent);
            pill.setTextSize(9);
            pill.setBackground(PausaUi.surface(c, status == Budget.UPCOMING ? PausaUi.NEUTRAL : 0x99FFFCF6, 8));
            pill.setPadding(dp(7), dp(4), dp(7), dp(4));
            top.addView(pill);
        }
        tile.addView(top, full());

        TextView name = PausaUi.text(c, line.rule.name, 14, PausaUi.INK, true);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(12), 0, 0);
        tile.addView(name, full());
        long shown = status == Budget.PAID || status == Budget.PARTIAL ? line.paid : line.expected;
        TextView amount = PausaUi.editorial(c, Budget.money(shown), 22);
        amount.setPadding(0, dp(4), 0, dp(2));
        if (status == Budget.SKIPPED) amount.setPaintFlags(amount.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        tile.addView(amount, full());
        TextView when = PausaUi.text(c, statusLine(cycle, line), 12, status == Budget.LATE ? PausaUi.TERRACOTTA : PausaUi.MUTED, false);
        when.setMaxLines(2);
        tile.addView(when, full());
        tile.setOnClickListener(v -> lineSheet(cycle, line));
        tile.setContentDescription(line.rule.name + ", " + Budget.money(shown) + ", " + statusLine(cycle, line));
        return tile;
    }

    private static String pill(Budget.Line line) {
        switch (line.status) {
            case Budget.LATE: return "Retrasado";
            case Budget.SOON: return "Pronto";
            case Budget.PARTIAL: return "En curso";
            case Budget.SKIPPED: return "No toca";
            case Budget.MISSED: return "Sin pago";
            default: return Budget.date(line.due);
        }
    }

    private static String statusLine(Budget.Cycle cycle, Budget.Line line) {
        switch (line.status) {
            case Budget.PAID: {
                String text = "Pagado el " + Budget.date(line.paidDay);
                if (line.history.length > 0 && Math.abs(line.paid - line.expected) * 5 > line.expected)
                    text += " · suele ser " + Budget.money(line.expected, false);
                return text;
            }
            case Budget.PARTIAL: return "Llevas " + Budget.money(line.paid) + " de " + Budget.money(line.expected, false);
            case Budget.SOON: {
                int days = line.due - cycle.today;
                return days <= 0 ? "Debería llegar hoy" : days == 1 ? "Llega mañana" : "Llega en " + days + " días";
            }
            case Budget.LATE: return "Se esperaba el " + Budget.date(line.due);
            case Budget.SKIPPED: return "Este ciclo no toca";
            case Budget.MISSED: return "No se pagó este ciclo";
            default: return "Hacia el " + Budget.date(line.due);
        }
    }

    private View addTile() {
        Context c = getContext();
        LinearLayout tile = column();
        tile.setGravity(Gravity.CENTER);
        tile.setMinimumHeight(dp(140));
        tile.setBackground(PausaUi.dashed(c, 0xFFCFC9BC, 22));
        ImageView plus = new ImageView(c);
        plus.setImageDrawable(new PausaUi.Symbol(c, "plus", PausaUi.GREEN, 24));
        tile.addView(plus, new LayoutParams(dp(24), dp(24)));
        TextView label = PausaUi.text(c, "Añadir fijo", 14, PausaUi.GREEN, true);
        label.setPadding(0, dp(8), 0, 0);
        label.setGravity(Gravity.CENTER);
        tile.addView(label, new LayoutParams(-2, -2));
        tile.setClickable(true);
        tile.setOnClickListener(v -> ruleEditor(null, null));
        tile.setContentDescription("Añadir un pago fijo");
        return tile;
    }

    private View badge(String symbol, int color, int fill, int size) {
        Context c = getContext();
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(new PausaUi.Symbol(c, symbol, color, size * 52 / 100));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setBackground(PausaUi.surface(c, fill, size / 2));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private void lineSheet(Budget.Cycle cycle, Budget.Line line) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, line.rule.name);
        String status = statusLine(cycle, line);
        sheet.subtitle(status.contains("suele ser") ? status : status + " · habitual " + Budget.money(line.expected) + (line.rule.invest ? " por ciclo" : ""));
        int n = Math.min(5, line.history.length);
        long[] values = new long[n + 1];
        String[] labels = new String[n + 1];
        for (int i = 0; i < n; i++) {
            values[i] = line.history[line.history.length - n + i];
            labels[i] = Budget.MONTHS[Ledger.civil(snapshot.periods.get(Math.max(0, cycle.index - n + i)).start + 15)[1] - 1];
        }
        values[n] = Math.max(0, line.paid);
        labels[n] = cycle.current ? "ahora" : Budget.MONTHS[Ledger.civil(cycle.period.start + 15)[1] - 1];
        MoneyMeters.HistoryBars bars = new MoneyMeters.HistoryBars(c);
        bars.set(values, labels, line.status == Budget.LATE ? PausaUi.TERRACOTTA : PausaUi.SAGE);
        bars.setContentDescription("Importe pagado en los últimos ciclos");
        sheet.add(bars, 12);
        if (line.entries.isEmpty()) sheet.add(note(cycle.current ? "Aún no ha pasado por tus cuentas en este ciclo." : "No hubo cargos en este ciclo."), 8);
        else for (Budget.Entry entry : line.entries) sheet.add(entryRow(entry, () -> { sheet.dismiss(); classify(entry); }), 2);
        String key = Budget.skipKey(line.rule, cycle.period);
        boolean skipped = snapshot.settings.skipped.contains(key);
        Button skip = PausaUi.quiet(c, skipped ? "Sí toca este ciclo" : "Este ciclo no toca", PausaUi.GREEN, () -> {
            sheet.dismiss();
            change(skipped ? line.rule.name + " vuelve a contar" : line.rule.name + " no cuenta este ciclo", s -> {
                if (skipped) s.skipped.remove(key); else s.skipped.add(key);
            });
        });
        if (line.paid > 0 || !cycle.current) skip = null;
        sheet.footer(skip, PausaUi.action(c, "Editar", true, () -> { sheet.dismiss(); ruleEditor(line.rule, null); }));
        sheet.show();
    }

    // ------------------------------------------------------------ everyday

    private void everyday(Budget.Cycle cycle) {
        Context c = getContext();
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.BOTTOM);
        TextView title = PausaUi.editorial(c, "Día a día", 24);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LayoutParams(0, -2, 1));
        header.addView(PausaUi.text(c, Budget.money(cycle.freeSpent, false) + " en " + cycle.elapsed() + (cycle.elapsed() == 1 ? " día" : " días"), 13, PausaUi.GREEN, true));
        month.addView(header, spaced(18, 10));

        LinearLayout card = column();
        card.setBackground(PausaUi.card(c));
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        long ideal = cycle.freeBudget() > 0 ? cycle.freeBudget() / Math.max(1, cycle.period.days()) : 0;
        String resting = "Media " + Budget.money(cycle.freeSpent / Math.max(1, cycle.elapsed())) + " al día" + (ideal > 0 ? " · lo cómodo son " + Budget.money(ideal, false) : "");
        TextView caption = PausaUi.text(c, resting, 13, PausaUi.MUTED, false);
        card.addView(caption, full());
        MoneyMeters.DailyBars bars = new MoneyMeters.DailyBars(c);
        int todayIndex = cycle.current ? cycle.today - cycle.period.start : -1;
        bars.set(cycle.daily, ideal, todayIndex, cycle.period.start, animateNext);
        bars.setContentDescription("Gasto libre por día. " + resting);
        bars.setListener(selected -> {
            if (selected < 0) { caption.setText(resting); caption.setTextColor(PausaUi.MUTED); return; }
            int day = cycle.period.start + selected, count = 0;
            for (Budget.Entry entry : cycle.entries) if (entry.free() && entry.txn.day == day && entry.txn.cents < 0) count++;
            caption.setText(PausaUi.capitalize(Budget.WEEKDAYS[Ledger.weekday(day)]) + " " + Budget.date(day) + " · "
                    + Budget.money(cycle.daily[selected]) + (count == 0 ? "" : " en " + count + (count == 1 ? " compra" : " compras")));
            caption.setTextColor(PausaUi.INK);
        });
        card.addView(bars, spaced(10, 0));
        if (cycle.previousAtSameDay >= 0 && cycle.current) {
            long delta = cycle.previousAtSameDay - cycle.freeSpent;
            boolean better = delta >= 0;
            TextView compare = PausaUi.text(c, Budget.money(Math.abs(delta), false) + (better ? " menos" : " más") + " que el ciclo pasado a estas alturas",
                    13, better ? PausaUi.SAGE : PausaUi.TERRACOTTA, true);
            compare.setCompoundDrawables(new PausaUi.Symbol(c, better ? "in" : "out", better ? PausaUi.SAGE : PausaUi.TERRACOTTA, 16), null, null, null);
            compare.setCompoundDrawablePadding(dp(6));
            compare.setPadding(0, dp(12), 0, 0);
            card.addView(compare, full());
        }
        month.addView(card, spaced(0, 12));

        if (cycle.habit != null) month.addView(habit(cycle), spaced(0, 12));

        if (cycle.slices.isEmpty()) return;
        LinearLayout list = column();
        list.setBackground(PausaUi.card(c));
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        long total = Math.max(1, cycle.freeSpent);
        int position = 0;
        for (Budget.Slice slice : cycle.slices) {
            if (slice.cents <= 0 && slice.entries.size() == 0) continue;
            list.addView(sliceRow(slice, total, position++), full());
        }
        month.addView(list, spaced(0, 12));
    }

    private View habit(Budget.Cycle cycle) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(PausaUi.ripple(c, PausaUi.CREAM_DEEP, 22));
        row.setPadding(dp(14), dp(14), dp(16), dp(14));
        row.addView(badge("spark", PausaUi.TERRACOTTA, PausaUi.PEACH_SOFT, 40), new LayoutParams(dp(40), dp(40)));
        LinearLayout labels = column();
        labels.setPadding(dp(14), 0, 0, 0);
        labels.addView(PausaUi.eyebrow(c, "Lo que más se repite", PausaUi.TERRACOTTA), full());
        TextView text = PausaUi.editorial(c, cycle.habit.merchant, 20);
        text.setPadding(0, dp(4), 0, dp(2));
        labels.addView(text, full());
        String perDay = cycle.habit.count + " veces · " + Budget.money(cycle.habit.cents)
                + (cycle.current && cycle.left() > 0 ? " · al ritmo actual, " + Budget.money(cycle.habit.cents * cycle.period.days() / Math.max(1, cycle.elapsed()), false) + " al cerrar el ciclo" : "");
        labels.addView(PausaUi.text(c, perDay, 12, PausaUi.MUTED, false), full());
        row.addView(labels, new LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> {
            List<Budget.Entry> entries = new ArrayList<>();
            for (Budget.Entry entry : cycle.entries) if (entry.free() && entry.txn.merchant.equals(cycle.habit.merchant)) entries.add(entry);
            listSheet(cycle.habit.merchant, cycle.habit.count + " veces este ciclo", entries);
        });
        row.setContentDescription("Lo que más se repite: " + cycle.habit.merchant + ", " + perDay);
        return row;
    }

    private View sliceRow(Budget.Slice slice, long total, int position) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(8), dp(6), dp(10), dp(6));
        row.setBackground(PausaUi.ripple(c, Color.TRANSPARENT, 16));
        row.addView(badge(slice.category.symbol, slice.category.color, (slice.category.color & 0x00FFFFFF) | 0x1F000000, 38), new LayoutParams(dp(38), dp(38)));
        LinearLayout middle = column();
        middle.setPadding(dp(12), 0, dp(10), 0);
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(PausaUi.text(c, slice.category.label, 15, PausaUi.INK, false), new LayoutParams(0, -2, 1));
        line.addView(PausaUi.text(c, Budget.money(slice.cents), 15, PausaUi.INK, true));
        middle.addView(line, full());
        MoneyMeters.Share share = new MoneyMeters.Share(c, slice.category.color, slice.cents / (float) total);
        if (animateNext) share.animateIn(260 + 50L * position);
        middle.addView(share, spaced(7, 3));
        middle.addView(PausaUi.text(c, slice.entries.size() + (slice.entries.size() == 1 ? " movimiento" : " movimientos")
                + " · " + Math.round(100f * slice.cents / total) + " %", 11, PausaUi.MUTED, false), full());
        row.addView(middle, new LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> listSheet(slice.category.label, Budget.money(slice.cents) + " este ciclo", slice.entries));
        row.setContentDescription(slice.category.label + ", " + Budget.money(slice.cents) + ", " + slice.entries.size() + " movimientos");
        return row;
    }

    private void listSheet(String title, String subtitle, List<Budget.Entry> entries) {
        PausaUi.Sheet sheet = new PausaUi.Sheet(getContext(), title);
        sheet.subtitle(subtitle + " · toca uno para cambiar cómo cuenta");
        for (Budget.Entry entry : entries) sheet.add(entryRow(entry, () -> { sheet.dismiss(); classify(entry); }), 2);
        sheet.show();
    }

    private void footer() {
        Context c = getContext();
        String when = lastSync <= 0 ? "Sin sincronizaciones completas todavía" : "Actualizado " + ago(System.currentTimeMillis() - lastSync);
        TextView text = PausaUi.text(c, "ABANCA y Trade Republic, leídos desde tu teléfono.\n" + when + (failed ? " · no se pudo leer la base de datos" : ""), 12, PausaUi.MUTED, false);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(0, 1.2f);
        month.addView(text, spaced(14, 0));
        Button sync = PausaUi.quiet(c, "Sincronizar bancos", PausaUi.GREEN, () -> show(2));
        month.addView(sync, new LayoutParams(-1, dp(48)));
    }

    static String ago(long millis) {
        long minutes = Math.max(0, millis / 60_000L);
        if (minutes < 1) return "ahora mismo";
        if (minutes < 60) return "hace " + minutes + " min";
        long hours = minutes / 60;
        if (hours < 24) return "hace " + hours + " h";
        long days = hours / 24;
        return days == 1 ? "ayer" : "hace " + days + " días";
    }

    private void empty(LinearLayout parent) {
        Context c = getContext();
        LinearLayout empty = column();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(20), dp(26), dp(20), dp(22));
        empty.setBackground(PausaUi.dashed(c, 0xFFCFC9BC, 24));
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(new PausaUi.Symbol(c, "wallet", PausaUi.SUN, 34));
        empty.addView(icon, new LayoutParams(dp(34), dp(34)));
        TextView title = PausaUi.editorial(c, failed ? "No se pudieron leer tus datos." : "Aún no hay movimientos.", 20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(6));
        empty.addView(title, full());
        TextView hint = PausaUi.text(c, failed ? "No se ha borrado nada. Prueba a abrir de nuevo la sección."
                : "Sincroniza ABANCA y Trade Republic: con tu nómina y tus pagos, Pausa separa lo fijo de lo libre.", 13, PausaUi.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.15f);
        empty.addView(hint, full());
        Button go = PausaUi.action(c, "Ir a Bancos", true, () -> show(2));
        LayoutParams p = new LayoutParams(-2, dp(48));
        p.topMargin = dp(16);
        empty.addView(go, p);
        parent.addView(empty, spaced(16, 0));
    }

    // ------------------------------------------------------------ Movimientos

    private void renderMoves() {
        Context c = getContext();
        moves.removeAllViews();
        if (txns == null || txns.isEmpty() || snapshot == null || snapshot.periods.isEmpty()) { empty(moves); return; }
        Budget.Cycle cycle = Budget.cycle(snapshot, index);
        moves.addView(cycleHeader(cycle), spaced(0, 6));
        HorizontalScrollView scroller = new HorizontalScrollView(c);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        LinearLayout chips = new LinearLayout(c);
        for (int i = 0; i < FILTERS.length; i++) {
            final int option = i;
            TextView chip = PausaUi.chip(c, FILTERS[i], i == filter, () -> { filter = option; renderMoves(); });
            LayoutParams p = new LayoutParams(-2, dp(44));
            if (i > 0) p.leftMargin = dp(8);
            chips.addView(chip, p);
        }
        scroller.addView(chips);
        moves.addView(scroller, spaced(0, 12));

        List<Budget.Entry> shown = new ArrayList<>();
        for (Budget.Entry entry : cycle.entries) if (matches(entry)) shown.add(entry);
        int counting = 0;
        for (Budget.Entry entry : cycle.entries) if (entry.counts()) counting++;
        moves.addView(PausaUi.text(c, shown.size() + " de " + cycle.entries.size() + " movimientos · " + counting
                + " cuentan para el presupuesto. Toca uno para enseñarle a Pausa qué es.", 13, PausaUi.MUTED, false), spaced(0, 14));
        int day = Integer.MIN_VALUE;
        LinearLayout card = null;
        for (Budget.Entry entry : shown) {
            if (entry.txn.day != day) {
                day = entry.txn.day;
                LinearLayout header = new LinearLayout(c);
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(dp(4), dp(10), dp(4), dp(6));
                String name = (day == cycle.today ? "Hoy · " : day == cycle.today - 1 ? "Ayer · " : "") + Budget.WEEKDAYS[Ledger.weekday(day)] + " " + Budget.date(day);
                header.addView(PausaUi.eyebrow(c, name, PausaUi.MUTED), new LayoutParams(0, -2, 1));
                long free = 0;
                for (Budget.Entry other : cycle.entries) if (other.txn.day == day && other.free()) free -= other.txn.cents;
                if (free != 0) header.addView(PausaUi.text(c, Budget.money(free) + " libre", 12, PausaUi.MUTED, true));
                moves.addView(header, full());
                card = column();
                card.setBackground(PausaUi.card(c));
                card.setPadding(dp(6), dp(4), dp(6), dp(4));
                moves.addView(card, spaced(0, 4));
            }
            card.addView(entryRow(entry, () -> classify(entry)), full());
        }
        if (shown.isEmpty()) moves.addView(note("Nada con este filtro en este ciclo."), full());
    }

    private boolean matches(Budget.Entry entry) {
        switch (filter) {
            case 1: return entry.free();
            case 2: return entry.fixed();
            case 3: return entry.txn.cents > 0 && entry.counts();
            case 4: return !entry.counts();
            default: return true;
        }
    }

    private View entryRow(Budget.Entry entry, Runnable action) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(8), dp(6), dp(10), dp(6));
        row.setBackground(PausaUi.ripple(c, Color.TRANSPARENT, 16));
        boolean counts = entry.counts();
        int color = entry.fixed() ? PausaUi.GREEN : entry.kind == Ledger.Kind.INCOME ? PausaUi.SAGE
                : entry.free() ? Ledger.category(entry.category).color : PausaUi.MUTED;
        String symbol = entry.fixed() ? entry.rule.symbol : entry.kind == Ledger.Kind.INCOME ? "in"
                : entry.free() ? Ledger.category(entry.category).symbol : entry.kind == Ledger.Kind.INVEST ? "seed" : "transfer";
        row.addView(badge(symbol, color, counts ? (color & 0x00FFFFFF) | 0x1F000000 : PausaUi.NEUTRAL, 38), new LayoutParams(dp(38), dp(38)));
        LinearLayout labels = column();
        labels.setPadding(dp(12), 0, dp(8), 0);
        TextView name = PausaUi.text(c, label(entry), 15, counts ? PausaUi.INK : PausaUi.MUTED, false);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name, full());
        TextView meta = PausaUi.text(c, entry.txn.sourceLabel + " · " + tag(entry), 12, PausaUi.MUTED, false);
        meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(3), 0, 0);
        labels.addView(meta, full());
        row.addView(labels, new LayoutParams(0, -2, 1));
        long cents = entry.txn.cents;
        TextView amount = PausaUi.text(c, (cents > 0 ? "+" : "") + Budget.money(cents, true), 15,
                !counts ? 0xFF9C9A92 : cents > 0 ? PausaUi.SAGE : PausaUi.INK, counts);
        if (!counts) amount.setPaintFlags(amount.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        row.addView(amount);
        row.setOnClickListener(v -> action.run());
        row.setContentDescription(label(entry) + ", " + Budget.money(cents, true) + ", " + tag(entry));
        return row;
    }

    private static String label(Budget.Entry entry) { return entry.txn.salary ? "Nómina" : entry.txn.merchant; }

    private static String tag(Budget.Entry entry) {
        String learned = entry.taught.equals("merchant") ? " · recordado" : entry.taught.equals("txn") ? " · a mano" : "";
        if (entry.txn.duplicate && entry.taught.isEmpty()) return "Repetido en la cuenta";
        if (entry.fixed()) return "Fijo · " + entry.rule.name + learned;
        if (entry.free()) return Ledger.category(entry.category).label + learned;
        switch (entry.kind) {
            case INCOME: return (entry.txn.salary ? "Nómina" : "Ingreso") + learned;
            case INVEST: return "Inversión · no cuenta" + learned;
            case TRANSFER: return (entry.txn.cents > 0 ? "Entrada entre tus cuentas" : "Entre tus cuentas") + learned;
            default: return "No cuenta" + learned;
        }
    }

    // ------------------------------------------------------------ teaching

    interface Edit { void apply(Budget.Settings settings) throws Exception; }

    /** Every correction is saved immediately and can be undone from the snackbar. */
    private void change(String message, Edit edit) {
        Context c = getContext();
        Budget.Settings settings = BudgetStore.load(c);
        String before;
        try { before = settings.toJson().toString(); edit.apply(settings); }
        catch (Exception error) { PausaUi.snack(c, "No se pudo guardar el cambio.", null, null); return; }
        BudgetStore.save(c, settings);
        recompute(false);
        PausaUi.snack(c, message, "Deshacer", () -> {
            try { BudgetStore.save(c, Budget.Settings.fromJson(new JSONObject(before))); recompute(false); }
            catch (Exception ignored) { /* The previous copy was produced by this same code. */ }
        });
    }

    private void classify(Budget.Entry entry) {
        Context c = getContext();
        Ledger.Txn txn = entry.txn;
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, label(entry));
        sheet.subtitle(PausaUi.capitalize(Budget.WEEKDAYS[Ledger.weekday(txn.day)]) + " " + Budget.date(txn.day) + " · " + txn.sourceLabel
                + " · " + Budget.money(txn.cents, true) + "\n" + txn.raw.trim());
        PausaUi.Check remember = new PausaUi.Check(c, PausaUi.SAGE);
        remember.setChecked(txn.cents < 0 && !txn.salary);
        String current = currentCode(entry);
        Flow chips = new Flow(c);
        if (txn.cents < 0) {
            sheet.add(PausaUi.eyebrow(c, "Gasto libre", PausaUi.MUTED), 8);
            for (Ledger.Category category : Ledger.CATEGORIES) if (!category.id.equals("deudas"))
                chips.addView(option(category.label, category.symbol, category.color, ("cat:" + category.id).equals(current),
                        () -> teach(sheet, entry, "cat:" + category.id, remember.isChecked(), category.label)));
            sheet.add(chips, 14);
            Flow fixedChips = new Flow(c);
            sheet.add(PausaUi.eyebrow(c, "Pago fijo", PausaUi.MUTED), 8);
            for (Budget.Rule rule : snapshot.settings.rules) if (rule.enabled)
                fixedChips.addView(option(rule.name, rule.symbol, PausaUi.GREEN, ("rule:" + rule.id).equals(current),
                        () -> teach(sheet, entry, "rule:" + rule.id, remember.isChecked(), rule.name)));
            fixedChips.addView(option("Nuevo fijo", "plus", PausaUi.GREEN, false, () -> { sheet.dismiss(); ruleEditor(null, entry); }));
            sheet.add(fixedChips, 14);
        }
        Flow other = new Flow(c);
        sheet.add(PausaUi.eyebrow(c, txn.cents < 0 ? "Otra cosa" : "Este dinero…", PausaUi.MUTED), 8);
        if (txn.cents > 0) {
            other.addView(option("Es un ingreso", "in", PausaUi.SAGE, "income".equals(current), () -> teach(sheet, entry, "income", remember.isChecked(), "Ingreso")));
            other.addView(option("Es una devolución", "reset", PausaUi.SAGE, "refund".equals(current), () -> teach(sheet, entry, "refund", remember.isChecked(), "Devolución")));
        }
        other.addView(option("No cuenta: entre mis cuentas", "transfer", PausaUi.MUTED, "transfer".equals(current),
                () -> teach(sheet, entry, "transfer", remember.isChecked(), "No cuenta")));
        sheet.add(other, 14);

        LinearLayout rememberRow = new LinearLayout(c);
        rememberRow.setGravity(Gravity.CENTER_VERTICAL);
        rememberRow.setBackground(PausaUi.ripple(c, PausaUi.CREAM, 18));
        rememberRow.setPadding(dp(4), 0, dp(14), 0);
        remember.setContentDescription("Recordar para " + txn.merchant);
        rememberRow.addView(remember, new LayoutParams(dp(48), dp(48)));
        rememberRow.addView(PausaUi.text(c, "Recordar para todo lo de «" + txn.merchant + "»", 14, PausaUi.INK, false), new LayoutParams(0, -2, 1));
        rememberRow.setOnClickListener(v -> remember.performClick());
        if (!txn.salary) sheet.add(rememberRow, 4);
        Button reset = !entry.taught.isEmpty() ? PausaUi.quiet(c, "Volver a lo automático", PausaUi.TERRACOTTA, () -> {
            sheet.dismiss();
            change("Vuelve a clasificarse solo", s -> { s.txn.remove(txn.id); if (entry.taught.equals("merchant")) s.merchant.remove(txn.merchantKey); });
        }) : null;
        sheet.footer(reset, PausaUi.action(c, "Cerrar", false, sheet::dismiss));
        sheet.show();
    }

    private static String currentCode(Budget.Entry entry) {
        if (entry.fixed()) return "rule:" + entry.rule.id;
        if (entry.free()) return entry.kind == Ledger.Kind.REFUND && entry.txn.cents > 0 ? "refund" : "cat:" + entry.category;
        if (entry.kind == Ledger.Kind.INCOME) return "income";
        return "transfer";
    }

    private void teach(PausaUi.Sheet sheet, Budget.Entry entry, String code, boolean remember, String label) {
        sheet.dismiss();
        Ledger.Txn txn = entry.txn;
        change(remember ? "«" + txn.merchant + "» → " + label : "Movimiento → " + label, s -> {
            if (remember) { s.merchant.put(txn.merchantKey, code); s.txn.remove(txn.id); }
            else s.txn.put(txn.id, code);
        });
    }

    private TextView option(String label, String symbol, int color, boolean selected, Runnable action) {
        Context c = getContext();
        TextView chip = PausaUi.chip(c, label, selected, action);
        chip.setTextSize(13);
        chip.setCompoundDrawables(new PausaUi.Symbol(c, symbol, selected ? PausaUi.SURFACE : color, 16), null, null, null);
        chip.setCompoundDrawablePadding(dp(6));
        chip.setPadding(dp(12), dp(6), dp(14), dp(6));
        return chip;
    }

    private static final String[] RULE_SYMBOLS = {"home", "heart", "doc", "card", "shield", "dumbbell", "play", "music", "phone", "box", "note", "spark", "seed", "train", "people", "cash"};

    /** Create or edit a fixed line. With a movement, it starts from that movement's merchant and amount. */
    private void ruleEditor(Budget.Rule existing, Budget.Entry from) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, existing == null ? "Nuevo pago fijo" : "Editar pago fijo");
        sheet.subtitle("Pausa lo reconoce cuando el concepto del movimiento contiene alguno de los textos. El importe y el día se ajustan solos con lo que se cobra.");
        EditText name = field(sheet, "Nombre", existing != null ? existing.name : from != null ? from.txn.merchant : "", InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        long amountCents = existing != null ? existing.expected : from != null ? Math.abs(from.txn.cents) : 0;
        EditText amount = field(sheet, "Importe habitual en euros", amountCents > 0 ? Budget.money(amountCents, true).replace(" €", "").replace(".", "") : "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int dayValue = existing != null ? existing.day : from != null ? Ledger.civil(from.txn.day)[2] : 1;
        EditText day = field(sheet, "Día aproximado del mes", String.valueOf(dayValue), InputType.TYPE_CLASS_NUMBER);
        String words = existing != null ? TextUtils.join(", ", existing.keywords) : from != null ? from.txn.merchantKey : "";
        EditText keywords = field(sheet, "Texto del concepto (separa con comas)", words, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if (existing != null && existing.invest) keywords.setEnabled(false);
        final String[] symbol = {existing != null ? existing.symbol : from != null ? Ledger.category(from.category).symbol : "spark"};
        Flow icons = new Flow(c);
        List<TextView> iconViews = new ArrayList<>();
        for (String option : RULE_SYMBOLS) {
            TextView icon = PausaUi.chip(c, "", option.equals(symbol[0]), null);
            icon.setCompoundDrawables(new PausaUi.Symbol(c, option, option.equals(symbol[0]) ? PausaUi.SURFACE : PausaUi.INK, 20), null, null, null);
            icon.setPadding(dp(12), dp(8), dp(12), dp(8));
            icon.setContentDescription("Icono " + option);
            icon.setOnClickListener(v -> {
                symbol[0] = option;
                for (int i = 0; i < iconViews.size(); i++) {
                    boolean on = RULE_SYMBOLS[i].equals(option);
                    PausaUi.setChip(iconViews.get(i), on);
                    iconViews.get(i).setCompoundDrawables(new PausaUi.Symbol(c, RULE_SYMBOLS[i], on ? PausaUi.SURFACE : PausaUi.INK, 20), null, null, null);
                }
            });
            iconViews.add(icon);
            icons.addView(icon);
        }
        sheet.add(icons, 8);
        Button delete = existing == null ? null : PausaUi.quiet(c, "Quitar", PausaUi.TERRACOTTA, () -> {
            sheet.dismiss();
            change(existing.name + " ya no es fijo", s -> s.rules.remove(s.rule(existing.id)));
        });
        sheet.footer(delete, PausaUi.action(c, "Guardar", true, () -> {
            String title = name.getText().toString().trim();
            long cents;
            int dayOfMonth;
            try {
                cents = Ledger.cents(amount.getText().toString().trim().replace(",", "."));
                dayOfMonth = Integer.parseInt(day.getText().toString().trim());
                if (cents <= 0) throw new NumberFormatException();
            } catch (Exception error) { amount.setError("Introduce un importe, por ejemplo 14,99"); return; }
            if (dayOfMonth < 1 || dayOfMonth > 31) { day.setError("Entre 1 y 31"); return; }
            if (title.isEmpty()) { name.setError("Ponle un nombre"); return; }
            List<String> keys = new ArrayList<>();
            for (String part : keywords.getText().toString().split(",")) if (!Ledger.key(part).isEmpty()) keys.add(Ledger.key(part));
            if (keys.isEmpty() && (existing == null || !existing.invest)) { keywords.setError("Añade un texto que aparezca en el concepto"); return; }
            sheet.dismiss();
            change(title + (existing == null ? " añadido a lo fijo" : " guardado"), s -> {
                Budget.Rule rule = existing == null ? null : s.rule(existing.id);
                if (rule == null) {
                    rule = new Budget.Rule("user-" + System.currentTimeMillis(), title, symbol[0], cents, dayOfMonth);
                    s.rules.add(rule);
                }
                boolean amountChanged = rule.expected != cents;
                rule.name = title; rule.symbol = symbol[0]; rule.expected = cents; rule.day = dayOfMonth;
                if (amountChanged && existing != null) rule.learn = false;
                if (!rule.invest) { rule.keywords.clear(); rule.keywords.addAll(keys); }
                if (from != null) s.txn.put(from.txn.id, "rule:" + rule.id);
            });
        }));
        TaskSheets.showWithKeyboard(sheet);
    }

    private EditText field(PausaUi.Sheet sheet, String hint, String value, int type) {
        Context c = getContext();
        sheet.add(PausaUi.text(c, hint, 12, PausaUi.MUTED, true), 6);
        EditText input = new EditText(c);
        PausaUi.input(input);
        input.setInputType(InputType.TYPE_CLASS_TEXT | type);
        if ((type & InputType.TYPE_CLASS_NUMBER) != 0) input.setInputType(type);
        input.setSingleLine(true);
        input.setText(value);
        input.setContentDescription(hint);
        sheet.add(input, 12);
        return input;
    }

    // ------------------------------------------------------------ layout helpers

    /** Wraps chips onto as many lines as they need. */
    static final class Flow extends ViewGroup {
        private final int gap;
        Flow(Context c) { super(c); gap = PausaUi.dp(c, 8); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += line + gap; line = 0; }
                x += child.getMeasuredWidth() + gap;
                line = Math.max(line, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + line);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += line + gap; line = 0; }
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                x += child.getMeasuredWidth() + gap;
                line = Math.max(line, child.getMeasuredHeight());
            }
        }
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(getContext()); v.setOrientation(VERTICAL); return v;
    }
    private static LayoutParams full() { return new LayoutParams(-1, -2); }
    private LayoutParams spaced(int top, int bottom) {
        LayoutParams p = full(); p.topMargin = dp(top); p.bottomMargin = dp(bottom); return p;
    }
    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
