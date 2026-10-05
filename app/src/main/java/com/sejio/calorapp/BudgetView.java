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
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dinero: the payroll-to-payroll budget built from the banks already saved on the phone.
 * Mes shows what is fixed, what is free and how the days are going; Movimientos lets the owner
 * teach the app; Bancos shows how fresh each bank is and updates it on request. Reading never contacts a bank.
 */
final class BudgetView extends LinearLayout {
    interface Source {
        JSONArray movements(Context context) throws Exception;
        /** When the bank ("abanca", "trade_republic") was last read completely; 0 if never. */
        long lastSync(Context context, String bank);
        /** Stored Trade Republic portfolio readings, oldest first. */
        JSONArray portfolios(Context context) throws Exception;
    }

    /** Replaced by instrumentation tests with a fixture; production reads the encrypted ledger. */
    static Source source = new Source() {
        @Override public JSONArray movements(Context context) throws Exception { return BankingDatabase.get(context).movements(); }
        @Override public long lastSync(Context context, String bank) { return BankingDatabase.get(context).lastSync(bank); }
        @Override public JSONArray portfolios(Context context) throws Exception { return BankingDatabase.get(context).portfolios(); }
    };
    static int todayOverride = Integer.MIN_VALUE;

    static final int MONTH = 0, MOVES = 1, PORTFOLIO = 2, BANKS = 3;
    private static final String[] FILTERS = {"Todo", "Libre", "Fijo", "Ahorro", "Imprevistos", "Entradas", "No cuenta"};

    private final PausaUi.Segmented tabs;
    private final View[] pages;
    private final LinearLayout month, moves;
    private final BankSyncView banking;
    private final PortfolioView portfolio;
    private Portfolio.View holdings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<Ledger.Txn> txns;
    private Budget.Snapshot snapshot;
    private long tradeSync, abancaSync, generation;
    /** Movements known before the last load, to announce what a sync brought. -1 until the first load. */
    private int known = -1;
    private int page = -1, index = -1, filter;
    private boolean failed, animateNext = true;
    private long shownAvailable = Long.MIN_VALUE;

    BudgetView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context);
        header.setPadding(dp(20), dp(10), dp(20), dp(6));
        tabs = new PausaUi.Segmented(context, new String[]{"Mes", "Movimientos", "Cartera", "Bancos"}, this::show);
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
        banking = new BankSyncView(context, this::load);
        portfolio = new PortfolioView(context, new PortfolioView.Host() {
            @Override public void reload() { load(); }
            @Override public void openBanks() { show(BANKS); }
        });
        pages = new View[]{monthScroll, movesScroll, portfolio, banking};
        for (View view : pages) { view.setVisibility(GONE); frame.addView(view, new FrameLayout.LayoutParams(-1, -1)); }
        show(0);
    }

    // ------------------------------------------------------------ lifecycle

    void refresh() {
        if (page == BANKS) banking.refresh();
        if (page == PORTFOLIO) portfolio.watch();
        load();
    }

    /** Leaving Dinero stops watching the Trade Republic client. */
    void pause() { banking.stop(); portfolio.stop(); }

    private void show(int next) {
        if (next == page) return;
        int previous = page;
        page = next;
        tabs.select(next, previous >= 0);
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == next ? VISIBLE : GONE);
        if (next == BANKS) banking.refresh(); else banking.stop();
        if (next == PORTFOLIO) portfolio.watch(); else portfolio.stop();
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
            Portfolio.View readings = null;
            long trade = 0, abanca = 0;
            try {
                loaded = Ledger.fromDatabase(source.movements(app), TimeZone.getDefault());
                readings = Portfolio.from(source.portfolios(app));
                trade = source.lastSync(app, "trade_republic");
                abanca = source.lastSync(app, "abanca");
            } catch (Exception error) {
                // Shown below; nothing is replaced or deleted.
            }
            final List<Ledger.Txn> result = loaded;
            final long t = trade, a = abanca;
            final Portfolio.View held = readings;
            post(() -> {
                if (version != generation) return;
                failed = result == null;
                if (result != null) {
                    int fresh = result.size() - known;
                    if (known >= 0 && fresh > 0 && isShown())
                        PausaUi.snack(getContext(), fresh == 1 ? "1 movimiento nuevo" : fresh + " movimientos nuevos", null, null);
                    known = result.size();
                    txns = result; tradeSync = t; abancaSync = a; holdings = held;
                }
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
        portfolio.show(holdings, snapshot);
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

        if (cycle.current) {
            View stale = staleNotice();
            if (stale != null) month.addView(stale, spaced(0, 10));
            List<Budget.Review> pending = Budget.review(snapshot);
            if (!pending.isEmpty()) month.addView(reviewPill(pending.size()), spaced(0, 12));
        }
        month.addView(hero(cycle), spaced(0, 14));
        fixed(cycle);
        savings(cycle);
        everyday(cycle);
        footer();
        if (animateNext) PausaUi.stagger(month, 8);
    }


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
        bar.set(cycle.income(), cycle.fixedPaid, cycle.fixedPending, cycle.savingsReserve(), cycle.freeSpent + cycle.uncovered(),
                cycle.current ? cycle.paceTarget() : -1, animateNext);
        hero.addView(bar, spaced(16, 10));

        LinearLayout legend = new LinearLayout(c);
        legend.addView(legend(MoneyMeters.PAID_TONE, false, "Fijo", Budget.money(cycle.fixed(), false)), new LayoutParams(0, -2, 1));
        legend.addView(legend(MoneyMeters.SAVE_TONE, false, "Ahorro", Budget.money(cycle.savingsReserve(), false)), new LayoutParams(0, -2, 1));
        legend.addView(legend(over ? MoneyMeters.OVER_TONE : MoneyMeters.SPENT_TONE, false, "Gastado", Budget.money(cycle.freeSpent + cycle.uncovered(), false)), new LayoutParams(0, -2, 1));
        legend.addView(legend(0x24F8F5ED, true, "Entró", Budget.money(cycle.income(), false) + (cycle.salaryEstimated ? "*" : "")), new LayoutParams(0, -2, 1));
        hero.addView(legend, full());
        if (cycle.unexpected > 0) {
            String text = "Imprevistos " + Budget.money(cycle.unexpected, false) + (cycle.uncovered() == 0 ? " · cubiertos por el colchón"
                    : cycle.covered > 0 ? " · " + Budget.money(cycle.uncovered(), false) + " salen de lo libre" : " · salen de lo libre");
            TextView surprise = PausaUi.text(c, text, 13, PausaUi.ON_NIGHT_MUTED, false);
            surprise.setCompoundDrawables(new PausaUi.Symbol(c, "umbrella", PausaUi.ON_NIGHT_MUTED, 16), null, null, null);
            surprise.setCompoundDrawablePadding(dp(8));
            surprise.setGravity(Gravity.CENTER_VERTICAL);
            hero.addView(surprise, spaced(14, 0));
        }

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
        TextView amount = PausaUi.text(c, value, 14, PausaUi.ON_NIGHT, true);
        amount.setSingleLine(true);
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
        sheet.add(sum(cycle.current ? "Ahorro apartado" : "Ahorro", -cycle.savingsReserve(), PausaUi.MUTED, false), 2);
        sheet.add(sum("Libre tras lo fijo y el ahorro", cycle.freeBudget(), PausaUi.INK, true), 2);
        sheet.add(sum("Gastado de lo libre", -cycle.freeSpent, PausaUi.MUTED, false), 2);
        if (cycle.unexpected > 0) sheet.add(sum("Imprevistos sin colchón", -cycle.uncovered(), PausaUi.MUTED, false), 2);
        sheet.add(sum(cycle.current ? "Te queda" : "Resultado", cycle.available(), cycle.available() < 0 ? PausaUi.TERRACOTTA : PausaUi.GREEN, true), 12);
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

    /** A quiet line when a bank's data is old enough that the month may be missing things. */
    private View staleNotice() {
        long now = System.currentTimeMillis();
        String behind = null;
        long when = 0;
        if (abancaSync > 0 && now - abancaSync >= BankSyncView.FRESH) { behind = "ABANCA"; when = abancaSync; }
        if (tradeSync > 0 && now - tradeSync >= BankSyncView.FRESH && (behind == null || tradeSync < when)) { behind = "Trade Republic"; when = tradeSync; }
        if (behind == null) return null;
        Context c = getContext();
        boolean old = now - when >= BankSyncView.STALE;
        TextView row = PausaUi.text(c, behind + ": datos de " + ago(now - when) + " · Actualizar", 13, old ? PausaUi.TERRACOTTA : PausaUi.MUTED, true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(44));
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setBackground(PausaUi.ripple(c, old ? PausaUi.PEACH_SOFT : PausaUi.CREAM_DEEP, 16));
        row.setCompoundDrawables(new PausaUi.Symbol(c, "reset", old ? PausaUi.TERRACOTTA : PausaUi.MUTED, 16), null, null, null);
        row.setCompoundDrawablePadding(dp(10));
        row.setOnClickListener(v -> show(BANKS));
        row.setContentDescription("Los datos de " + behind + " son de " + ago(now - when) + ". Ir a actualizar");
        return row;
    }

    // ------------------------------------------------------------ review inbox

    private View reviewPill(int count) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setBackground(PausaUi.ripple(c, PausaUi.SUN_SOFT, 20));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.addView(badge("spark", 0xFF9A6A12, 0x33E5AB35, 36), new LayoutParams(dp(36), dp(36)));
        LinearLayout labels = column();
        labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(PausaUi.text(c, "Por revisar", 15, PausaUi.INK, true), full());
        TextView hint = PausaUi.text(c, "Lo que Pausa no tiene claro. Cada respuesta vale para siempre.", 12, PausaUi.MUTED, false);
        hint.setPadding(0, dp(3), 0, 0);
        labels.addView(hint, full());
        row.addView(labels, new LayoutParams(0, -2, 1));
        TextView number = PausaUi.text(c, String.valueOf(count), 13, PausaUi.SURFACE, true);
        number.setGravity(Gravity.CENTER);
        number.setMinWidth(dp(30));
        number.setPadding(dp(8), dp(5), dp(8), dp(5));
        number.setBackground(PausaUi.surface(c, 0xFFC98A1C, 14));
        row.addView(number);
        row.setOnClickListener(v -> reviewSheet());
        row.setContentDescription("Por revisar: " + count + (count == 1 ? " duda" : " dudas"));
        return row;
    }

    interface Act { void run(Edit edit); }

    /** One question at a time; answers apply immediately and the last one can be undone from the sheet. */
    void reviewSheet() {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Por revisar");
        sheet.subtitle("Responde lo que sepas; lo demás puede esperar. Pausa recuerda cada respuesta.");
        sheet.tall();
        LinearLayout list = column();
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new android.widget.ScrollView.LayoutParams(-1, -2));
        sheet.body.addView(scroll, new LayoutParams(-1, 0, 1));
        final String[] last = {null};
        final Runnable[] fill = new Runnable[1];
        Button undo = PausaUi.quiet(c, "Deshacer", PausaUi.GREEN, () -> {
            if (last[0] == null) return;
            try { BudgetStore.save(c, Budget.Settings.fromJson(new JSONObject(last[0]))); }
            catch (Exception ignored) { return; }
            last[0] = null;
            recompute(false);
            fill[0].run();
        });
        undo.setVisibility(INVISIBLE);
        sheet.trailing(undo);
        Act act = edit -> {
            String before = commit(edit);
            if (before == null) return;
            last[0] = before;
            undo.setVisibility(VISIBLE);
            recompute(false);
            fill[0].run();
        };
        fill[0] = () -> {
            list.removeAllViews();
            List<Budget.Review> items = Budget.review(snapshot);
            sheet.title.setText(items.isEmpty() ? "Todo claro" : "Por revisar · " + items.size());
            if (items.isEmpty()) {
                TextView done = PausaUi.editorial(c, "No queda nada por revisar.", 20);
                done.setGravity(Gravity.CENTER);
                done.setCompoundDrawables(null, new PausaUi.Symbol(c, "check", PausaUi.SAGE, 32), null, null);
                done.setCompoundDrawablePadding(dp(12));
                done.setPadding(0, dp(40), 0, 0);
                list.addView(done, full());
                return;
            }
            for (Budget.Review item : items) list.addView(reviewCard(item, act, sheet), spaced(0, 10));
        };
        fill[0].run();
        sheet.show();
    }

    private View reviewCard(Budget.Review item, Act act, PausaUi.Sheet sheet) {
        Context c = getContext();
        LinearLayout card = column();
        card.setBackground(PausaUi.card(c));
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        Flow chips = new Flow(c);
        Budget.Entry entry = item.entry;
        String when = entry == null ? null : Budget.date(entry.txn.day) + " · " + entry.txn.sourceLabel;
        switch (item.type) {
            case Budget.REVIEW_INFLOW: {
                reviewHeader(card, "in", PausaUi.SAGE, "+" + Budget.money(entry.txn.cents, true) + " · " + entry.label(), when, "¿Qué es esta entrada de dinero?");
                chips.addView(answer(card, act, "Entre mis cuentas", "transfer", s -> { s.merchant.put(entry.merchantKey, "transfer"); s.txn.remove(entry.txn.id); }));
                chips.addView(answer(card, act, "Un ingreso", "in", s -> s.txn.put(entry.txn.id, "income")));
                chips.addView(answer(card, act, "Una devolución", "reset", s -> s.txn.put(entry.txn.id, "refund")));
                break;
            }
            case Budget.REVIEW_BIG: {
                reviewHeader(card, "umbrella", PausaUi.TERRACOTTA, Budget.money(entry.txn.cents, true) + " · " + entry.label(), when,
                        "Un gasto grande y puntual. ¿Fue un imprevisto?");
                chips.addView(answer(card, act, "Sí, imprevisto", "umbrella", s -> s.txn.put(entry.txn.id, "unexpected")));
                chips.addView(answer(card, act, "No, gasto libre", "check", s -> s.reviewed.add(entry.txn.id)));
                chips.addView(option("Otra cosa…", "tune", PausaUi.MUTED, false, () -> { sheet.dismiss(); classify(entry); }));
                break;
            }
            case Budget.REVIEW_UNKNOWN: {
                reviewHeader(card, "spark", PausaUi.MUTED, Budget.money(entry.txn.cents, true) + " · " + entry.label(), when, "No sé qué es. ¿Dónde lo pongo?");
                for (String id : new String[]{"compras", "comer", "ocio", "regalos", "personas", "transporte"}) {
                    Ledger.Category category = Ledger.category(id);
                    chips.addView(answer(card, act, category.label, category.symbol, s -> { s.merchant.put(entry.merchantKey, "cat:" + id); s.txn.remove(entry.txn.id); }));
                }
                chips.addView(answer(card, act, "Imprevisto", "umbrella", s -> s.txn.put(entry.txn.id, "unexpected")));
                chips.addView(answer(card, act, "Déjalo en Otros", "check", s -> s.reviewed.add(entry.txn.id)));
                chips.addView(option("Más…", "tune", PausaUi.MUTED, false, () -> { sheet.dismiss(); classify(entry); }));
                break;
            }
            case Budget.REVIEW_RECURRING: {
                Budget.Suggestion suggestion = item.suggestion;
                reviewHeader(card, Ledger.category(suggestion.category).symbol, 0xFF9A6A12, suggestion.name + " · " + Budget.money(suggestion.amount),
                        "Hacia el día " + suggestion.dayOfMonth + " · se ha repetido en " + suggestion.cycles + " ciclos", "¿Es un pago fijo?");
                chips.addView(answer(card, act, "Sí, es fijo", "check", s -> s.rules.add(suggestion.toRule())));
                chips.addView(answer(card, act, "No", "close", s -> s.dismissed.add(suggestion.key)));
                break;
            }
            default: {
                reviewHeader(card, "transfer", PausaUi.GREEN, "¿Son el mismo sitio?", null, null);
                card.addView(PausaUi.text(c, item.a.name + "  ·  " + item.a.count() + " mov.", 15, PausaUi.INK, false), spaced(4, 2));
                card.addView(PausaUi.text(c, item.b.name + "  ·  " + item.b.count() + " mov.", 15, PausaUi.INK, false), spaced(0, 2));
                chips.addView(answer(card, act, "Sí, unir", "check", s -> s.merge(item.a.key, item.b.key)));
                chips.addView(answer(card, act, "No", "close", s -> s.separate.add(Budget.mergeKey(item.a.key, item.b.key))));
            }
        }
        card.addView(chips, spaced(12, 0));
        return card;
    }

    private void reviewHeader(LinearLayout card, String symbol, int color, String title, String meta, String question) {
        Context c = getContext();
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(symbol, color, (color & 0x00FFFFFF) | 0x1F000000, 36), new LayoutParams(dp(36), dp(36)));
        LinearLayout labels = column();
        labels.setPadding(dp(12), 0, 0, 0);
        TextView name = PausaUi.text(c, title, 15, PausaUi.INK, true);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name, full());
        if (meta != null) labels.addView(PausaUi.text(c, meta, 12, PausaUi.MUTED, false), spaced(3, 0));
        top.addView(labels, new LayoutParams(0, -2, 1));
        card.addView(top, full());
        if (question != null) card.addView(PausaUi.text(c, question, 14, PausaUi.INK, false), spaced(12, 0));
    }

    private TextView answer(View card, Act act, String label, String symbol, Edit edit) {
        return option(label, symbol, PausaUi.GREEN, false, () -> PausaUi.fadeOutAndRun(card, () -> act.run(edit)));
    }

    // ------------------------------------------------------------ savings and cushion

    private void savings(Budget.Cycle cycle) {
        Context c = getContext();
        Budget.Settings settings = snapshot.settings;
        long atClose = cycle.savingsAtClose(), target = cycle.savingsTarget;
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.BOTTOM);
        TextView title = PausaUi.editorial(c, "Ahorro", 24);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LayoutParams(0, -2, 1));
        header.addView(PausaUi.text(c, target > 0 ? "objetivo " + Budget.money(target, false) + (settings.goal > 0 ? "" : " (habitual)") : "sin objetivo", 13, PausaUi.GREEN, true));
        month.addView(header, spaced(18, 10));

        // Where the cycle is heading.
        LinearLayout card = column();
        card.setBackground(PausaUi.card(c));
        card.setPadding(dp(16), dp(14), dp(14), dp(16));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge("seed", 0xFF3E6A73, 0x1F3E6A73, 40), new LayoutParams(dp(40), dp(40)));
        LinearLayout labels = column();
        labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(PausaUi.eyebrow(c, cycle.current ? "Al cerrar el ciclo, a este ritmo" : "Ahorrado ese ciclo", PausaUi.MUTED), full());
        TextView big = PausaUi.editorial(c, Budget.money(atClose, false), 26);
        big.setPadding(0, dp(4), 0, 0);
        labels.addView(big, full());
        top.addView(labels, new LayoutParams(0, -2, 1));
        TextView goal = PausaUi.text(c, settings.goal > 0 ? "Cambiar" : "Poner objetivo", 13, PausaUi.GREEN, true);
        goal.setGravity(Gravity.CENTER);
        goal.setMinHeight(dp(40));
        goal.setPadding(dp(12), 0, dp(12), 0);
        goal.setBackground(PausaUi.ripple(c, PausaUi.NEUTRAL, 18));
        goal.setContentDescription(settings.goal > 0 ? "Cambiar objetivo de ahorro" : "Poner objetivo de ahorro");
        goal.setOnClickListener(v -> editGoal());
        top.addView(goal);
        card.addView(top, full());

        MoneyMeters.GoalBar bar = new MoneyMeters.GoalBar(c);
        bar.set(Math.min(cycle.saved, atClose), atClose - Math.min(cycle.saved, atClose), target, animateNext);
        card.addView(bar, spaced(14, 6));
        String parts = "Apartado " + Budget.money(cycle.saved, false);
        if (cycle.invested > 0 && cycle.toCushion > 0) parts += " (" + Budget.money(cycle.invested, false) + " invertido, " + Budget.money(cycle.toCushion, false) + " al colchón)";
        else if (cycle.toCushion > 0) parts += " al colchón";
        else if (cycle.invested > 0) parts += " en Trade Republic";
        if (atClose > cycle.saved) parts += " · " + (cycle.current ? "lo que no gastes" : "sobró de lo libre") + " " + Budget.money(atClose - cycle.saved, false);
        else if (atClose < cycle.saved) parts += " · gastar por encima de lo libre " + (cycle.current ? "se comería " : "se comió ")
                + Budget.money(cycle.saved - atClose, false) + " de ese ahorro";
        if (target > 0) parts += " · marca: objetivo";
        TextView legend = PausaUi.text(c, parts, 12, PausaUi.MUTED, false);
        legend.setLineSpacing(0, 1.12f);
        card.addView(legend, full());

        TextView verdict = PausaUi.text(c, "", 14, PausaUi.INK, true);
        verdict.setLineSpacing(0, 1.12f);
        verdict.setCompoundDrawablePadding(dp(8));
        verdict.setGravity(Gravity.CENTER_VERTICAL);
        String advice;
        if (target <= 0) {
            verdict.setText("Sin objetivo de ahorro");
            advice = "Ponte uno: se aparta antes de calcular lo libre y Pausa te dice si llegas.";
        } else if (!cycle.current) {
            boolean met = atClose >= target;
            verdict.setText(met ? "Objetivo cumplido" : "Faltaron " + Budget.money(target - atClose, false));
            verdict.setTextColor(met ? PausaUi.SAGE : PausaUi.TERRACOTTA);
            verdict.setCompoundDrawables(new PausaUi.Symbol(c, met ? "check" : "pulse", met ? PausaUi.SAGE : PausaUi.TERRACOTTA, 18), null, null, null);
            advice = met ? "Ahorraste " + Budget.money(atClose, false) + " de " + Budget.money(target, false) + "." : "Lo libre se quedó corto ese ciclo.";
        } else if (cycle.shortfall() == 0) {
            verdict.setText("A este ritmo llegas a tu objetivo");
            verdict.setTextColor(PausaUi.SAGE);
            verdict.setCompoundDrawables(new PausaUi.Symbol(c, "check", PausaUi.SAGE, 18), null, null, null);
            advice = "Mientras tu gasto libre no pase de " + Budget.money(cycle.dailyAllowance(), false) + " al día, lo apartado está a salvo.";
        } else {
            verdict.setText("A este ritmo te faltarían " + Budget.money(cycle.shortfall(), false));
            verdict.setTextColor(PausaUi.TERRACOTTA);
            verdict.setCompoundDrawables(new PausaUi.Symbol(c, "pulse", PausaUi.TERRACOTTA, 18), null, null, null);
            advice = cycle.dailyAllowance() > 0 ? "Para llegar, gasta como mucho " + Budget.money(cycle.dailyAllowance(), false) + " al día durante los "
                    + cycle.left() + " días que quedan." : "Lo libre de este ciclo ya se ha gastado: solo queda lo apartado.";
        }
        card.addView(verdict, spaced(14, 4));
        TextView tip = PausaUi.text(c, advice, 13, PausaUi.MUTED, false);
        tip.setLineSpacing(0, 1.12f);
        card.addView(tip, full());
        if (cycle.gift > 0) {
            TextView gift = PausaUi.text(c, "+" + Budget.money(cycle.gift) + " de saveback: Trade Republic lo invierte y no sale de tu dinero", 12, PausaUi.SAGE, true);
            gift.setCompoundDrawables(new PausaUi.Symbol(c, "gift", PausaUi.SAGE, 16), null, null, null);
            gift.setCompoundDrawablePadding(dp(6));
            card.addView(gift, spaced(10, 0));
        }
        if (!cycle.savingEntries.isEmpty()) card.setOnClickListener(v -> listSheet("Ahorro", Budget.money(cycle.invested) + " invertidos este ciclo", cycle.savingEntries));
        month.addView(card, spaced(0, 10));

        // The rhythm across cycles.
        long[] history = Budget.savingsHistory(snapshot, cycle.index, 6);
        if (history.length > 1) {
            LinearLayout rhythm = column();
            rhythm.setBackground(PausaUi.card(c));
            rhythm.setPadding(dp(16), dp(14), dp(16), dp(14));
            long sum = 0;
            for (int i = 0; i < history.length - (cycle.current ? 1 : 0); i++) sum += history[i];
            LinearLayout line = new LinearLayout(c);
            line.addView(PausaUi.text(c, "Ahorro por ciclo", 14, PausaUi.INK, true), new LayoutParams(0, -2, 1));
            line.addView(PausaUi.text(c, Budget.money(sum, false) + " en ciclos cerrados", 12, PausaUi.MUTED, false));
            rhythm.addView(line, full());
            String[] names = new String[history.length];
            int from = cycle.index - history.length + 1;
            for (int i = 0; i < history.length; i++)
                names[i] = cycle.current && i == history.length - 1 ? "ahora" : Budget.MONTHS[Ledger.civil(snapshot.periods.get(from + i).start + 15)[1] - 1];
            MoneyMeters.HistoryBars bars = new MoneyMeters.HistoryBars(c);
            bars.set(history, names, 0xFF3E6A73);
            bars.setContentDescription("Ahorro de los últimos ciclos");
            rhythm.addView(bars, spaced(6, 0));
            month.addView(rhythm, spaced(0, 10));
        }
        if (holdings != null && !holdings.empty() && holdings.value > 0) {
            boolean up = holdings.gain() >= 0;
            View link = settingRow("Cartera", Budget.money(holdings.value, false) + " · " + Portfolio.percent(holdings.gainRatio()), () -> show(PORTFOLIO));
            ((TextView) ((LinearLayout) link).getChildAt(1)).setTextColor(up ? PausaUi.SAGE : PausaUi.TERRACOTTA);
            link.setContentDescription("Cartera: " + Budget.money(holdings.value, false) + ", " + Portfolio.percent(holdings.gainRatio()) + ". Abrir");
            month.addView(link, spaced(0, 4));
        }
        cushion(cycle);
    }

    /** The cushion: money already set aside for obligations that don't repeat, and what came in and out of it. */
    private void cushion(Budget.Cycle cycle) {
        Context c = getContext();
        Budget.Settings settings = snapshot.settings;
        boolean set = cycle.cushionStart >= 0;
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.BOTTOM);
        TextView title = PausaUi.editorial(c, "Colchón", 24);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LayoutParams(0, -2, 1));
        if (set) header.addView(PausaUi.text(c, Budget.money(cycle.cushionLeft(), false) + (settings.cushionGoal > 0 ? " de " + Budget.money(settings.cushionGoal, false) : ""), 13, PausaUi.GREEN, true));
        month.addView(header, spaced(18, 10));

        LinearLayout card = column();
        card.setBackground(PausaUi.surface(c, PausaUi.CREAM_DEEP, 22));
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge("umbrella", PausaUi.TERRACOTTA, PausaUi.PEACH_SOFT, 40), new LayoutParams(dp(40), dp(40)));
        TextView explain = PausaUi.text(c, "Dinero apartado para lo obligatorio que no se repite: el coche, el dentista. Los imprevistos salen de aquí, "
                + "no de tu día a día. Lo que añades cuenta como ahorro del ciclo.", 13, PausaUi.MUTED, false);
        explain.setLineSpacing(0, 1.15f);
        explain.setPadding(dp(12), 0, 0, 0);
        top.addView(explain, new LayoutParams(0, -2, 1));
        card.addView(top, full());
        if (!set) {
            Button start = PausaUi.action(c, "Empezar mi colchón", true, () -> editCushion(cycle));
            card.addView(start, spaced(14, 0));
            month.addView(card, spaced(0, 4));
            return;
        }
        TextView balance = PausaUi.editorial(c, Budget.money(cycle.cushionLeft(), false), 26);
        balance.setPadding(0, dp(14), 0, 0);
        card.addView(balance, full());
        if (settings.cushionGoal > 0) {
            MoneyMeters.Share share = new MoneyMeters.Share(c, PausaUi.TERRACOTTA, Math.min(1f, cycle.cushionLeft() / (float) settings.cushionGoal));
            if (animateNext) share.animateIn(300);
            card.addView(share, spaced(10, 4));
            long missing = settings.cushionGoal - cycle.cushionLeft();
            card.addView(PausaUi.text(c, missing > 0 ? "Faltan " + Budget.money(missing, false) + " para tu objetivo de " + Budget.money(settings.cushionGoal, false)
                    : "Objetivo de " + Budget.money(settings.cushionGoal, false) + " cubierto", 12, PausaUi.MUTED, false), full());
        }
        Flow actions = new Flow(c);
        actions.addView(option("Añadir dinero", "plus", PausaUi.GREEN, false, this::addToCushion));
        actions.addView(option("Cambiar saldo", "edit", PausaUi.GREEN, false, () -> editCushion(cycle)));
        actions.addView(option(settings.cushionGoal > 0 ? "Objetivo" : "Poner objetivo", "spark", PausaUi.GREEN, false, this::editCushionGoal));
        card.addView(actions, spaced(14, 4));

        // Recent comings and goings.
        List<Object[]> moves = new ArrayList<>();
        int from = settings.cushionStart();
        for (long[] move : settings.cushionMoves) if (move[0] >= from) moves.add(new Object[]{(int) move[0], move[1], "Añadido al colchón", null});
        for (Budget.Entry entry : snapshot.entries)
            if (entry.unexpected && entry.txn.day >= from) moves.add(new Object[]{entry.txn.day, entry.txn.cents, entry.note != null ? entry.note : entry.label(), entry});
        java.util.Collections.sort(moves, (a, b) -> Integer.compare((Integer) b[0], (Integer) a[0]));
        if (moves.isEmpty()) {
            card.addView(note("¿Algo obligatorio que no se repite? Márcalo como imprevisto desde Movimientos y saldrá de aquí."), spaced(8, 0));
        } else {
            card.addView(PausaUi.eyebrow(c, "Últimos movimientos del colchón", PausaUi.MUTED), spaced(10, 4));
            for (int i = 0; i < Math.min(5, moves.size()); i++) {
                Object[] move = moves.get(i);
                long cents = (Long) move[1];
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(40));
                row.setPadding(dp(4), 0, dp(4), 0);
                TextView label = PausaUi.text(c, Budget.date((Integer) move[0]) + " · " + move[2], 14, PausaUi.INK, false);
                label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(label, new LayoutParams(0, -2, 1));
                row.addView(PausaUi.text(c, (cents > 0 ? "+" : "") + Budget.money(cents), 14, cents > 0 ? PausaUi.SAGE : PausaUi.TERRACOTTA, true));
                if (move[3] != null) {
                    Budget.Entry entry = (Budget.Entry) move[3];
                    row.setBackground(PausaUi.ripple(c, Color.TRANSPARENT, 12));
                    row.setOnClickListener(v -> classify(entry));
                }
                card.addView(row, full());
            }
        }
        month.addView(card, spaced(0, 4));
    }

    private void editGoal() {
        long goal = snapshot.settings.goal;
        PausaUi.numberSheet(getContext(), "Objetivo de ahorro", "Euros por ciclo. Se apartan antes de calcular lo libre. "
                        + "Con 0 se usa lo que sueles apartar en Trade Republic.", (int) (goal / 100), 0, "Introduce euros enteros",
                value -> change(value == 0 ? "Sin objetivo de ahorro" : "Objetivo: " + Budget.money(value * 100L, false) + " por ciclo", s -> s.goal = value * 100L));
    }

    /** Sets what the cushion holds today, counting from this cycle's start. */
    private void editCushion(Budget.Cycle cycle) {
        Budget.Cycle open = Budget.cycle(snapshot, snapshot.last());
        long left = Math.max(0, open.cushionLeft());
        int start = open.period.start;
        long spentHere = open.unexpected, addedHere = open.toCushion;
        PausaUi.numberSheet(getContext(), "Saldo del colchón", "Lo que tienes apartado hoy para gastos obligatorios que no se repiten. "
                        + "Los imprevistos que marques lo irán gastando.", (int) (left / 100), 0, "Introduce euros enteros",
                value -> change("Colchón: " + Budget.money(value * 100L, false), s -> {
                    // Counted from this cycle's start: imprevistos and contributions already here stay in the picture.
                    s.cushion = value * 100L + spentHere - addedHere; s.cushionDay = start;
                }));
    }

    private void addToCushion() {
        PausaUi.numberSheet(getContext(), "Añadir al colchón", "Euros que acabas de apartar para imprevistos. Cuentan como ahorro de este ciclo.",
                -1, 1, "Introduce euros enteros", value -> change("+" + Budget.money(value * 100L, false) + " al colchón",
                        s -> s.cushionMoves.add(new long[]{today(), value * 100L})));
    }

    private void editCushionGoal() {
        PausaUi.numberSheet(getContext(), "Objetivo del colchón", "Cuánto quieres tener apartado para imprevistos. Con 0, sin objetivo.",
                (int) (snapshot.settings.cushionGoal / 100), 0, "Introduce euros enteros",
                value -> change(value == 0 ? "Colchón sin objetivo" : "Objetivo del colchón: " + Budget.money(value * 100L, false), s -> s.cushionGoal = value * 100L));
    }

    private void unexpectedSheet(Budget.Entry entry) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Imprevisto");
        sheet.subtitle(entry.label() + " · " + Budget.money(entry.txn.cents, true) + ". Obligatorio pero puntual: sale del colchón mientras quede y no cuenta en tu ritmo diario.");
        EditText note = field(sheet, "¿Qué fue? (opcional)", entry.note == null ? "" : entry.note, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setHint("Por ejemplo, reparación del coche");
        sheet.footer(PausaUi.quiet(c, "Cancelar", PausaUi.MUTED, sheet::dismiss), PausaUi.action(c, "Guardar", true, () -> {
            String text = note.getText().toString().trim();
            sheet.dismiss();
            change(entry.label() + " → imprevisto", s -> {
                s.txn.put(entry.txn.id, "unexpected");
                if (text.isEmpty()) s.notes.remove(entry.txn.id); else s.notes.put(entry.txn.id, text);
            });
        }));
        TaskSheets.showWithKeyboard(sheet);
    }

    // ------------------------------------------------------------ merchants

    /** Everything about one place, across banks: totals, rhythm, its name and which spellings are the same place. */
    private void merchantSheet(String key) {
        Context c = getContext();
        Budget.Merchant merchant = Budget.merchants(snapshot).get(key);
        if (merchant == null) return;
        int first = Integer.MAX_VALUE;
        for (Budget.Entry entry : merchant.entries) first = Math.min(first, entry.txn.day);
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, merchant.name);
        sheet.subtitle(merchant.count() + (merchant.count() == 1 ? " movimiento" : " movimientos") + " · " + Budget.money(merchant.spent)
                + " en total · desde el " + Budget.date(first));
        int from = Math.max(snapshot.first(), snapshot.last() - 5), n = snapshot.last() - from + 1;
        long[] values = new long[n];
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            Budget.Period period = snapshot.periods.get(from + i);
            for (Budget.Entry entry : merchant.entries) if (period.contains(entry.txn.day) && entry.counts()) values[i] -= entry.txn.cents;
            labels[i] = from + i == snapshot.last() ? "ahora" : Budget.MONTHS[Ledger.civil(period.start + 15)[1] - 1];
        }
        MoneyMeters.HistoryBars bars = new MoneyMeters.HistoryBars(c);
        bars.set(values, labels, Ledger.category(merchant.category).color);
        bars.setContentDescription("Gasto en " + merchant.name + " por ciclo");
        sheet.add(bars, 12);

        EditText name = field(sheet, "Nombre", merchant.name, InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        List<PausaUi.Check> checks = new ArrayList<>();
        List<Budget.Merchant> alike = Budget.lookalikes(snapshot, key, .35);
        if (!alike.isEmpty()) {
            sheet.add(PausaUi.eyebrow(c, "¿Es el mismo sitio que…?", PausaUi.MUTED), 6);
            for (Budget.Merchant other : alike) {
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(PausaUi.ripple(c, Color.TRANSPARENT, 16));
                PausaUi.Check check = new PausaUi.Check(c, PausaUi.SAGE);
                check.setChecked(other.similarity >= .6);
                check.setContentDescription("Unir " + other.name);
                checks.add(check);
                row.addView(check, new LayoutParams(dp(48), dp(48)));
                LinearLayout text = column();
                text.addView(PausaUi.text(c, other.name, 15, PausaUi.INK, false), full());
                text.addView(PausaUi.text(c, other.count() + " mov. · " + Budget.money(other.spent), 12, PausaUi.MUTED, false), full());
                row.addView(text, new LayoutParams(0, -2, 1));
                row.setOnClickListener(v -> check.performClick());
                sheet.add(row, 0);
            }
        }
        Map<String, String> spelled = new java.util.HashMap<>();
        for (Budget.Entry entry : snapshot.entries) spelled.put(entry.txn.merchantKey, entry.txn.merchant);
        for (Map.Entry<String, String> alias : snapshot.settings.alias.entrySet()) {
            if (!alias.getValue().equals(key)) continue;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, 0, 0);
            String readable = spelled.containsKey(alias.getKey()) ? spelled.get(alias.getKey()) : alias.getKey();
            row.addView(PausaUi.text(c, "Unido: " + readable, 14, PausaUi.MUTED, false), new LayoutParams(0, -2, 1));
            row.addView(PausaUi.quiet(c, "Separar", PausaUi.TERRACOTTA, () -> {
                sheet.dismiss();
                change(readable + " vuelve a ir por separado", s -> { s.split(alias.getKey()); s.separate.add(Budget.mergeKey(key, alias.getKey())); });
            }));
            sheet.add(row, 0);
        }
        sheet.add(PausaUi.eyebrow(c, "Movimientos", PausaUi.MUTED), 4);
        int shown = 0;
        for (Budget.Entry entry : merchant.entries) {
            if (shown++ == 20) break;
            sheet.add(entryRow(entry, () -> { sheet.dismiss(); classify(entry); }), 0);
        }
        sheet.footer(PausaUi.quiet(c, "Cerrar", PausaUi.MUTED, sheet::dismiss), PausaUi.action(c, "Guardar", true, () -> {
            String renamed = name.getText().toString().trim();
            List<String> merged = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) if (checks.get(i).isChecked()) merged.add(alike.get(i).key);
            sheet.dismiss();
            if ((renamed.isEmpty() || renamed.equals(merchant.name)) && merged.isEmpty()) return;
            String shownName = renamed.isEmpty() ? merchant.name : renamed;
            change("«" + shownName + "»" + (merged.isEmpty() ? " guardado" : " · " + (merged.size() + 1) + " nombres unidos"), s -> {
                if (!renamed.isEmpty() && !renamed.equals(merchant.name)) s.names.put(key, renamed);
                for (String other : merged) s.merge(key, other);
            });
        }));
        sheet.show();
    }

    // ------------------------------------------------------------ fixed

    private void fixed(Budget.Cycle cycle) {
        Context c = getContext();
        android.content.SharedPreferences ui = c.getSharedPreferences("budget_ui", Context.MODE_PRIVATE);
        boolean collapsed = ui.getBoolean("fixed_collapsed", false);
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        header.setBackground(PausaUi.ripple(c, Color.TRANSPARENT, 16));
        TextView title = PausaUi.editorial(c, "Lo fijo", 24);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LayoutParams(0, -2, 1));
        header.addView(PausaUi.text(c, cycle.paidLines() + " de " + cycle.activeLines() + " pagados", 13, PausaUi.GREEN, true));
        ImageView chevron = new ImageView(c);
        chevron.setImageDrawable(new PausaUi.Symbol(c, "down", PausaUi.GREEN, 18));
        chevron.setRotation(collapsed ? 0 : 180);
        LayoutParams cp = new LayoutParams(dp(28), dp(28));
        cp.leftMargin = dp(6);
        header.addView(chevron, cp);
        month.addView(header, spaced(12, 0));
        String reserved = Budget.money(cycle.fixed(), false) + " reservados";
        if (cycle.fixedPending > 0) reserved += " · faltan " + Budget.money(cycle.fixedPending, false);
        month.addView(PausaUi.text(c, reserved, 13, PausaUi.MUTED, false), spaced(0, 10));
        MoneyMeters.StatusDots dots = new MoneyMeters.StatusDots(c);
        int[] statuses = new int[cycle.lines.size()];
        for (int i = 0; i < statuses.length; i++) statuses[i] = cycle.lines.get(i).status;
        dots.set(statuses, animateNext);
        month.addView(dots, spaced(0, 12));
        Budget.Line next = null;
        for (Budget.Line line : cycle.lines) if (!line.done() && (next == null || line.due < next.due)) next = line;
        View nextRow = null;
        if (next != null && cycle.current) {
            final Budget.Line upcoming = next;
            nextRow = settingRow("Próximo: " + next.rule.name, Budget.money(next.expected) + " · " + pill(next).toLowerCase(PausaUi.SPANISH),
                    () -> lineSheet(cycle, upcoming));
            nextRow.setVisibility(collapsed ? VISIBLE : GONE);
            month.addView(nextRow, spaced(0, 8));
        }

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
        grid.setVisibility(collapsed ? GONE : VISIBLE);
        final View summary = nextRow;
        header.setContentDescription(collapsed ? "Mostrar todos los pagos fijos" : "Encoger los pagos fijos");
        header.setOnClickListener(v -> {
            boolean fold = grid.getVisibility() == VISIBLE;
            ui.edit().putBoolean("fixed_collapsed", fold).apply();
            grid.setVisibility(fold ? GONE : VISIBLE);
            if (summary != null) summary.setVisibility(fold ? VISIBLE : GONE);
            header.setContentDescription(fold ? "Mostrar todos los pagos fijos" : "Encoger los pagos fijos");
            if (PausaUi.motion(c)) chevron.animate().rotation(fold ? 0 : 180).setDuration(260).setInterpolator(PausaUi.EASE).start();
            else chevron.setRotation(fold ? 0 : 180);
            if (!fold) PausaUi.stagger(grid, 8);
        });
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
        boolean quiet = status == Budget.UPCOMING || status == Budget.RESERVED;
        top.addView(badge(line.rule.symbol, accent, quiet ? PausaUi.NEUTRAL : 0x99FFFCF6, 38), new LayoutParams(dp(38), dp(38)));
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
            pill.setBackground(PausaUi.surface(c, quiet ? PausaUi.NEUTRAL : 0x99FFFCF6, 8));
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
            case Budget.RESERVED: return Budget.frequency(line.rule.frequency);
            default: return Budget.date(line.due);
        }
    }

    private static String statusLine(Budget.Cycle cycle, Budget.Line line) {
        switch (line.status) {
            case Budget.RESERVED:
                return "Apartas " + Budget.money(line.reserve()) + " · próximo hacia el " + Budget.date(line.due);
            case Budget.PAID: {
                String text = "Pagado el " + Budget.date(line.paidDay) + (line.rule.frequency > 1 ? " · " + Budget.frequency(line.rule.frequency).toLowerCase(PausaUi.SPANISH) : "");
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
            default: return "Hacia el " + Budget.date(line.due) + (line.planned ? " · ajustado" : "");
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
        if (cycle.current && line.status != Budget.SKIPPED) {
            String how = line.planned ? "fijado para este ciclo" : line.rule.learn ? "según lo que sueles pagar" : "importe fijo";
            View amount = settingRow("Este ciclo", Budget.money(line.expected) + " · " + how, () -> { sheet.dismiss(); amountSheet(cycle, line); });
            amount.setContentDescription("Cambiar el importe de " + line.rule.name + " este ciclo: " + Budget.money(line.expected));
            sheet.add(amount, 12);
        }
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

    /** How much a fixed line is this cycle, or from now on. Quick choices come from what was actually paid. */
    private void amountSheet(Budget.Cycle cycle, Budget.Line line) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, line.rule.name);
        sheet.subtitle("¿Cuánto toca este ciclo? Lo que aún no haya salido de tus cuentas queda reservado hasta que salga.");
        EditText input = new EditText(c);
        PausaUi.input(input);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setTextSize(28);
        input.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        input.setSingleLine(true);
        input.setText(euros(line.expected));
        input.setContentDescription("Importe en euros");
        input.selectAll();
        sheet.add(input, 10);
        List<Long> usual = Budget.usualAmounts(line);
        if (!usual.contains(line.expected)) usual.add(0, line.expected);
        if (usual.size() > 1) {
            Flow quick = new Flow(c);
            for (long cents : usual) {
                TextView chip = PausaUi.amountChip(c, Budget.money(cents), PausaUi.GREEN, PausaUi.SAGE_SOFT, "Usar " + Budget.money(cents), () -> {
                    input.setText(euros(cents)); input.setSelection(input.getText().length());
                });
                chip.setPadding(dp(14), dp(8), dp(14), dp(8));
                quick.addView(chip);
            }
            sheet.add(PausaUi.text(c, "Lo que has pagado otras veces", 12, PausaUi.MUTED, true), 6);
            sheet.add(quick, 14);
        }
        final int[] scope = {0};
        PausaUi.Segmented when = new PausaUi.Segmented(c, new String[]{"Solo este ciclo", "Desde ahora"}, i -> {
            scope[0] = i;
        });
        sheet.add(when, 6);
        when.post(() -> when.select(0, false));
        TextView explain = note("«Desde ahora» fija el importe y deja de ajustarlo a lo que se cobre.");
        sheet.add(explain, 4);
        String key = Budget.skipKey(line.rule, cycle.period);
        Button clear = line.planned ? PausaUi.quiet(c, "Quitar el ajuste", PausaUi.TERRACOTTA, () -> {
            sheet.dismiss();
            change(line.rule.name + ": vuelve a lo habitual", s -> s.planned.remove(key));
        }) : null;
        sheet.footer(clear, PausaUi.action(c, "Guardar", true, () -> {
            long cents;
            try {
                cents = Ledger.cents(input.getText().toString().trim().replace(".", "").replace(",", "."));
                if (cents <= 0) throw new NumberFormatException();
            } catch (Exception error) { input.setError("Introduce un importe, por ejemplo 550"); return; }
            sheet.dismiss();
            boolean always = scope[0] == 1;
            change(line.rule.name + ": " + Budget.money(cents) + (always ? " desde ahora" : " este ciclo"), s -> {
                Budget.Rule rule = s.rule(line.rule.id);
                if (always && rule != null) { rule.expected = cents; rule.learn = false; s.planned.remove(key); }
                else s.planned.put(key, cents);
            });
        }));
        TaskSheets.showWithKeyboard(sheet);
        input.requestFocus();
    }

    private View settingRow(String label, String value, Runnable action) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), 0, dp(12), 0);
        row.setBackground(PausaUi.ripple(c, PausaUi.CREAM, 18));
        row.addView(PausaUi.text(c, label, 15, PausaUi.INK, false), new LayoutParams(0, -2, 1));
        TextView amount = PausaUi.text(c, value, 14, PausaUi.MUTED, true);
        amount.setCompoundDrawables(null, null, new PausaUi.Symbol(c, "chevron", PausaUi.MUTED, 18), null);
        amount.setCompoundDrawablePadding(dp(6));
        row.addView(amount, new LayoutParams(-2, -2));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** Editable euros: "550" or "14,99". */
    private static String euros(long cents) {
        return cents % 100 == 0 ? Long.toString(cents / 100) : (cents / 100) + "," + (cents % 100 < 10 ? "0" : "") + cents % 100;
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
        row.setOnClickListener(v -> merchantSheet(cycle.habit.key));
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
        long lastSync = Math.max(tradeSync, abancaSync);
        String when = lastSync <= 0 ? "Sin sincronizaciones completas todavía" : "Actualizado " + ago(System.currentTimeMillis() - lastSync);
        TextView text = PausaUi.text(c, "ABANCA y Trade Republic, leídos desde tu teléfono.\n" + when + (failed ? " · no se pudo leer la base de datos" : ""), 12, PausaUi.MUTED, false);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(0, 1.2f);
        month.addView(text, spaced(14, 0));
        Button sync = PausaUi.quiet(c, "Sincronizar bancos", PausaUi.GREEN, () -> show(BANKS));
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
        Button go = PausaUi.action(c, "Ir a Bancos", true, () -> show(BANKS));
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
            case 3: return entry.saving();
            case 4: return entry.unexpected;
            case 5: return entry.txn.cents > 0 && entry.counts() && !entry.saving();
            case 6: return !entry.counts();
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
        int color = entry.unexpected ? PausaUi.TERRACOTTA : entry.saving() ? 0xFF3E6A73 : entry.fixed() ? PausaUi.GREEN
                : entry.kind == Ledger.Kind.INCOME ? PausaUi.SAGE : entry.free() ? Ledger.category(entry.category).color : PausaUi.MUTED;
        String symbol = entry.unexpected ? "umbrella" : entry.saving() ? "seed" : entry.fixed() ? entry.rule.symbol
                : entry.kind == Ledger.Kind.INCOME ? "in" : entry.free() ? Ledger.category(entry.category).symbol : entry.txn.reward ? "gift" : "transfer";
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
        if (!entry.txn.salary) row.setOnLongClickListener(v -> { merchantSheet(entry.merchantKey); return true; });
        row.setContentDescription(label(entry) + ", " + Budget.money(cents, true) + ", " + tag(entry));
        return row;
    }

    private static String label(Budget.Entry entry) { return entry.label(); }

    private static String tag(Budget.Entry entry) {
        String learned = entry.taught.equals("merchant") ? " · recordado" : entry.taught.equals("txn") ? " · a mano" : "";
        if (entry.txn.duplicate && entry.taught.isEmpty()) return "Repetido en la cuenta";
        if (entry.unexpected) return "Imprevisto" + (entry.note == null ? "" : " · " + entry.note) + learned;
        if (entry.saving()) return "Ahorro" + learned;
        if (entry.fixed()) return "Fijo · " + entry.rule.name + learned;
        if (entry.free()) return Ledger.category(entry.category).label + learned;
        switch (entry.kind) {
            case INCOME: return (entry.txn.salary ? "Nómina" : "Ingreso") + learned;
            case IGNORED: return entry.txn.reward ? "Saveback · regalo de Trade Republic" : "No cuenta" + learned;
            case TRANSFER: return (entry.txn.cents > 0 ? "Entrada entre tus cuentas" : "Entre tus cuentas") + learned;
            default: return "No cuenta" + learned;
        }
    }

    // ------------------------------------------------------------ teaching

    interface Edit { void apply(Budget.Settings settings) throws Exception; }

    /** Every correction is saved immediately and can be undone from the snackbar. */
    private void change(String message, Edit edit) {
        Context c = getContext();
        String before = commit(edit);
        if (before == null) return;
        recompute(false);
        PausaUi.snack(c, message, "Deshacer", () -> {
            try { BudgetStore.save(c, Budget.Settings.fromJson(new JSONObject(before))); recompute(false); }
            catch (Exception ignored) { /* The previous copy was produced by this same code. */ }
        });
    }

    /** Saves one correction and returns the settings as they were, or null if nothing could be saved. */
    private String commit(Edit edit) {
        Context c = getContext();
        Budget.Settings settings = BudgetStore.load(c);
        String before;
        try { before = settings.toJson().toString(); edit.apply(settings); }
        catch (Exception error) { PausaUi.snack(c, "No se pudo guardar el cambio.", null, null); return null; }
        BudgetStore.save(c, settings);
        return before;
    }

    private void classify(Budget.Entry entry) {
        Context c = getContext();
        Ledger.Txn txn = entry.txn;
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, entry.label());
        sheet.subtitle(PausaUi.capitalize(Budget.WEEKDAYS[Ledger.weekday(txn.day)]) + " " + Budget.date(txn.day) + " · " + txn.sourceLabel
                + " · " + Budget.money(txn.cents, true) + "\n" + txn.raw.trim());
        if (!txn.salary) {
            Button page = PausaUi.quiet(c, "Ficha de «" + entry.merchant + "» · renombrar o unir", PausaUi.GREEN, () -> { sheet.dismiss(); merchantSheet(entry.merchantKey); });
            page.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            page.setCompoundDrawables(new PausaUi.Symbol(c, "edit", PausaUi.GREEN, 16), null, null, null);
            page.setCompoundDrawablePadding(dp(8));
            page.setPadding(dp(4), 0, dp(4), 0);
            sheet.add(page, 8);
        }
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
        if (txn.cents < 0) {
            other.addView(option("Imprevisto", "umbrella", PausaUi.TERRACOTTA, "unexpected".equals(current), () -> { sheet.dismiss(); unexpectedSheet(entry); }));
            other.addView(option("Es ahorro", "seed", 0xFF3E6A73, "saving".equals(current), () -> teach(sheet, entry, "saving", remember.isChecked(), "Ahorro")));
        }
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
        remember.setContentDescription("Recordar para " + entry.merchant);
        rememberRow.addView(remember, new LayoutParams(dp(48), dp(48)));
        rememberRow.addView(PausaUi.text(c, "Recordar para todo lo de «" + entry.merchant + "»", 14, PausaUi.INK, false), new LayoutParams(0, -2, 1));
        rememberRow.setOnClickListener(v -> remember.performClick());
        if (!txn.salary) sheet.add(rememberRow, 4);
        Button reset = !entry.taught.isEmpty() ? PausaUi.quiet(c, "Volver a lo automático", PausaUi.TERRACOTTA, () -> {
            sheet.dismiss();
            change("Vuelve a clasificarse solo", s -> {
                s.txn.remove(txn.id); s.notes.remove(txn.id);
                if (entry.taught.equals("merchant")) { s.merchant.remove(entry.merchantKey); s.merchant.remove(txn.merchantKey); }
            });
        }) : null;
        sheet.footer(reset, PausaUi.action(c, "Cerrar", false, sheet::dismiss));
        sheet.show();
    }

    private static String currentCode(Budget.Entry entry) {
        if (entry.unexpected) return "unexpected";
        if (entry.saving()) return "saving";
        if (entry.fixed()) return "rule:" + entry.rule.id;
        if (entry.free()) return entry.kind == Ledger.Kind.REFUND && entry.txn.cents > 0 ? "refund" : "cat:" + entry.category;
        if (entry.kind == Ledger.Kind.INCOME) return "income";
        return "transfer";
    }

    private void teach(PausaUi.Sheet sheet, Budget.Entry entry, String code, boolean remember, String label) {
        sheet.dismiss();
        Ledger.Txn txn = entry.txn;
        change(remember ? "«" + entry.merchant + "» → " + label : "Movimiento → " + label, s -> {
            if (remember) { s.merchant.put(entry.merchantKey, code); s.txn.remove(txn.id); }
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

    private static final String[] RULE_SYMBOLS = {"home", "heart", "doc", "card", "shield", "dumbbell", "play", "music", "phone", "box", "note", "spark", "train", "people", "cash", "umbrella"};

    /** Create or edit a fixed line. With a movement, it starts from that movement's merchant and amount. */
    private void ruleEditor(Budget.Rule existing, Budget.Entry from) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, existing == null ? "Nuevo pago fijo" : "Editar pago fijo");
        sheet.subtitle("Pausa lo reconoce cuando el concepto del movimiento contiene alguno de los textos. El importe y el día se ajustan solos con lo que se cobra.");
        EditText name = field(sheet, "Nombre", existing != null ? existing.name : from != null ? from.merchant : "", InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        long amountCents = existing != null ? existing.expected : from != null ? Math.abs(from.txn.cents) : 0;
        EditText amount = field(sheet, "Importe habitual en euros", amountCents > 0 ? Budget.money(amountCents, true).replace(" €", "").replace(".", "") : "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int dayValue = existing != null ? existing.day : from != null ? Ledger.civil(from.txn.day)[2] : 1;
        EditText day = field(sheet, "Día aproximado del mes", String.valueOf(dayValue), InputType.TYPE_CLASS_NUMBER);
        String words = existing != null ? TextUtils.join(", ", existing.keywords) : from != null ? from.merchantKey : "";
        EditText keywords = field(sheet, "Texto del concepto (separa con comas)", words, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if (existing != null && existing.invest) keywords.setEnabled(false);
        final int[] months = {existing != null ? existing.frequency : 1};
        int[] options = {1, 3, 12};
        sheet.add(PausaUi.text(c, "Cada cuánto se cobra", 12, PausaUi.MUTED, true), 6);
        PausaUi.Segmented every = new PausaUi.Segmented(c, new String[]{"Mensual", "Trimestral", "Anual"}, i -> {
            months[0] = options[i];
        });
        sheet.add(every, 12);
        final PausaUi.Segmented frequency = every;
        frequency.post(() -> frequency.select(months[0] >= 12 ? 2 : months[0] >= 3 ? 1 : 0, false));
        PausaUi.Check learn = new PausaUi.Check(c, PausaUi.SAGE);
        learn.setChecked(existing == null || existing.learn);
        learn.setContentDescription("Ajustar el importe a lo que se cobra");
        LinearLayout learnRow = new LinearLayout(c);
        learnRow.setGravity(Gravity.CENTER_VERTICAL);
        learnRow.setBackground(PausaUi.ripple(c, PausaUi.CREAM, 18));
        learnRow.setPadding(dp(4), 0, dp(14), 0);
        learnRow.addView(learn, new LayoutParams(dp(48), dp(48)));
        learnRow.addView(PausaUi.text(c, "Ajustar el importe a lo que se cobra", 14, PausaUi.INK, false), new LayoutParams(0, -2, 1));
        learnRow.setOnClickListener(v -> learn.performClick());
        sheet.add(learnRow, 12);
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
                rule.name = title; rule.symbol = symbol[0]; rule.expected = cents; rule.day = dayOfMonth; rule.frequency = months[0];
                rule.learn = learn.isChecked();
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
