package com.sejio.calorapp;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cartera: the Trade Republic holdings with their cost, last known value and history, plus what the owner
 * contributes. Prices are the last ones received at a valuation; nothing here is a sale price.
 */
final class PortfolioView extends PausaUi.Scroll implements TradeRepository.Listener {
    interface Host { void reload(); void openBanks(); }

    private final Host host;
    private final LinearLayout root;
    private final TradeRepository trade;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Portfolio.View view;
    private Budget.Snapshot snapshot;
    private MoneyMeters.Working working;
    private TextView progress;
    private Button update;
    private boolean watching, requested, wasBusy, busy, animate = true;

    PortfolioView(Context context, Host host) {
        super(context);
        this.host = host;
        trade = TradeRepository.get(context);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(6), dp(20), 0);
        addView(root, new LayoutParams(-1, -2));
    }

    void show(Portfolio.View view, Budget.Snapshot snapshot) {
        this.view = view; this.snapshot = snapshot;
        render();
    }

    void watch() { watching = true; trade.observe(this); animate = true; render(); }

    void stop() { watching = false; trade.detach(this); }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    @Override public void changed(JSONObject state, String error, boolean nowBusy) {
        if (!watching) return;
        boolean finished = wasBusy && !nowBusy;
        wasBusy = nowBusy; busy = nowBusy;
        if (working != null) {
            working.setVisibility(busy ? VISIBLE : GONE);
            update.setEnabled(!busy);
            update.setText(busy ? "Leyendo tu cartera…" : "Actualizar valoración");
            String code = BankSyncView.codeOf(error == null ? "" : error);
            progress.setText(busy ? trade.progress() : error != null && !error.isEmpty() ? BankSyncView.plain(error)
                    + (code.equals("AUTH") || code.equals("NO_PROCESS") ? " Conéctalo desde Bancos." : "") : stamp());
            progress.setTextColor(!busy && error != null && !error.isEmpty() ? PausaUi.TERRACOTTA : PausaUi.MUTED);
        }
        if (finished && requested) { requested = false; if (error == null || error.isEmpty()) { animate = true; host.reload(); } }
    }

    private void refresh() {
        JSONObject state = trade.current();
        if (state == null || !state.optBoolean("connected")) { host.openBanks(); return; }
        if (busy) return;
        requested = true;
        trade.execute(client -> client.syncValuation());
    }

    // ------------------------------------------------------------ page

    private void render() {
        Context c = getContext();
        root.removeAllViews();
        TextView title = PausaUi.editorial(c, "Cartera", 30);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        root.addView(title, full());
        root.addView(PausaUi.text(c, "Trade Republic · solo lectura", 13, PausaUi.MUTED, false), spaced(6, 16));

        if (view == null || view.empty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(20), dp(26), dp(20), dp(22));
            empty.setBackground(PausaUi.dashed(c, 0xFFCFC9BC, 24));
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(new PausaUi.Symbol(c, "seed", PausaUi.SAGE, 34));
            empty.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView heading = PausaUi.editorial(c, "Aún no hay posiciones leídas.", 20);
            heading.setGravity(Gravity.CENTER);
            heading.setPadding(0, dp(12), 0, dp(6));
            empty.addView(heading, full());
            TextView hint = PausaUi.text(c, "Pausa lee tus posiciones y su valoración cuando actualizas Trade Republic. "
                    + "Cada lectura queda guardada: así se construye el histórico.", 13, PausaUi.MUTED, false);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(0, 1.15f);
            empty.addView(hint, full());
            root.addView(empty, full());
            root.addView(updater(), spaced(12, 0));
            return;
        }
        root.addView(hero(), spaced(0, 12));
        root.addView(updater(), spaced(0, 18));
        contributions();
        holdings();
        TextView note = PausaUi.text(c, "Valores estimados con el último precio recibido en cada valoración. Pueden ir con retraso "
                + "y no son un precio de venta garantizado. Solo se suman precios en euros. El PnL total es de posiciones abiertas; el diario compara sus participaciones actuales con la referencia de la sesión anterior. No incluye ventas realizadas, dividendos ni ajustes por compras del día.", 12, PausaUi.MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(0, 1.2f);
        root.addView(note, spaced(16, 8));
        if (animate) PausaUi.stagger(root, 8);
        animate = false;
    }

    private View hero() {
        Context c = getContext();
        LinearLayout hero = column();
        hero.setBackground(PausaUi.surface(c, PausaUi.NIGHT, 28));
        hero.setPadding(dp(22), dp(20), dp(22), dp(18));
        hero.addView(PausaUi.eyebrow(c, "Valor estimado", PausaUi.ON_NIGHT_MUTED), full());
        TextView value = PausaUi.editorial(c, (view.valued > 0 ? Budget.money(view.value, true) : "Sin precio"), view.valued > 0 ? 48 : 30);
        value.setTextColor(PausaUi.ON_NIGHT);
        value.setPadding(0, dp(8), 0, dp(4));
        hero.addView(value, full());
        boolean up = view.gain() >= 0;
        String totalPnl = view.gainValued > 0 ? "PnL total · " + change(view.gain(), view.gainRatio()) : "PnL total · no disponible";
        hero.addView(PausaUi.text(c, totalPnl, 14, view.gainValued == 0 ? PausaUi.ON_NIGHT_MUTED : up ? MoneyMeters.PAID_TONE : MoneyMeters.OVER_TONE, true), full());
        String daily = view.dailyValued > 0 ? "Diario · " + view.dailySession + " · " + change(view.dailyGain(), view.dailyRatio()) : "Diario · falta precio de referencia";
        hero.addView(PausaUi.text(c, daily, 14, view.dailyValued == 0 ? PausaUi.ON_NIGHT_MUTED : view.dailyGain() >= 0 ? MoneyMeters.PAID_TONE : MoneyMeters.OVER_TONE, true), spaced(6, 0));
        if (view.gainValued < view.holdings.size() || view.dailyValued < view.holdings.size())
            hero.addView(PausaUi.text(c, "Cobertura PnL: total " + view.gainValued + "/" + view.holdings.size()
                    + " · diario " + view.dailyValued + "/" + view.holdings.size() + ". Los subtotales excluyen datos ausentes.", 12, PausaUi.ON_NIGHT_MUTED, false), spaced(6, 0));
        if (view.valued < view.holdings.size())
            hero.addView(PausaUi.text(c, (view.holdings.size() - view.valued) + " posiciones sin precio en euros no se suman", 12, PausaUi.ON_NIGHT_MUTED, false), spaced(4, 0));

        int n = view.history.size();
        if (n >= 1) {
            long[] at = new long[n], values = new long[n], costs = new long[n];
            for (int i = 0; i < n; i++) { at[i] = view.history.get(i).at; values[i] = view.history.get(i).value; costs[i] = view.history.get(i).cost; }
            MoneyMeters.ValueChart chart = new MoneyMeters.ValueChart(c, PausaUi.SUN, 0x99F8F5ED, PausaUi.NIGHT);
            chart.set(at, values, costs, animate);
            hero.addView(chart, spaced(16, 6));
            TextView caption = PausaUi.text(c, describe(view.history.get(n - 1)), 13, PausaUi.ON_NIGHT_MUTED, false);
            hero.addView(caption, full());
            chart.setListener(i -> caption.setText(describe(view.history.get(i))));
            chart.setContentDescription("Evolución del valor de la cartera en " + n + " valoraciones");
            TextView legend = PausaUi.text(c, n == 1 ? "Cada valoración completa añade un punto al histórico."
                    : "Línea: valor · Discontinua: coste conocido · " + n + " valoraciones", 12, PausaUi.ON_NIGHT_MUTED, false);
            hero.addView(legend, spaced(4, 0));
        }
        return hero;
    }

    private String describe(Portfolio.Point point) {
        long gain = point.value - point.cost;
        return date(point.at) + " · " + Budget.money(point.value, false) + (point.cost < 0 ? " · coste incompleto" : " · " + (gain >= 0 ? "+" : "") + Budget.money(gain, false) + " sobre el coste");
    }

    private View updater() {
        Context c = getContext();
        LinearLayout box = column();
        box.setBackground(PausaUi.card(c));
        box.setPadding(dp(16), dp(14), dp(16), dp(16));
        progress = PausaUi.text(c, stamp(), 13, PausaUi.MUTED, false);
        progress.setLineSpacing(0, 1.12f);
        box.addView(progress, full());
        working = new MoneyMeters.Working(c, PausaUi.NEUTRAL, PausaUi.SAGE);
        working.setVisibility(busy ? VISIBLE : GONE);
        box.addView(working, spaced(10, 0));
        update = PausaUi.action(c, busy ? "Leyendo tu cartera…" : "Actualizar valoración", true, this::refresh);
        update.setEnabled(!busy);
        box.addView(update, spaced(12, 0));
        return box;
    }

    private String stamp() {
        if (view == null || view.readAt <= 0) return "Lee tus posiciones y sus precios en Trade Republic, hasta 20 instrumentos por valoración.";
        String text = "Posiciones leídas " + BudgetView.ago(System.currentTimeMillis() - view.readAt);
        if (view.valuedAt > 0 && view.valuedAt < view.readAt - 60_000) text += " · precios de " + date(view.valuedAt);
        return text + ".";
    }

    // ------------------------------------------------------------ contributions

    /** What the owner put in each month (Trade Republic purchases minus saveback), from the movements. */
    private void contributions() {
        if (snapshot == null) return;
        Context c = getContext();
        Map<String, long[]> months = new LinkedHashMap<>();
        long gift = 0, total = 0;
        int[] now = Ledger.civil(snapshot.today);
        for (int back = 5; back >= 0; back--) {
            int month = now[1] - back, year = now[0];
            while (month < 1) { month += 12; year--; }
            months.put(year + "-" + month, new long[]{0, month});
        }
        for (Budget.Entry entry : snapshot.entries) {
            int[] d = Ledger.civil(entry.txn.day);
            long[] bucket = months.get(d[0] + "-" + d[1]);
            if (entry.saving()) { total -= entry.txn.cents; if (bucket != null) bucket[0] -= entry.txn.cents; }
            if (entry.txn.reward && entry.kind == Ledger.Kind.IGNORED) { gift += entry.txn.cents; if (bucket != null) bucket[0] -= entry.txn.cents; }
        }
        if (total <= 0) return;
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.BOTTOM);
        header.addView(PausaUi.editorial(c, "Lo que aportas", 24), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(PausaUi.text(c, Budget.money(Math.max(0, total - gift), false) + " en total", 13, PausaUi.GREEN, true));
        root.addView(header, spaced(0, 10));
        LinearLayout card = column();
        card.setBackground(PausaUi.card(c));
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        long[] values = new long[months.size()];
        String[] labels = new String[months.size()];
        int i = 0;
        for (long[] bucket : months.values()) { values[i] = Math.max(0, bucket[0]); labels[i] = Budget.MONTHS[(int) bucket[1] - 1]; i++; }
        labels[labels.length - 1] = "este mes";
        MoneyMeters.HistoryBars bars = new MoneyMeters.HistoryBars(c);
        bars.set(values, labels, 0xFF3E6A73);
        bars.setContentDescription("Aportaciones de los últimos seis meses");
        card.addView(bars, full());
        TextView detail = PausaUi.text(c, "Plan de inversión y redondeos, sin contar el saveback" + (gift > 0 ? " (+" + Budget.money(gift) + " de regalo)." : "."),
                12, PausaUi.MUTED, false);
        detail.setPadding(0, dp(8), 0, 0);
        card.addView(detail, full());
        root.addView(card, spaced(0, 18));
    }

    // ------------------------------------------------------------ holdings

    private void holdings() {
        Context c = getContext();
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.BOTTOM);
        header.addView(PausaUi.editorial(c, "Posiciones", 24), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(PausaUi.text(c, view.holdings.size() + (view.holdings.size() == 1 ? " instrumento" : " instrumentos"), 13, PausaUi.GREEN, true));
        root.addView(header, spaced(0, 10));
        LinearLayout list = column();
        list.setBackground(PausaUi.card(c));
        list.setPadding(dp(8), dp(6), dp(8), dp(6));
        long total = Math.max(1, view.value);
        int position = 0;
        for (Portfolio.Holding holding : view.holdings) list.addView(row(holding, total, position++), full());
        root.addView(list, full());
    }

    private View row(Portfolio.Holding holding, long total, int position) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(8), dp(8), dp(10), dp(8));
        row.setBackground(PausaUi.ripple(c, android.graphics.Color.TRANSPARENT, 16));
        boolean up = holding.gain() >= 0;
        int tone = !holding.gainKnown() ? PausaUi.MUTED : up ? PausaUi.SAGE : PausaUi.TERRACOTTA;
        TextView badge = PausaUi.text(c, initials(holding.name), 13, tone, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(PausaUi.surface(c, (tone & 0x00FFFFFF) | 0x1F000000, 19));
        row.addView(badge, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout middle = column();
        middle.setPadding(dp(12), 0, dp(10), 0);
        TextView name = PausaUi.text(c, holding.name, 15, PausaUi.INK, false);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        middle.addView(name, full());
        String meta = Portfolio.quantity(holding.quantity) + " part." + (holding.averageBuyIn != null && holding.cost >= 0 ? " · medio " + Portfolio.price(holding.averageBuyIn) : "");
        TextView details = PausaUi.text(c, meta, 12, PausaUi.MUTED, false);
        details.setPadding(0, dp(3), 0, 0);
        middle.addView(details, full());
        if (holding.valued()) {
            MoneyMeters.Share share = new MoneyMeters.Share(c, tone, holding.value / (float) total);
            share.animateIn(220 + 60L * position);
            middle.addView(share, spaced(7, 0));
        }
        row.addView(middle, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout right = column();
        right.setGravity(Gravity.END);
        right.addView(PausaUi.text(c, holding.valued() ? Budget.money(holding.value) : "Sin precio", 15, PausaUi.INK, true));
        if (holding.gainKnown()) {
            TextView change = PausaUi.text(c, "Total " + Portfolio.percent(holding.gainRatio()), 11, tone, true);
            change.setPadding(0, dp(3), 0, 0);
            right.addView(change);
        }
        if (holding.dailyKnown()) right.addView(PausaUi.text(c, "Sesión " + Portfolio.percent(holding.dailyRatio()), 11,
                holding.dailyGain() >= 0 ? PausaUi.GREEN : PausaUi.TERRACOTTA, false));
        row.addView(right);
        row.setOnClickListener(v -> holdingSheet(holding, total));
        row.setContentDescription(holding.name + ", " + (holding.valued() ? Budget.money(holding.value) + (holding.gainKnown() ? ", PnL total " + change(holding.gain(), holding.gainRatio()) : ", PnL desconocido") : "sin precio"));
        return row;
    }

    private void holdingSheet(Portfolio.Holding holding, long total) {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, holding.name);
        sheet.subtitle(holding.id + (holding.pricedAt > 0 ? " · precio del " + date(holding.pricedAt) : holding.receivedAt > 0 ? " · recibido el " + date(holding.receivedAt) + ", hora del precio desconocida" : ""));
        List<long[]> series = view.holdingHistory.get(holding.id);
        if (series != null && series.size() >= 2) {
            long[] at = new long[series.size()], values = new long[series.size()], costs = new long[series.size()];
            for (int i = 0; i < series.size(); i++) { at[i] = series.get(i)[0]; values[i] = series.get(i)[1]; costs[i] = series.get(i)[2]; }
            MoneyMeters.ValueChart chart = new MoneyMeters.ValueChart(c, PausaUi.SAGE, PausaUi.MUTED, PausaUi.SURFACE);
            chart.set(at, values, costs, true);
            chart.setContentDescription("Evolución del valor de " + holding.name);
            sheet.add(chart, 4);
            TextView caption = PausaUi.text(c, captionFor(series.get(series.size() - 1)), 12, PausaUi.MUTED, false);
            chart.setListener(i -> caption.setText(captionFor(series.get(i))));
            sheet.add(caption, 12);
        }
        List<String[]> facts = new ArrayList<>();
        if (holding.valued()) facts.add(new String[]{"Valor estimado", Budget.money(holding.value, true)});
        if (holding.cost >= 0) facts.add(new String[]{"Invertido", Budget.money(holding.cost, true)});
        facts.add(new String[]{"PnL total (abierto)", holding.gainKnown() ? change(holding.gain(), holding.gainRatio()) : "No disponible"});
        facts.add(new String[]{"PnL diario estimado", holding.dailyKnown() ? change(holding.dailyGain(), holding.dailyRatio()) : "Sin referencia"});
        if (holding.dailyKnown()) {
            facts.add(new String[]{"Sesión del precio", holding.dailySession});
            facts.add(new String[]{"Referencia anterior", date(holding.referenceAt)});
        }
        facts.add(new String[]{"Participaciones", Portfolio.quantity(holding.quantity)});
        if (holding.averageBuyIn != null && holding.cost >= 0) facts.add(new String[]{"Precio medio de compra", Portfolio.price(holding.averageBuyIn)});
        if (holding.price != null) facts.add(new String[]{"Último precio", Portfolio.price(holding.price)});
        if (holding.valued()) facts.add(new String[]{"Peso en la cartera", Math.round(100f * holding.value / total) + " %"});
        for (String[] fact : facts) {
            LinearLayout line = new LinearLayout(c);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(dp(44));
            line.setPadding(dp(14), 0, dp(14), 0);
            line.setBackground(PausaUi.surface(c, PausaUi.CREAM, 14));
            line.addView(PausaUi.text(c, fact[0], 14, PausaUi.MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(PausaUi.text(c, fact[1], 15, PausaUi.INK, true));
            sheet.add(line, 6);
        }
        if (!holding.valued()) sheet.add(PausaUi.text(c, "Sin precio en euros en las valoraciones guardadas.", 13, PausaUi.MUTED, false), 4);
        sheet.footer(null, PausaUi.action(c, "Cerrar", false, sheet::dismiss));
        sheet.show();
    }

    private static String change(long amount, double ratio) { return (amount > 0 ? "+" : "") + Budget.money(amount, true) + " · " + Portfolio.percent(ratio); }

    private static String captionFor(long[] point) {
        return date(point[0]) + " · " + Budget.money(point[1]) + (point[2] >= 0 ? " · invertido " + Budget.money(point[2]) : "");
    }

    private static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split("[\\s&()]+")) {
            if (word.isEmpty() || !Character.isLetterOrDigit(word.charAt(0))) continue;
            out.append(Character.toUpperCase(word.charAt(0)));
            if (out.length() == 2) break;
        }
        return out.length() == 0 ? "·" : out.toString();
    }

    private static String date(long millis) {
        return new SimpleDateFormat("d MMM, HH:mm", PausaUi.SPANISH).format(new Date(millis)).replace(".", "");
    }

    // ------------------------------------------------------------ layout helpers

    private LinearLayout column() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private static LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams p = full(); p.topMargin = dp(top); p.bottomMargin = dp(bottom); return p;
    }
    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
