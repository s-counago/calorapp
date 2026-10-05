package com.sejio.calorapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** Native UI. Never embeds a bank page or starts a new login on Activity recreation. */
public final class TradeRepublicActivity extends Activity implements TradeRepository.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TradeRepository repository;
    private LinearLayout loginPanel, pendingPanel, connectedPanel, history, portfolioHistory;
    private EditText phone, pin, code;
    private TextView status, pendingText;
    private Button login, check, verify, sync, portfolioSync, valuationSync, cancel, forget;
    private boolean active;
    private long shownCapture = -1;
    private long shownPortfolio = -1;
    private final Runnable poll = () -> {
        JSONObject state = repository.current();
        if (active && state != null && state.optBoolean("pending") && !state.optBoolean("authenticator")
                && repository.error().isEmpty()) repository.execute(c -> c.checkLogin());
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        repository = TradeRepository.get(this);
        LinearLayout root = column(); root.setPadding(dp(20), dp(14), dp(20), dp(20)); root.setBackgroundColor(PausaUi.CREAM);
        ScrollView scroll = new PausaUi.Scroll(this); scroll.addView(root); setContentView(scroll);
        root.addView(PausaUi.quiet(this, "Volver", PausaUi.GREEN, this::finish));
        root.addView(PausaUi.editorial(this, "Trade Republic", 28));
        root.addView(label("Conexión directa · prueba", true));
        status = label("Cargando la sesión del teléfono…", false); root.addView(status);

        loginPanel = column(); root.addView(loginPanel);
        phone = input("Teléfono con prefijo, p. ej. +34…", InputType.TYPE_CLASS_PHONE); loginPanel.addView(phone);
        pin = input("PIN de Trade Republic (4 cifras)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); loginPanel.addView(pin);
        login = PausaUi.action(this, "Conectar", true, () -> {
            final String number = phone.getText().toString().replace(" ", "");
            final String secret = pin.getText().toString();
            pin.setText("");
            repository.execute(c -> c.beginLogin(number, secret));
        }); loginPanel.addView(login);
        loginPanel.addView(label("El PIN se utiliza para este acceso y no se guarda. Después puedes aprobarlo en la app de Trade Republic.", false));

        pendingPanel = column(); root.addView(pendingPanel);
        pendingText = label("", false); pendingPanel.addView(pendingText);
        check = PausaUi.action(this, "Ya he confirmado · comprobar", true, () -> repository.execute(c -> c.checkLogin())); pendingPanel.addView(check);
        code = input("Código de la aplicación de autenticación", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); pendingPanel.addView(code);
        verify = PausaUi.action(this, "Validar código", true, () -> {
            final String value = code.getText().toString(); code.setText(""); repository.execute(c -> c.verifyAuthenticator(value));
        }); pendingPanel.addView(verify);
        cancel = PausaUi.quiet(this, "Cancelar este intento", PausaUi.TERRACOTTA, () -> repository.execute(c -> c.cancelLogin())); pendingPanel.addView(cancel);

        connectedPanel = column(); root.addView(connectedPanel);
        sync = PausaUi.action(this, "Sincronizar saldo y movimientos", true, () -> repository.execute(c -> c.sync())); connectedPanel.addView(sync);
        portfolioSync = PausaUi.action(this, "Consultar posiciones de inversión", true, () -> repository.execute(c -> c.syncPortfolio()));
        connectedPanel.addView(portfolioSync);
        valuationSync = PausaUi.action(this, "Actualizar valoración", true, () -> repository.execute(c -> c.syncValuation()));
        connectedPanel.addView(valuationSync);
        connectedPanel.addView(label("La valoración consulta las posiciones y un último precio por instrumento, hasta 20. Solo al pulsar, sin cotizaciones continuas. Puedes repetir pasado un minuto.", false));
        connectedPanel.addView(label("Consulta al banco al pulsar Sincronizar. Los datos guardados se pueden leer sin conexión.", false));
        forget = PausaUi.quiet(this, "Olvidar conexión y datos locales", PausaUi.TERRACOTTA, () -> {
            new AlertDialog.Builder(this).setTitle("¿Olvidar Trade Republic en Pausa?")
                    .setMessage("Borra la sesión y los datos descargados de este cliente. No revoca la sesión en el banco.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Olvidar", (d, w) -> repository.forget()).show();
        }); root.addView(forget);
        portfolioHistory = column(); root.addView(portfolioHistory);
        history = column(); root.addView(history);
        loginPanel.setVisibility(View.GONE); pendingPanel.setVisibility(View.GONE); connectedPanel.setVisibility(View.GONE);
    }

    @Override protected void onResume() { super.onResume(); active = true; repository.observe(this); }
    @Override protected void onPause() {
        active = false; handler.removeCallbacks(poll); repository.detach(this); pin.setText(""); code.setText(""); super.onPause();
    }

    @Override public void changed(JSONObject view, String error, boolean busy) {
        if (!active) return;
        handler.removeCallbacks(poll);
        boolean pending = view != null && view.optBoolean("pending");
        boolean connected = view != null && view.optBoolean("connected");
        boolean authenticator = view != null && view.optBoolean("authenticator");
        loginPanel.setVisibility(!pending && !connected ? View.VISIBLE : View.GONE);
        pendingPanel.setVisibility(pending ? View.VISIBLE : View.GONE);
        connectedPanel.setVisibility(connected && !pending ? View.VISIBLE : View.GONE);
        code.setVisibility(authenticator ? View.VISIBLE : View.GONE); verify.setVisibility(authenticator ? View.VISIBLE : View.GONE);
        check.setVisibility(authenticator ? View.GONE : View.VISIBLE);
        for (Button button : new Button[]{login, check, verify, sync, portfolioSync, valuationSync, cancel, forget}) button.setEnabled(!busy);
        phone.setEnabled(!busy); pin.setEnabled(!busy); code.setEnabled(!busy);
        if (!error.isEmpty()) status.setText(error);
        else if (busy) status.setText(repository.progress());
        else if (connected) status.setText("Sesión guardada. Pulsa Sincronizar para consultar al banco.");
        else if (pending) status.setText("Acceso pendiente de tu confirmación.");
        else status.setText("Conecta tu cuenta para guardar una sesión en este teléfono.");
        if (pending) {
            long seconds = Math.max(0, (view.optLong("expiresAt") - System.currentTimeMillis()) / 1000);
            pendingText.setText(authenticator ? "Introduce el código de tu aplicación de autenticación."
                    : "Confirma en la app de Trade Republic y vuelve aquí. Conservaremos este intento. Quedan aproximadamente " + seconds + " s.");
            if (!busy && !authenticator && error.isEmpty()) handler.postDelayed(poll, 2500);
        }
        showHistory(view == null ? null : view.optJSONObject("snapshot"));
        showPortfolio(view == null ? null : view.optJSONObject("portfolio"));
    }

    private void showPortfolio(JSONObject portfolio) {
        long captured = portfolio == null ? 0 : portfolio.optLong("capturedAt");
        if (captured == shownPortfolio) return;
        shownPortfolio = captured; portfolioHistory.removeAllViews();
        if (portfolio == null) return;
        portfolioHistory.addView(PausaUi.editorial(this, "Posiciones de inversión", 24));
        portfolioHistory.addView(label("Consulta: " + DateFormat.getDateTimeInstance().format(new Date(captured)), true));
        portfolioHistory.addView(label("Cuenta de valores · estimación al último precio recibido, no un precio garantizado de venta. Los datos pueden tener retraso o proceder de una sesión anterior del mercado.", false));
        JSONObject totals = portfolio.optJSONObject("totals");
        if (totals != null) {
            portfolioHistory.addView(label("Valoración: " + totals.optInt("valued") + " de " + totals.optInt("positions")
                    + " posiciones con precio y moneda confirmada.", true));
            JSONObject currencies = totals.optJSONObject("byCurrency");
            if (currencies != null) for (java.util.Iterator<String> it = currencies.keys(); it.hasNext();) {
                String currency = it.next();
                portfolioHistory.addView(label("Subtotal estimado " + currency + ": " + money(currencies.optString(currency)), true));
            }
            if (!totals.optBoolean("complete")) portfolioHistory.addView(label("Valoración parcial: hay posiciones sin valorar o sin moneda confirmada; no forman parte de los subtotales.", false));
        } else portfolioHistory.addView(label("Pulsa Actualizar valoración para consultar precios.", false));
        JSONArray rows = portfolio.optJSONArray("positions");
        if (rows == null) return;
        if (rows.length() == 0) portfolioHistory.addView(label("El banco devolvió una lista de posiciones vacía para esta cuenta de valores.", false));
        for (int i = 0; i < Math.min(100, rows.length()); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            String currency = row.optString("currency");
            String cost = row.has("averageBuyIn") ? row.optString("averageBuyIn")
                    + (currency.isEmpty() ? " (moneda no indicada)" : " " + currency) : "No indicado";
            String name = row.optString("name", row.optString("instrumentId"));
            portfolioHistory.addView(label(name + "\n" + row.optString("instrumentId") + "\nCantidad: " + row.optString("quantity")
                    + "\nPrecio medio de compra: " + cost, false));
            JSONObject quote = row.optJSONObject("quote");
            if (quote != null && quote.has("price")) {
                String denomination = quote.optString("currency");
                if (denomination.isEmpty()) denomination = "(moneda no enviada por TR)";
                long quoted = quote.optLong("quotedAt");
                String quoteDate = quoted > 0 ? DateFormat.getDateTimeInstance().format(new Date(quoted)) : "no indicada por TR";
                portfolioHistory.addView(label("Último precio: " + quote.optString("price") + " " + denomination
                        + "\nMercado: " + quote.optString("exchange") + " · fecha del precio: " + quoteDate
                        + "\nRecibido: " + DateFormat.getDateTimeInstance().format(new Date(quote.optLong("receivedAt")))
                        + (row.has("estimatedValue") ? "\nValor estimado: " + money(row.optString("estimatedValue")) + " " + denomination : ""), true));
            } else if (totals != null) {
                String reason = row.optString("valuationStatus");
                portfolioHistory.addView(label(reason.equals("LIMIT") ? "Sin valorar: límite de esta consulta."
                        : reason.equals("UNSUPPORTED_TYPE") ? "Sin valorar: tipo de instrumento o unidad no compatible."
                        : reason.equals("NO_MARKET") ? "Sin valorar: mercado no disponible." : "Sin valorar: precio no disponible.", false));
            }
        }
        if (rows.length() > 100) portfolioHistory.addView(label("Mostrando 100 de " + rows.length() + " posiciones guardadas.", false));
    }

    private String money(String value) {
        try { return new java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(); }
        catch (NumberFormatException error) { return "No disponible"; }
    }

    private void showHistory(JSONObject snapshot) {
        long captured = snapshot == null ? 0 : snapshot.optLong("capturedAt");
        if (captured == shownCapture) return;
        shownCapture = captured; history.removeAllViews();
        if (snapshot == null) return;
        history.addView(label("Última descarga: " + DateFormat.getDateTimeInstance().format(new Date(captured)), true));
        JSONArray balances = snapshot.optJSONArray("balances");
        if (balances != null) for (int i = 0; i < balances.length(); i++) {
            JSONObject balance = balances.optJSONObject(i);
            if (balance != null) history.addView(PausaUi.editorial(this, "Efectivo: " + balance.optString("amount") + " " + balance.optString("currency"), 24));
        }
        showTimeline("Movimientos", snapshot.optJSONObject("transactions"));
        showTimeline("Actividad de la cuenta", snapshot.optJSONObject("activity"));
    }

    private void showTimeline(String title, JSONObject feed) {
        if (feed == null) return;
        JSONArray rows = feed.optJSONArray("items"); if (rows == null) return;
        history.addView(label(title + " · " + rows.length() + " registros", true));
        history.addView(label(feed.optBoolean("complete") ? "Se ha alcanzado el final de este listado del banco."
                : "Descarga parcial: hasta 5 páginas o 500 registros por listado. No representa todo el historial.", false));
        // Bound the UI, while retaining all downloaded items in the encrypted snapshot.
        int displayed = Math.min(rows.length(), 50);
        for (int i = 0; i < displayed; i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            JSONObject amount = row.optJSONObject("amount");
            String money = amount == null ? "" : " · " + amount.optString("value") + " " + amount.optString("currency");
            String name = row.optString("title"); if (name.isEmpty()) name = row.optString("eventType");
            history.addView(label(name + money + "\n" + row.optString("timestamp") + " · " + row.optString("status")
                    + "\n" + row.optString("subtitle"), false));
        }
        if (rows.length() > displayed) history.addView(label("Mostrando los primeros " + displayed + " registros de este listado.", false));
    }

    private EditText input(String hint, int type) {
        EditText field = new EditText(this); field.setHint(hint); field.setInputType(type); field.setSingleLine(true);
        field.setSaveEnabled(false); field.setFreezesText(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return field;
    }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private TextView label(String text, boolean bold) {
        TextView result = PausaUi.text(this, text, 14, bold ? PausaUi.INK : PausaUi.MUTED, bold);
        result.setPadding(0, dp(9), 0, dp(9)); return result;
    }
    private int dp(int value) { return PausaUi.dp(this, value); }
}
