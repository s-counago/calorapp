package com.sejio.calorapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.UserAgentMetadata;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Visible, user-operated bank browser. Extraction never drives login or banking actions. */
public final class BankBrowserActivity extends Activity {
    static final String EXTRA_BANK = "bank";
    private BankProvider bank;
    private WebView browser;
    private TextView status, origin;
    private Button capture;
    private boolean reading;
    private boolean loadFailed;
    private long navigation;
    private long readRequest;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String readerScript;
    private FrameLayout browserFrame;
    private BankBrowserMode mode;
    private Button modeButton;
    private boolean desktopHints;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // Renfe enables this process-wide in debug builds. Banking must always turn it off.
        WebView.setWebContentsDebuggingEnabled(false);
        try { bank = BankProvider.fromId(getIntent().getStringExtra(EXTRA_BANK)); }
        catch (IllegalArgumentException error) { finish(); return; }
        mode = bank == BankProvider.TRADE_REPUBLIC ? BankBrowserMode.fromOrdinal(
                getPreferences(MODE_PRIVATE).getInt("trade_browser_mode", 0)) : BankBrowserMode.MOBILE;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PausaUi.CREAM);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(PausaUi.editorial(this, bank.label, 25));
        origin = PausaUi.text(this, "", 12, PausaUi.GREEN, true);
        root.addView(origin);
        status = PausaUi.text(this, "Inicia sesión en la web del banco si te lo pide. Después abre el resumen o los movimientos.", 13, PausaUi.MUTED, false);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);
        capture = PausaUi.action(this, "Guardar datos de esta página", true, this::capture);
        capture.setEnabled(false);
        root.addView(capture, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.addView(PausaUi.quiet(this, "Volver", PausaUi.GREEN, this::finish), new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(PausaUi.quiet(this, "Inicio", PausaUi.GREEN, () -> {
            if (browser != null) browser.loadUrl(bank.home);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(PausaUi.quiet(this, "Ayuda", PausaUi.GREEN, this::help), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        if (bank == BankProvider.TRADE_REPUBLIC) {
            LinearLayout testActions = new LinearLayout(this);
            modeButton = PausaUi.quiet(this, mode.label, PausaUi.GREEN, this::chooseMode);
            testActions.addView(modeButton, new LinearLayout.LayoutParams(0, -2, 2));
            testActions.addView(PausaUi.quiet(this, "Diagnóstico", PausaUi.GREEN, this::diagnostics),
                    new LinearLayout.LayoutParams(0, -2, 1));
            root.addView(testActions);
        }
        browserFrame = new FrameLayout(this);
        browserFrame.setClipChildren(true);
        root.addView(browserFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        browserFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutBrowser());
        setContentView(root);

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            status.setText("Actualiza Android System WebView y Chrome para usar sesiones separadas. No se utilizará el perfil de Renfe.");
            return;
        }
        try {
            readerScript = readAsset();
            createBrowser();
        } catch (Exception error) {
            destroyBrowser();
            status.setText("No se pudo preparar el navegador bancario. Actualiza WebView y vuelve a intentarlo.");
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "RequiresFeature"})
    private void createBrowser() {
        browser = new WebView(this);
        WebViewCompat.setProfile(browser, mode.profileName(bank));
        WebSettings settings = browser.getSettings();
        desktopHints = false;
        // A and B have the same viewport. C changes the virtual view width below.
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        if (mode.desktop) configureDesktopIdentity(settings);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true); // Popups are refused by the default onCreateWindow.
        settings.setSaveFormData(false);
        WebViewCompat.getProfile(browser).getCookieManager().setAcceptCookie(true);
        WebViewCompat.getProfile(browser).getCookieManager().setAcceptThirdPartyCookies(browser, false);
        if (android.os.Build.VERSION.SDK_INT >= 26) browser.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return blockNavigation(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return blockNavigation(url); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                navigation++;
                readRequest++;
                reading = false;
                loadFailed = false;
                capture.setEnabled(false);
                origin.setText(bank.allows(url) ? Uri.parse(url).getHost() : "Destino no permitido");
                if (!bank.allows(url)) { view.stopLoading(); status.setText("Enlace externo bloqueado. Vuelve al inicio del banco."); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                capture.setEnabled(!reading && !loadFailed && bank.allows(url) && bank.allows(view.getUrl()));
                flushCookies();
                // Page load is not proof of authentication. The bank owns session expiry.
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                loadFailed = true;
                capture.setEnabled(false);
                status.setText("No se pudo verificar la conexión segura con el banco.");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    capture.setEnabled(false);
                    status.setText("No se pudo cargar la página. Comprueba la conexión o consulta Ayuda.");
                }
            }
        });
        browser.setDownloadListener((url, agent, disposition, type, size) ->
                status.setText("La descarga de archivos aún no está integrada. Usa la web del banco en tu navegador para descargar extractos."));
        browserFrame.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        final WebView created = browser;
        browserFrame.post(() -> {
            if (browser != created || isDestroyed()) return;
            layoutBrowser();
            browser.loadUrl(bank.home);
        });
    }

    @SuppressLint("RequiresFeature")
    private void configureDesktopIdentity(WebSettings settings) {
        String original = settings.getUserAgentString();
        settings.setUserAgentString(BankBrowserMode.desktopUserAgent(original));
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return;
        String version = BankBrowserMode.chromeVersion(original);
        UserAgentMetadata.BrandVersion brand = new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion(version.split("\\.")[0]).setFullVersion(version).build();
        UserAgentMetadata.Builder metadata = new UserAgentMetadata.Builder()
                .setBrandVersionList(java.util.Collections.singletonList(brand))
                .setFullVersion(version).setPlatform("Linux").setPlatformVersion("")
                .setArchitecture("x86").setBitness(64).setModel("").setMobile(false).setWow64(false);
        // Use public WebKit metadata APIs; form-factor overrides are library-internal in 1.15.
        WebSettingsCompat.setUserAgentMetadata(settings, metadata.build());
        desktopHints = true;
    }

    private void layoutBrowser() {
        if (browser == null || browserFrame.getWidth() == 0 || browserFrame.getHeight() == 0) return;
        int width = mode.wide ? Math.max(dp(1100), browserFrame.getWidth()) : browserFrame.getWidth();
        float scale = (float) browserFrame.getWidth() / width;
        int height = (int) Math.ceil(browserFrame.getHeight() / scale);
        ViewGroup.LayoutParams params = browser.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            browser.setLayoutParams(params);
        }
        browser.setPivotX(0);
        browser.setPivotY(0);
        browser.setScaleX(scale);
        browser.setScaleY(scale);
    }

    private void chooseMode() {
        String[] labels = new String[BankBrowserMode.values().length];
        for (int i = 0; i < labels.length; i++) labels[i] = BankBrowserMode.values()[i].label;
        new AlertDialog.Builder(this).setTitle("Comparar acceso a Trade Republic")
                .setSingleChoiceItems(labels, mode.ordinal(), (dialog, selected) -> {
                    dialog.dismiss();
                    if (mode.ordinal() == selected) return;
                    flushCookies();
                    destroyBrowser();
                    navigation++;
                    readRequest++;
                    reading = false;
                    loadFailed = false;
                    capture.setEnabled(false);
                    mode = BankBrowserMode.fromOrdinal(selected);
                    getPreferences(MODE_PRIVATE).edit().putInt("trade_browser_mode", selected).apply();
                    modeButton.setText(mode.label);
                    status.setText("Cada modo conserva su propia sesión. Comprueba si aparece el formulario de acceso.");
                    try { createBrowser(); }
                    catch (Exception error) {
                        destroyBrowser();
                        status.setText("No se pudo aplicar este modo. Actualiza Android System WebView o selecciona Móvil.");
                    }
                }).setNegativeButton("Cerrar", null).show();
    }

    private void diagnostics() {
        if (browser == null) return;
        final WebView observed = browser;
        final BankBrowserMode observedMode = mode;
        final boolean hints = desktopHints;
        // Only browser properties: no page content, account data, URL, cookies or storage.
        browser.evaluateJavascript("JSON.stringify({ua:navigator.userAgent,width:innerWidth,"
                + "mobile:navigator.userAgentData?navigator.userAgentData.mobile:null,"
                + "platform:navigator.userAgentData?navigator.userAgentData.platform:null})", result -> {
            if (browser != observed || isFinishing() || isDestroyed()) return;
            try {
                Object decoded = new org.json.JSONTokener(result).nextValue();
                JSONObject data = new JSONObject((String) decoded);
                String summary = observedMode.label + "\nAncho web: " + data.optInt("width") + " px"
                        + "\nIndicador móvil: " + data.optString("mobile", "no disponible")
                        + "\nPlataforma: " + data.optString("platform", "no disponible")
                        + (observedMode.desktop && !hints ? "\nWebView no permite ajustar Client Hints: prueba parcial." : "")
                        + "\n\nUser-Agent:\n" + data.optString("ua")
                        + "\n\nA y B mantienen el mismo ancho; C usa un lienzo de 1100 dp. "
                        + "El dispositivo y el motor siguen siendo Android WebView.";
                new AlertDialog.Builder(this).setTitle("Diagnóstico del navegador")
                        .setMessage(summary).setPositiveButton("Cerrar", null).show();
            } catch (Exception ignored) {
                status.setText("No se pudo leer el diagnóstico. Espera a que cargue la página.");
            }
        });
    }

    private boolean blockNavigation(String url) {
        if (bank.allows(url)) return false;
        status.setText("Este enlace abre otro destino. Usa Ayuda para acceder con el navegador del sistema si el banco lo necesita.");
        return true;
    }

    private void capture() {
        if (browser == null || reading || !bank.allows(browser.getUrl())) return;
        reading = true;
        capture.setEnabled(false);
        final String url = browser.getUrl();
        final long version = navigation;
        final long request = ++readRequest;
        status.setText("Leyendo los datos visibles…");
        handler.postDelayed(() -> {
            if (browser == null || !reading || request != readRequest) return;
            readRequest++;
            reading = false;
            capture.setEnabled(!loadFailed && bank.allows(browser.getUrl()));
            status.setText("La lectura tardó demasiado. La captura anterior se conserva; puedes volver a intentarlo.");
        }, 10_000);
        browser.evaluateJavascript(readerScript, result -> {
            if (isFinishing() || isDestroyed() || browser == null || request != readRequest) return;
            reading = false;
            capture.setEnabled(!loadFailed && bank.allows(browser.getUrl()));
            if (version != navigation || !url.equals(browser.getUrl())) {
                status.setText("La página cambió durante la lectura. Vuelve a guardar cuando termine de cargar.");
                return;
            }
            try {
                JSONObject data = new JSONObject(result);
                if ("authentication_required".equals(data.optString("status"))) {
                    status.setText("Completa el acceso o la verificación del banco y vuelve a guardar los datos.");
                    return;
                }
                if (!"captured".equals(data.optString("status"))) {
                    status.setText("No se encontraron datos legibles aquí. Abre el resumen o los movimientos. La captura anterior se conserva.");
                    return;
                }
                data.put("schema", 1);
                data.put("bank", bank.id);
                data.put("capturedAt", System.currentTimeMillis());
                // Keep only the origin: never persist query parameters, fragments or path tokens.
                data.put("url", "https://" + Uri.parse(url).getHost() + "/");
                BankSnapshotStore.save(this, bank, data);
                status.setText("Captura guardada para consultar sin conexión. Solo incluye datos cargados de esta página, no todo el historial.");
            } catch (Exception error) {
                status.setText("No se pudo guardar la captura. Tus datos anteriores se conservan; no se ha registrado información bancaria en los logs.");
            }
        });
    }

    private String readAsset() throws Exception {
        try (InputStream input = getAssets().open("banking/read-visible.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void help() {
        new AlertDialog.Builder(this).setTitle("Tu sesión de " + bank.label)
                .setMessage("La sesión se conserva en este teléfono mientras el banco lo permita. Pausa no guarda tu PIN ni evita el segundo factor.\n\n"
                        + "El lector solo guarda texto visible; tú manejas la web completa del banco, incluidas sus operaciones.\n\n"
                        + "Si el banco no admite este navegador integrado, puedes abrir su web oficial fuera. Esa sesión no se comparte con Pausa y no permite capturar datos aquí.")
                .setPositiveButton("Entendido", null)
                .setNeutralButton("Navegador externo", (dialog, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(bank.home))); }
                    catch (android.content.ActivityNotFoundException error) { status.setText("No hay un navegador externo disponible."); }
                }).show();
    }

    @SuppressLint("RequiresFeature") private void flushCookies() {
        if (browser != null) WebViewCompat.getProfile(browser).getCookieManager().flush();
    }

    @Override protected void onResume() {
        super.onResume();
        WebView.setWebContentsDebuggingEnabled(false);
        if (browser != null) browser.onResume();
    }

    @Override protected void onPause() {
        flushCookies();
        if (browser != null) browser.onPause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (browser != null && browser.canGoBack()) browser.goBack(); else super.onBackPressed();
    }

    private void destroyBrowser() {
        if (browser == null) return;
        browser.stopLoading();
        if (browser.getParent() != null) ((ViewGroup) browser.getParent()).removeView(browser);
        browser.destroy();
        browser = null;
    }

    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); destroyBrowser(); super.onDestroy(); }
    private int dp(int value) { return PausaUi.dp(this, value); }
}
