package com.sejio.calorapp;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tus bancos: how fresh each bank's data is and one button to bring it up to date. Trade Republic is read
 * natively right here; ABANCA opens its own web for the login it requires. Nothing syncs without a tap.
 */
final class BankSyncView extends PausaUi.Scroll implements TradeRepository.Listener {
    interface Host { void synced(); }

    static final long FRESH = 24 * 3_600_000L, STALE = 72 * 3_600_000L;

    private final Host host;
    private final LinearLayout root;
    private final TradeRepository trade;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView overall, tradeMeta, abancaMeta, tradeMessage;
    private final View tradeDot, abancaDot;
    private final MoneyMeters.Working tradeWorking;
    private final Button tradeAction;
    private JSONObject tradeView;
    private String tradeError = "";
    /** requested: the operation in flight was asked for here, so its end deserves a reaction. */
    private boolean tradeBusy, wasBusy, syncAfterLogin, watching, requested;
    private long tradeSync, abancaSync, tradeCount, abancaCount;
    private ConnectSheet connect;
    private final Runnable poll = () -> {
        if (watching && tradeView != null && tradeView.optBoolean("pending") && !tradeView.optBoolean("authenticator") && !tradeBusy && tradeError.isEmpty())
            run(c -> c.checkLogin());
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (connect != null) connect.countdown(); main.postDelayed(this, 1000); }
    };

    BankSyncView(Context context, Host host) {
        super(context);
        this.host = host;
        trade = TradeRepository.get(context);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(6), dp(20), 0);
        addView(root, new LayoutParams(-1, -2));

        TextView title = PausaUi.editorial(context, "Tus bancos", 30);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        root.addView(title, full());
        overall = PausaUi.text(context, "", 13, PausaUi.MUTED, false);
        overall.setLineSpacing(0, 1.12f);
        root.addView(overall, spaced(6, 18));

        // Trade Republic: native, updated in place.
        LinearLayout tradeCard = card();
        tradeDot = dot();
        tradeMeta = meta();
        tradeCard.addView(head("TR", PausaUi.NIGHT, "Trade Republic", tradeDot, tradeMeta), full());
        tradeWorking = new MoneyMeters.Working(context, PausaUi.NEUTRAL, PausaUi.SAGE);
        tradeWorking.setVisibility(GONE);
        tradeCard.addView(tradeWorking, spaced(14, 0));
        tradeMessage = PausaUi.text(context, "", 13, PausaUi.MUTED, false);
        tradeMessage.setLineSpacing(0, 1.12f);
        tradeCard.addView(tradeMessage, spaced(10, 0));
        tradeAction = PausaUi.action(context, "Actualizar", true, this::tradeAction);
        tradeCard.addView(tradeAction, spaced(14, 0));
        root.addView(tradeCard, spaced(0, 12));

        // ABANCA: the bank's own web for the login, then Pausa reads on its own.
        LinearLayout abancaCard = card();
        abancaDot = dot();
        abancaMeta = meta();
        abancaCard.addView(head("AB", PausaUi.GREEN, "ABANCA", abancaDot, abancaMeta), full());
        TextView how = PausaUi.text(context, "Se abre su web para que entres con tus claves y su verificación. "
                + "Después Pausa lee tus cuentas, tarjetas y préstamo sola y vuelve aquí.", 13, PausaUi.MUTED, false);
        how.setLineSpacing(0, 1.12f);
        abancaCard.addView(how, spaced(12, 0));
        Button abanca = PausaUi.action(context, "Actualizar", true, () -> context.startActivity(
                new Intent(context, BankBrowserActivity.class).putExtra(BankBrowserActivity.EXTRA_BANK, BankProvider.ABANCA.id)
                        .putExtra(BankBrowserActivity.EXTRA_SYNC, true)));
        abancaCard.addView(abanca, spaced(14, 0));
        root.addView(abancaCard, spaced(0, 12));

        LinearLayout more = new LinearLayout(context);
        more.setGravity(Gravity.CENTER_VERTICAL);
        more.setMinimumHeight(dp(56));
        more.setPadding(dp(16), 0, dp(12), 0);
        more.setBackground(PausaUi.ripple(context, PausaUi.CREAM_DEEP, 18));
        TextView label = PausaUi.text(context, "Más opciones", 15, PausaUi.INK, false);
        label.setCompoundDrawables(new PausaUi.Symbol(context, "tune", PausaUi.INK, 18), null, null, null);
        label.setCompoundDrawablePadding(dp(10));
        more.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView chevron = new ImageView(context);
        chevron.setImageDrawable(new PausaUi.Symbol(context, "chevron", PausaUi.MUTED, 18));
        more.addView(chevron);
        more.setOnClickListener(v -> options());
        more.setContentDescription("Más opciones de los bancos");
        root.addView(more, spaced(4, 10));
        TextView promise = PausaUi.text(context, "Pausa solo lee. Nunca mueve dinero, no guarda tus claves y todo se queda cifrado en este teléfono.", 12, PausaUi.MUTED, false);
        promise.setGravity(Gravity.CENTER);
        promise.setLineSpacing(0, 1.2f);
        promise.setCompoundDrawables(null, new PausaUi.Symbol(context, "shield", PausaUi.SAGE, 22), null, null);
        promise.setCompoundDrawablePadding(dp(8));
        root.addView(promise, spaced(14, 0));
    }

    // ------------------------------------------------------------ lifecycle

    /** Starts observing Trade Republic and re-reads freshness from the ledger. Reading never contacts a bank. */
    void refresh() {
        watching = true;
        trade.observe(this);
        main.removeCallbacks(tick); main.post(tick);
        Context app = getContext().getApplicationContext();
        worker.execute(() -> {
            long t = 0, a = 0, tc = 0, ac = 0;
            try {
                BankingDatabase db = BankingDatabase.get(app);
                t = db.lastSync("trade_republic"); a = db.lastSync("abanca");
                tc = db.counts("trade_republic").optLong("movements"); ac = db.counts("abanca").optLong("movements");
            } catch (Exception ignored) { /* Freshness is informative; the cards still work. */ }
            final long ft = t, fa = a, ftc = tc, fac = ac;
            main.post(() -> { tradeSync = ft; abancaSync = fa; tradeCount = ftc; abancaCount = fac; render(); });
        });
    }

    void stop() {
        watching = false;
        main.removeCallbacks(poll); main.removeCallbacks(tick);
        trade.detach(this);
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    @Override public void changed(JSONObject view, String error, boolean busy) {
        if (!watching) return;
        main.removeCallbacks(poll);
        boolean finished = wasBusy && !busy;
        wasBusy = busy;
        tradeView = view; tradeError = error == null ? "" : error; tradeBusy = busy;
        boolean connected = view != null && view.optBoolean("connected"), pending = view != null && view.optBoolean("pending");
        if (pending && !busy && !view.optBoolean("authenticator") && tradeError.isEmpty()) main.postDelayed(poll, 2500);
        if (finished && requested) {
            requested = false;
            if (tradeError.isEmpty() && connected && !pending) {
                if (syncAfterLogin) {
                    // The login just completed: read right away, as the owner asked when connecting.
                    syncAfterLogin = false;
                    runAll();
                    return;
                }
                if (connect != null) { connect.dismiss(); connect = null; }
                refresh();
                host.synced();
            }
        }
        render();
        if (connect != null) connect.update();
    }

    // ------------------------------------------------------------ cards

    private void render() {
        Context c = getContext();
        long now = System.currentTimeMillis();
        boolean connected = tradeView != null && tradeView.optBoolean("connected");
        boolean pending = tradeView != null && tradeView.optBoolean("pending");
        String code = codeOf(tradeError);
        boolean expired = code.equals("AUTH") || code.equals("EXPIRED") || code.equals("LOGIN_ENDED");
        freshness(tradeDot, tradeMeta, tradeSync, tradeCount, now);
        freshness(abancaDot, abancaMeta, abancaSync, abancaCount, now);

        tradeWorking.setVisibility(tradeBusy ? VISIBLE : GONE);
        tradeWorking.set(-1);
        String message;
        int color = PausaUi.MUTED;
        if (tradeBusy) message = trade.progress().isEmpty() ? "Hablando con Trade Republic…" : trade.progress();
        else if (!tradeError.isEmpty()) { message = plain(tradeError); color = PausaUi.TERRACOTTA; }
        else if (pending) message = "Falta confirmar el acceso en la app de Trade Republic.";
        else if (connected) message = "Sesión guardada en este teléfono: movimientos, posiciones y valoración se actualizan aquí mismo.";
        else message = "Conéctalo una vez con tu teléfono y PIN; después basta con un toque.";
        tradeMessage.setText(message);
        tradeMessage.setTextColor(color);
        tradeAction.setEnabled(!tradeBusy);
        tradeAction.setText(tradeBusy ? "Leyendo…" : pending ? "Continuar el acceso" : connected && !expired ? "Actualizar" : expired && connected ? "Volver a conectar" : "Conectar");

        long oldest = Math.min(tradeSync <= 0 ? 0 : tradeSync, abancaSync <= 0 ? 0 : abancaSync);
        if (tradeSync <= 0 && abancaSync <= 0) overall.setText("Aún no hay datos. Empieza por cualquiera de los dos: Dinero se construye con lo que leas aquí.");
        else if (tradeSync > 0 && abancaSync > 0 && now - oldest < FRESH) overall.setText("Todo al día. Pausa solo lee: nada se mueve sin ti.");
        else {
            String behind = tradeSync <= 0 || (abancaSync > 0 && tradeSync < abancaSync) ? "Trade Republic" : "ABANCA";
            long when = behind.equals("ABANCA") ? abancaSync : tradeSync;
            overall.setText(behind + (when <= 0 ? " aún no se ha leído." : " no se actualiza desde " + BudgetView.ago(now - when) + ".")
                    + " Lo que ves en Dinero puede ir por detrás.");
        }
    }

    private void freshness(View dot, TextView meta, long at, long count, long now) {
        long age = now - at;
        int color = at <= 0 ? PausaUi.LINE : age < FRESH ? PausaUi.SAGE : age < STALE ? PausaUi.SUN : PausaUi.TERRACOTTA;
        dot.setBackground(PausaUi.surface(getContext(), color, 5));
        meta.setText(at <= 0 ? "Sin leer todavía" : "Actualizado " + BudgetView.ago(age) + " · " + PausaUi.number(count) + " movimientos");
    }

    private void tradeAction() {
        boolean connected = tradeView != null && tradeView.optBoolean("connected");
        boolean pending = tradeView != null && tradeView.optBoolean("pending");
        String code = codeOf(tradeError);
        boolean expired = code.equals("AUTH") || code.equals("EXPIRED") || code.equals("LOGIN_ENDED");
        if (connected && !pending && !expired) { runAll(); return; }
        connect = new ConnectSheet();
        connect.show();
    }

    /** Movements, positions and valuation: everything Dinero and Cartera show. */
    void runAll() {
        if (tradeBusy) return;
        requested = true;
        trade.syncAll();
    }

    private void run(TradeRepository.Work work) {
        if (tradeBusy) return;
        requested = true;
        trade.execute(work);
    }

    /** "Mensaje… [CODE]" → CODE. */
    static String codeOf(String error) {
        int open = error.lastIndexOf('['), close = error.lastIndexOf(']');
        return open >= 0 && close > open ? error.substring(open + 1, close) : "";
    }

    static String plain(String error) {
        int open = error.lastIndexOf(" [");
        return open > 0 ? error.substring(0, open) : error;
    }

    // ------------------------------------------------------------ Trade Republic connection

    /** Phone and PIN, then the confirmation in the Trade Republic app (or an authenticator code), then a first read. */
    private final class ConnectSheet {
        private final PausaUi.Sheet sheet;
        private final LinearLayout form, waiting;
        private final EditText phone, pin, code;
        private final TextView status, wait, countdown;
        private final Button primary;
        private final MoneyMeters.Working working;

        ConnectSheet() {
            Context c = getContext();
            sheet = new PausaUi.Sheet(c, "Conectar Trade Republic");
            sheet.subtitle("Solo lectura. El PIN se usa para este acceso y no se guarda; tu teléfono queda como sesión de confianza.");
            form = new LinearLayout(c);
            form.setOrientation(LinearLayout.VERTICAL);
            phone = field(form, "Teléfono con prefijo", InputType.TYPE_CLASS_PHONE);
            phone.setText("+34");
            pin = field(form, "PIN de 4 cifras", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pin.setImeOptions(EditorInfo.IME_ACTION_DONE);
            pin.setOnEditorActionListener((v, id, e) -> { if (PausaUi.isSubmit(id, e)) { go(); return true; } return false; });
            sheet.add(form, 0);

            waiting = new LinearLayout(c);
            waiting.setOrientation(LinearLayout.VERTICAL);
            waiting.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(new PausaUi.Symbol(c, "phone", PausaUi.GREEN, 40));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setBackground(PausaUi.surface(c, PausaUi.SAGE_SOFT, 36));
            waiting.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
            wait = PausaUi.editorial(c, "", 22);
            wait.setGravity(Gravity.CENTER);
            wait.setPadding(0, dp(14), 0, dp(6));
            waiting.addView(wait, full());
            countdown = PausaUi.text(c, "", 13, PausaUi.MUTED, false);
            countdown.setGravity(Gravity.CENTER);
            waiting.addView(countdown, full());
            code = field(waiting, "Código de tu app de autenticación", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            sheet.add(waiting, 4);

            working = new MoneyMeters.Working(c, PausaUi.NEUTRAL, PausaUi.SAGE);
            sheet.add(working, 8);
            status = PausaUi.text(c, "", 13, PausaUi.MUTED, false);
            status.setLineSpacing(0, 1.12f);
            sheet.add(status, 4);
            primary = PausaUi.action(c, "Continuar", true, this::go);
            Button cancel = PausaUi.quiet(c, "Cancelar", PausaUi.MUTED, () -> {
                syncAfterLogin = false;
                if (tradeView != null && tradeView.optBoolean("pending") && !tradeBusy) trade.execute(cl -> cl.cancelLogin());
                dismiss();
            });
            sheet.footer(cancel, primary);
            sheet.dialog.setOnDismissListener(d -> { pin.setText(""); code.setText(""); if (connect == this) connect = null; });
            update();
        }

        void show() {
            TaskSheets.showWithKeyboard(sheet);
            if (form.getVisibility() == VISIBLE) pin.requestFocus();
        }

        void dismiss() { pin.setText(""); code.setText(""); sheet.dismiss(); }

        private void go() {
            boolean pending = tradeView != null && tradeView.optBoolean("pending");
            boolean authenticator = tradeView != null && tradeView.optBoolean("authenticator");
            if (tradeBusy) return;
            if (pending && authenticator) {
                String value = code.getText().toString(); code.setText("");
                syncAfterLogin = true;
                run(c -> c.verifyAuthenticator(value));
            } else if (pending) {
                syncAfterLogin = true;
                run(c -> c.checkLogin());
            } else {
                String number = phone.getText().toString().replace(" ", ""), secret = pin.getText().toString();
                pin.setText("");
                syncAfterLogin = true;
                run(c -> c.beginLogin(number, secret));
            }
        }

        void update() {
            boolean pending = tradeView != null && tradeView.optBoolean("pending");
            boolean authenticator = tradeView != null && tradeView.optBoolean("authenticator");
            boolean connected = tradeView != null && tradeView.optBoolean("connected");
            form.setVisibility(pending || (connected && tradeBusy) ? GONE : VISIBLE);
            waiting.setVisibility(pending ? VISIBLE : GONE);
            code.setVisibility(authenticator ? VISIBLE : GONE);
            wait.setText(authenticator ? "Escribe el código" : "Confírmalo en tu móvil");
            working.setVisibility(tradeBusy ? VISIBLE : GONE);
            primary.setEnabled(!tradeBusy);
            primary.setText(pending ? (authenticator ? "Validar" : "Ya lo he confirmado") : "Continuar");
            if (tradeBusy) { status.setText(trade.progress()); status.setTextColor(PausaUi.MUTED); }
            else if (!tradeError.isEmpty()) { status.setText(plain(tradeError)); status.setTextColor(PausaUi.TERRACOTTA); }
            else if (pending && !authenticator) { status.setText("Abre la app de Trade Republic y acepta el acceso. Pausa lo detecta sola."); status.setTextColor(PausaUi.MUTED); }
            else { status.setText(""); }
            countdown();
        }

        void countdown() {
            if (tradeView == null || !tradeView.optBoolean("pending")) { countdown.setText(""); return; }
            long seconds = Math.max(0, (tradeView.optLong("expiresAt") - System.currentTimeMillis()) / 1000);
            countdown.setText(seconds == 0 ? "El intento ha caducado: cancela y vuelve a empezar." : "Quedan " + seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60));
        }

        private EditText field(LinearLayout parent, String hint, int type) {
            Context c = getContext();
            TextView label = PausaUi.text(c, hint, 12, PausaUi.MUTED, true);
            parent.addView(label, spaced(6, 6));
            EditText input = new EditText(c);
            PausaUi.input(input);
            input.setInputType(type);
            input.setSingleLine(true);
            input.setContentDescription(hint);
            input.setSaveEnabled(false);
            input.setFreezesText(false);
            if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            if ((type & InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0) input.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            parent.addView(input, spaced(0, 8));
            return input;
        }
    }

    // ------------------------------------------------------------ options

    private void options() {
        Context c = getContext();
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Más opciones");
        sheet.add(option(sheet, "Cargar histórico inicial", "doc", () -> c.startActivity(new Intent(c, BankingImportActivity.class))), 6);
        sheet.add(option(sheet, "Lecturas guardadas de ABANCA", "doc", () -> c.startActivity(new Intent(c, AbancaArchiveActivity.class))), 6);
        sheet.add(option(sheet, "Lecturas guardadas de Trade Republic", "doc", () -> c.startActivity(new Intent(c, AbancaArchiveActivity.class)
                .putExtra(AbancaArchiveActivity.EXTRA_BANK, BankProvider.TRADE_REPUBLIC.id))), 6);
        sheet.add(option(sheet, "Inversiones y detalles de Trade Republic", "seed", () -> c.startActivity(new Intent(c, TradeRepublicActivity.class))), 6);
        sheet.add(option(sheet, "Abrir solo la web de ABANCA", "bank", () -> c.startActivity(new Intent(c, BankBrowserActivity.class)
                .putExtra(BankBrowserActivity.EXTRA_BANK, BankProvider.ABANCA.id))), 14);
        sheet.add(PausaUi.eyebrow(c, "Sesiones en este teléfono", PausaUi.MUTED), 6);
        sheet.add(option(sheet, "Olvidar la sesión web de ABANCA", "trash", () -> confirmForgetWeb(BankProvider.ABANCA)), 6);
        sheet.add(option(sheet, "Olvidar Trade Republic y sus datos", "trash", this::confirmForgetTrade), 6);
        sheet.add(option(sheet, "Olvidar la prueba antigua de Trade Republic en navegador", "trash", () -> confirmForgetWeb(BankProvider.TRADE_REPUBLIC)), 4);
        sheet.show();
    }

    private View option(PausaUi.Sheet sheet, String label, String symbol, Runnable action) {
        Context c = getContext();
        boolean destructive = symbol.equals("trash");
        TextView row = PausaUi.text(c, label, 15, destructive ? PausaUi.TERRACOTTA : PausaUi.INK, false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(52));
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setBackground(PausaUi.ripple(c, PausaUi.CREAM, 16));
        row.setCompoundDrawables(new PausaUi.Symbol(c, symbol, destructive ? PausaUi.TERRACOTTA : PausaUi.GREEN, 20), null, null, null);
        row.setCompoundDrawablePadding(dp(12));
        row.setOnClickListener(v -> { sheet.dismiss(); action.run(); });
        return row;
    }

    private void confirmForgetTrade() {
        new AlertDialog.Builder(getContext()).setTitle("¿Olvidar Trade Republic en Pausa?")
                .setMessage("Borra la sesión y los datos descargados de Trade Republic en este teléfono. No revoca la sesión en el banco.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Olvidar", (d, w) -> { trade.forget(); host.synced(); }).show();
    }

    private void confirmForgetWeb(BankProvider bank) {
        new AlertDialog.Builder(getContext()).setTitle("¿Olvidar la sesión web de " + bank.label + "?")
                .setMessage("Se eliminan las cookies y los datos del navegador de este banco en Pausa. Los datos leídos se conservan. "
                        + "Esto no revoca la sesión en el banco; para eso usa sus opciones de seguridad.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Olvidar", (d, w) -> forgetWeb(bank)).show();
    }

    @SuppressLint("RequiresFeature") private void forgetWeb(BankProvider bank) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            PausaUi.snack(getContext(), "Actualiza WebView para gestionar las sesiones de los bancos.", null, null);
            return;
        }
        try {
            ProfileStore profiles = ProfileStore.getInstance();
            if (profiles.getAllProfileNames().contains(bank.profileName()) && !profiles.deleteProfile(bank.profileName()))
                throw new IllegalStateException("Perfil en uso");
            if (bank == BankProvider.TRADE_REPUBLIC) {
                for (BankBrowserMode mode : BankBrowserMode.values()) {
                    String name = mode.profileName(bank);
                    if (profiles.getAllProfileNames().contains(name) && !profiles.deleteProfile(name)) throw new IllegalStateException("Perfil en uso");
                }
            }
            PausaUi.snack(getContext(), "Sesión web eliminada de este teléfono.", null, null);
        } catch (Exception error) {
            PausaUi.snack(getContext(), "No se pudo eliminar la sesión. Cierra la web del banco e inténtalo otra vez.", null, null);
        }
    }

    // ------------------------------------------------------------ layout helpers

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(PausaUi.card(getContext()));
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        return card;
    }

    private View head(String monogram, int color, String name, View dot, TextView meta) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = PausaUi.text(c, monogram, 14, PausaUi.CREAM, true);
        badge.setGravity(Gravity.CENTER);
        badge.setLetterSpacing(.06f);
        badge.setBackground(PausaUi.surface(c, color, 22));
        badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = new LinearLayout(c);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, 0, 0);
        TextView title = PausaUi.editorial(c, name, 22);
        labels.addView(title, full());
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(5), 0, 0);
        line.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = dp(8);
        line.addView(meta, mp);
        labels.addView(line, full());
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View dot() {
        View dot = new View(getContext());
        dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return dot;
    }

    private TextView meta() { return PausaUi.text(getContext(), "", 13, PausaUi.MUTED, false); }

    private static LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }

    private LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams p = full(); p.topMargin = dp(top); p.bottomMargin = dp(bottom); return p;
    }

    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
