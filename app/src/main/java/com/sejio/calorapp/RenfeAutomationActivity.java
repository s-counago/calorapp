package com.sejio.calorapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RenfeAutomationActivity extends Activity {
    private static final String RENFE_PASSES =
            "https://venta.renfe.com/vol/myPassesCard.do";
    private static final long STEP_DELAY_MS = 800L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable automationRunnable = this::runAutomationStep;
    private final List<WorkItem> workItems = new ArrayList<>();
    private final List<String> completedDescriptions = new ArrayList<>();
    private final Map<String, JSONObject> pairedSeats = new HashMap<>();

    private FrameLayout browserContainer;
    private WebView webView;
    private TextView statusText;
    private TextView currentText;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button continueButton;
    private Button loginButton;
    private Button stopButton;
    private int currentIndex;
    private int waitingAttempts;
    private int repeatedActionAttempts;
    private boolean evaluating;
    private boolean automaticRunning;
    private String lastActionUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        setContentView(buildUi());

        if (!loadPlan()) {
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showFatalError(
                    "El WebView del teléfono no admite perfiles separados. "
                            + "Actualiza Android System WebView y Chrome desde Google Play y vuelve a intentarlo."
            );
            return;
        }
        prepareCurrentWork();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            confirmStop();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyWebView();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.background));
        root.setPadding(dp(12), dp(28), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("Formalización Renfe");
        title.setTextColor(getColor(R.color.text_main));
        title.setTextSize(24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        progressText = new TextView(this);
        progressText.setTextColor(getColor(R.color.text_muted));
        progressText.setTextSize(13);
        progressText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams progressTextParams = matchWrap();
        progressTextParams.setMargins(0, dp(4), 0, dp(6));
        root.addView(progressText, progressTextParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
        ));

        currentText = new TextView(this);
        currentText.setTextColor(getColor(R.color.text_main));
        currentText.setTextSize(16);
        currentText.setTypeface(currentText.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams currentParams = matchWrap();
        currentParams.setMargins(dp(4), dp(10), dp(4), 0);
        root.addView(currentText, currentParams);

        statusText = new TextView(this);
        statusText.setTextColor(getColor(R.color.text_muted));
        statusText.setTextSize(14);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.setMargins(dp(4), dp(3), dp(4), dp(8));
        root.addView(statusText, statusParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        continueButton = button("Continuar", true);
        continueButton.setOnClickListener(view -> {
            automaticRunning = true;
            continueButton.setEnabled(false);
            setStatus("Continuando…");
            runAutomationStep();
        });
        actions.addView(continueButton, weightedButton());

        loginButton = button("Abrir Renfe", false);
        loginButton.setOnClickListener(view -> {
            automaticRunning = false;
            if (webView != null) {
                webView.loadUrl(RENFE_PASSES);
            }
        });
        LinearLayout.LayoutParams loginParams = weightedButton();
        loginParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(loginButton, loginParams);

        stopButton = button("Salir", false);
        stopButton.setOnClickListener(view -> confirmStop());
        LinearLayout.LayoutParams stopParams = weightedButton();
        stopParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(stopButton, stopParams);

        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.setMargins(0, 0, 0, dp(8));
        root.addView(actions, actionParams);

        browserContainer = new FrameLayout(this);
        browserContainer.setBackgroundColor(Color.WHITE);
        root.addView(browserContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        return root;
    }

    private boolean loadPlan() {
        String rawPlan = getIntent().getStringExtra(TicketPlan.EXTRA_PLAN);
        if (rawPlan == null) {
            showFatalError("No se ha recibido ningún plan de viajes.");
            return false;
        }

        try {
            TicketPlan plan = TicketPlan.fromJsonString(rawPlan);
            for (TicketPlan.Trip trip : plan.trips) {
                if (plan.sergio && plan.miriam) {
                    String pairKey = pairKey(trip);
                    workItems.add(new WorkItem(
                            TicketPlan.TRAVELER_SERGIO, trip, true, true, pairKey
                    ));
                    workItems.add(new WorkItem(
                            TicketPlan.TRAVELER_MIRIAM, trip, true, false, pairKey
                    ));
                } else if (plan.sergio) {
                    workItems.add(new WorkItem(
                            TicketPlan.TRAVELER_SERGIO, trip, false, false, null
                    ));
                } else if (plan.miriam) {
                    workItems.add(new WorkItem(
                            TicketPlan.TRAVELER_MIRIAM, trip, false, false, null
                    ));
                }
            }
        } catch (JSONException error) {
            showFatalError("El plan de viajes no es válido.");
            return false;
        }

        if (workItems.isEmpty()) {
            showFatalError("El plan no contiene formalizaciones.");
            return false;
        }
        return true;
    }

    private String pairKey(TicketPlan.Trip trip) {
        return trip.date + "|" + trip.outbound + "|" + trip.train.departure + "|"
                + trip.train.arrival + "|" + trip.train.service;
    }

    private void prepareCurrentWork() {
        if (currentIndex >= workItems.size()) {
            finishSuccessfully();
            return;
        }

        WorkItem item = currentWork();
        if (item.paired && !item.pairLeader && !pairedSeats.containsKey(item.pairKey)) {
            pauseForUser(
                    "No he podido recuperar la plaza contigua elegida para "
                            + item.traveler + ". No continuaré con una plaza separada."
            );
            return;
        }
        updateProgress();
        currentText.setText(item.traveler + " · " + item.dates.size()
                + (item.dates.size() == 1 ? " fecha · " : " fechas · ")
                + item.trip.tripLabel() + " "
                + item.trip.train.departure + " → " + item.trip.train.arrival);
        setStatus("Preparando el lote en la sesión de " + item.traveler + "…");
        continueButton.setEnabled(false);
        automaticRunning = true;
        evaluating = false;
        waitingAttempts = 0;
        repeatedActionAttempts = 0;
        lastActionUrl = null;
        createWebViewForProfile(item.traveler.toLowerCase(Locale.ROOT));
    }

    @SuppressLint({"RequiresFeature", "SetJavaScriptEnabled"})
    private void createWebViewForProfile(String profileName) {
        destroyWebView();

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showFatalError("El WebView del teléfono no admite perfiles separados.");
            return;
        }
        WebView newWebView = new WebView(this);
        WebViewCompat.setProfile(newWebView, "renfe_" + profileName);
        webView = newWebView;

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (automaticRunning) {
                    scheduleAutomationStep(STEP_DELAY_MS);
                } else {
                    continueButton.setEnabled(true);
                }
            }
        });
        webView.setDownloadListener(downloadListener());
        browserContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.loadUrl(RENFE_PASSES);
    }

    private boolean handleExternalUrl(Uri uri) {
        String host = uri.getHost();
        if (host != null && (host.equals("renfe.com") || host.endsWith(".renfe.com"))) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "No se puede abrir este enlace.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private DownloadListener downloadListener() {
        return (url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "No se pudo abrir el billete.", Toast.LENGTH_LONG).show();
            }
        };
    }

    private void runAutomationStep() {
        if (!automaticRunning || evaluating || webView == null || currentIndex >= workItems.size()) {
            return;
        }
        evaluating = true;
        WorkItem item = currentWork();
        JSONObject requiredSeat = item.paired && !item.pairLeader
                ? pairedSeats.get(item.pairKey)
                : null;
        String script = RenfeAutomationEngine.buildScript(
                item.dates,
                item.trip,
                item.paired && item.pairLeader,
                requiredSeat
        );
        webView.evaluateJavascript(script, value -> {
            evaluating = false;
            handleAutomationResult(value);
        });
    }

    private void handleAutomationResult(String rawValue) {
        if (rawValue == null || "null".equals(rawValue)) {
            setStatus("Esperando la respuesta de Renfe…");
            return;
        }

        JSONObject result;
        try {
            result = new JSONObject(decodeJavascriptValue(rawValue));
        } catch (Exception error) {
            pauseForUser("Renfe ha devuelto una pantalla inesperada. Revisa la página y pulsa Continuar.");
            return;
        }

        String status = result.optString("status", "needs_user");
        String message = result.optString("message", "");
        switch (status) {
            case "acted":
                rememberPairedSeat(result);
                waitingAttempts = 0;
                String actionUrl = webView == null ? "" : webView.getUrl();
                if (actionUrl != null && actionUrl.equals(lastActionUrl)) {
                    repeatedActionAttempts++;
                } else {
                    lastActionUrl = actionUrl;
                    repeatedActionAttempts = 1;
                }
                setStatus(message);
                if (repeatedActionAttempts > 2) {
                    pauseForUser("Renfe ha devuelto dos veces la misma pantalla. "
                            + "He detenido los reintentos para evitar envíos duplicados.");
                }
                break;
            case "waiting":
                waitingAttempts++;
                setStatus(message);
                if (waitingAttempts > 50) {
                    pauseForUser("Renfe está tardando demasiado. Revisa la página y pulsa Continuar.");
                } else {
                    scheduleAutomationStep(STEP_DELAY_MS);
                }
                break;
            case "success":
                completeCurrentWork();
                break;
            case "needs_login":
            case "needs_route":
            case "needs_user":
            case "train_unavailable":
                pauseForUser(message);
                break;
            default:
                pauseForUser(message.isEmpty() ? "Revisa la página y pulsa Continuar." : message);
                break;
        }
    }

    private void rememberPairedSeat(JSONObject result) {
        JSONObject partnerSeat = result.optJSONObject("partnerSeat");
        if (partnerSeat == null || currentIndex >= workItems.size()) {
            return;
        }
        WorkItem item = currentWork();
        if (item.paired && item.pairLeader) {
            pairedSeats.put(item.pairKey, partnerSeat);
        }
    }

    private void completeCurrentWork() {
        WorkItem item = currentWork();
        completedDescriptions.add(item.traveler + " · " + item.dates.size()
                + (item.dates.size() == 1 ? " fecha · " : " fechas · ")
                + item.trip.tripLabel().toLowerCase(Locale.ROOT) + " "
                + item.trip.train.departure);
        currentIndex++;
        automaticRunning = false;
        prepareCurrentWork();
    }

    private void pauseForUser(String message) {
        automaticRunning = false;
        handler.removeCallbacks(automationRunnable);
        continueButton.setEnabled(true);
        setStatus(message);
    }

    private void scheduleAutomationStep(long delayMillis) {
        handler.removeCallbacks(automationRunnable);
        handler.postDelayed(automationRunnable, delayMillis);
    }

    private void finishSuccessfully() {
        automaticRunning = false;
        updateProgress();
        currentText.setText("Plan terminado");
        setStatus("Todos los lotes seleccionados han finalizado.");
        continueButton.setEnabled(false);
        loginButton.setEnabled(false);

        StringBuilder summary = new StringBuilder();
        for (String description : completedDescriptions) {
            summary.append("✓ ").append(description).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Lotes terminados")
                .setMessage(summary.toString())
                .setPositiveButton("Volver al Cogedor", (dialog, which) -> finish())
                .setNegativeButton("Ver Renfe", null)
                .show();
    }

    private void updateProgress() {
        progressText.setText(Math.min(currentIndex + 1, workItems.size()) + " de " + workItems.size());
        int progress = workItems.isEmpty() ? 0 : Math.round(currentIndex * 100f / workItems.size());
        progressBar.setProgress(progress);
    }

    private void confirmStop() {
        if (currentIndex == 0) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("¿Salir de la formalización?")
                .setMessage("Las reservas ya confirmadas seguirán activas en Renfe.")
                .setNegativeButton("Continuar", null)
                .setPositiveButton("Salir", (dialog, which) -> finish())
                .show();
    }

    private void showFatalError(String message) {
        automaticRunning = false;
        statusText.setText(message);
        continueButton.setEnabled(false);
        loginButton.setEnabled(false);
        new AlertDialog.Builder(this)
                .setTitle("No se puede continuar")
                .setMessage(message)
                .setPositiveButton("Volver", (dialog, which) -> finish())
                .show();
    }

    private WorkItem currentWork() {
        return workItems.get(currentIndex);
    }

    private String decodeJavascriptValue(String raw) throws JSONException {
        if (raw == null || "null".equals(raw)) {
            throw new JSONException("Empty JavaScript result");
        }
        return new JSONArray("[" + raw + "]").getString(0);
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        browserContainer.removeView(webView);
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.destroy();
        webView = null;
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : getColor(R.color.text_main));
        button.setBackgroundResource(primary ? R.drawable.button_primary : R.drawable.button_secondary);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, dp(52), 1);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class WorkItem {
        final String traveler;
        final TicketPlan.Trip trip;
        final List<String> dates = new ArrayList<>();
        final boolean paired;
        final boolean pairLeader;
        final String pairKey;

        WorkItem(
                String traveler,
                TicketPlan.Trip trip,
                boolean paired,
                boolean pairLeader,
                String pairKey
        ) {
            this.traveler = traveler;
            this.trip = trip;
            this.paired = paired;
            this.pairLeader = pairLeader;
            this.pairKey = pairKey;
            dates.add(trip.date);
        }
    }
}
