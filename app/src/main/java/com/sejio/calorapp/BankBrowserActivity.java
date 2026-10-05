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
    static final String EXTRA_SYNC = "sync_after_login";
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
    private AbancaSyncController abancaSync;
    private String linksScript;
    private Button syncButton, syncCancel, viewSaved, coverDone;
    private LinearLayout syncCover;
    private TextView syncProgress, coverTitle;
    private android.widget.ImageView coverIcon;
    private MoneyMeters.Working coverBar;
    private static final String[] STEPS = {"Accede", "Leemos", "Listo"};
    private final TextView[] stepViews = new TextView[STEPS.length];

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
        root.setPadding(dp(8), dp(6), dp(8), dp(10));

        // A slim bar: back, the bank and where the page comes from, home and help.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.addView(PausaUi.iconButton(this, "back", "Volver", PausaUi.INK, this::finish), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(4), 0, 0, 0);
        titles.addView(PausaUi.editorial(this, bank.label, 22));
        origin = PausaUi.text(this, "", 12, PausaUi.SAGE, true);
        origin.setCompoundDrawables(new PausaUi.Symbol(this, "shield", PausaUi.SAGE, 13), null, null, null);
        origin.setCompoundDrawablePadding(dp(4));
        origin.setPadding(0, dp(3), 0, 0);
        titles.addView(origin);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(PausaUi.iconButton(this, "home", "Inicio del banco", PausaUi.INK, () -> {
            if (browser != null) browser.loadUrl(bank.home);
        }), new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(PausaUi.iconButton(this, "info", "Ayuda", PausaUi.INK, this::help), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        // What is happening, in three steps, above the bank's own page.
        LinearLayout guide = new LinearLayout(this);
        guide.setOrientation(LinearLayout.VERTICAL);
        guide.setBackground(PausaUi.surface(this, PausaUi.SUN_SOFT, 18));
        guide.setPadding(dp(14), dp(10), dp(14), dp(12));
        if (bank == BankProvider.ABANCA) {
            LinearLayout steps = new LinearLayout(this);
            for (int i = 0; i < STEPS.length; i++) {
                TextView step = PausaUi.text(this, (i + 1) + "  " + STEPS[i], 12, PausaUi.MUTED, true);
                step.setGravity(android.view.Gravity.CENTER);
                step.setPadding(dp(6), dp(5), dp(6), dp(5));
                stepViews[i] = step;
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > 0) sp.leftMargin = dp(6);
                steps.addView(step, sp);
            }
            guide.addView(steps, new LinearLayout.LayoutParams(-1, -2));
        }
        status = PausaUi.text(this, bank == BankProvider.ABANCA ? "Entra con tus claves en la web de ABANCA. En cuanto aparezca tu posición global, Pausa empieza a leer sola."
                : "Inicia sesión en la web del banco si te lo pide. Después abre el resumen o los movimientos.", 13, PausaUi.INK, false);
        status.setLineSpacing(0, 1.12f);
        status.setPadding(dp(2), dp(bank == BankProvider.ABANCA ? 8 : 0), dp(2), 0);
        guide.addView(status);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = dp(4); gp.leftMargin = dp(4); gp.rightMargin = dp(4);
        root.addView(guide, gp);

        capture = PausaUi.action(this, "Guardar datos de esta página", true, this::capture);
        capture.setEnabled(false);
        if (bank == BankProvider.ABANCA) capture.setVisibility(View.GONE);
        root.addView(capture, new LinearLayout.LayoutParams(-1, -2));
        if (bank == BankProvider.ABANCA) {
            syncButton = PausaUi.quiet(this, "Leer mis productos ahora", PausaUi.GREEN, () -> {
                if (abancaSync == null) return;
                if (abancaSync.isActive()) abancaSync.cancel(); else abancaSync.request();
            });
            syncButton.setEnabled(false);
            root.addView(syncButton, new LinearLayout.LayoutParams(-1, dp(48)));
        }
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
        // The bank's page sits in a rounded window, so it reads as a guest inside Pausa.
        browserFrame.setBackground(PausaUi.card(this));
        browserFrame.setClipToOutline(true);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, 0, 1);
        fp.topMargin = dp(8); fp.leftMargin = dp(4); fp.rightMargin = dp(4);
        root.addView(browserFrame, fp);
        browserFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutBrowser());
        setContentView(root);
        // Android 15 draws behind the system bars: keep the bar and the bank's page clear of them.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(dp(8) + insets.getSystemWindowInsetLeft(), top + dp(6), dp(8) + insets.getSystemWindowInsetRight(), bottom + dp(10));
            return insets;
        });
        root.requestApplyInsets();

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            status.setText("Actualiza Android System WebView y Chrome para usar sesiones separadas. No se utilizará el perfil de Renfe.");
            return;
        }
        try {
            readerScript = readAsset();
            if (bank == BankProvider.ABANCA) linksScript = readAsset("banking/abanca-links.js");
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
                if (abancaSync != null) abancaSync.pageStarted(url);
                origin.setText(bank.allows(url) ? Uri.parse(url).getHost() : "Destino no permitido");
                if (!bank.allows(url)) { view.stopLoading(); status.setText("Enlace externo bloqueado. Vuelve al inicio del banco."); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                capture.setEnabled(!reading && !loadFailed && bank.allows(url) && bank.allows(view.getUrl())
                        && (abancaSync == null || !abancaSync.isActive()));
                flushCookies();
                if (abancaSync != null) abancaSync.pageFinished(url);
                // Page load is not proof of authentication. The bank owns session expiry.
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (abancaSync != null) abancaSync.error();
                loadFailed = true;
                capture.setEnabled(false);
                status.setText("No se pudo verificar la conexión segura con el banco.");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    if (abancaSync != null) abancaSync.error();
                    loadFailed = true;
                    capture.setEnabled(false);
                    status.setText("No se pudo cargar la página. Comprueba la conexión o consulta Ayuda.");
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400 && abancaSync != null) abancaSync.error();
            }
        });
        browser.setDownloadListener((url, agent, disposition, type, size) ->
                status.setText("La descarga de archivos aún no está integrada. Usa la web del banco en tu navegador para descargar extractos."));
        browserFrame.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        if (bank == BankProvider.ABANCA) prepareSync();
        final WebView created = browser;
        browserFrame.post(() -> {
            if (browser != created || isDestroyed()) return;
            layoutBrowser();
            if (abancaSync != null && getIntent().getBooleanExtra(EXTRA_SYNC, false)) abancaSync.request();
            else browser.loadUrl(bank.home);
        });
    }

    private void prepareSync() {
        syncCover = new LinearLayout(this); syncCover.setOrientation(LinearLayout.VERTICAL);
        syncCover.setGravity(android.view.Gravity.CENTER); syncCover.setPadding(dp(28), dp(28), dp(28), dp(28));
        syncCover.setBackgroundColor(PausaUi.SURFACE); syncCover.setClickable(true); syncCover.setVisibility(View.GONE);
        coverIcon = new android.widget.ImageView(this);
        coverIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        coverIcon.setImageDrawable(new PausaUi.Symbol(this, "bank", PausaUi.GREEN, 40));
        coverIcon.setBackground(PausaUi.surface(this, PausaUi.SAGE_SOFT, 42));
        syncCover.addView(coverIcon, new LinearLayout.LayoutParams(dp(84), dp(84)));
        coverTitle = PausaUi.editorial(this, "Leyendo tus productos", 26);
        coverTitle.setGravity(android.view.Gravity.CENTER);
        coverTitle.setPadding(0, dp(18), 0, dp(14));
        syncCover.addView(coverTitle, new LinearLayout.LayoutParams(-1, -2));
        coverBar = new MoneyMeters.Working(this, PausaUi.NEUTRAL, PausaUi.SAGE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(220), -2);
        syncCover.addView(coverBar, bp);
        syncProgress = PausaUi.text(this, "", 14, PausaUi.MUTED, false);
        syncProgress.setGravity(android.view.Gravity.CENTER);
        syncProgress.setLineSpacing(0, 1.15f);
        syncProgress.setPadding(0, dp(14), 0, dp(18));
        syncCover.addView(syncProgress, new LinearLayout.LayoutParams(-1, -2));
        coverDone = PausaUi.action(this, "Volver a Dinero", true, this::finish);
        syncCover.addView(coverDone, new LinearLayout.LayoutParams(-2, dp(52)));
        viewSaved = PausaUi.quiet(this, "Ver lecturas guardadas", PausaUi.GREEN, () -> startActivity(new Intent(this, AbancaArchiveActivity.class)));
        syncCover.addView(viewSaved, new LinearLayout.LayoutParams(-2, dp(48)));
        syncCancel = PausaUi.quiet(this, "Mostrar la web y detener", PausaUi.MUTED, () -> {
            if (abancaSync != null && abancaSync.isActive()) abancaSync.cancel();
            else { syncCover.setVisibility(View.GONE); capture.setEnabled(!loadFailed && browser != null && bank.allows(browser.getUrl())); }
        }); syncCover.addView(syncCancel, new LinearLayout.LayoutParams(-2, dp(48)));
        browserFrame.addView(syncCover, new FrameLayout.LayoutParams(-1, -1));
        abancaSync = new AbancaSyncController(this, browser, readerScript, linksScript, (cover, cancellable, message) -> {
            if (isFinishing() || isDestroyed()) return;
            boolean active = abancaSync != null && abancaSync.isActive();
            boolean waiting = abancaSync != null && abancaSync.isWaitingForLogin();
            boolean done = cover && !active && cancellable;
            status.setText(waiting ? "Entra con tus claves y completa la verificación de ABANCA. En cuanto aparezca tu posición global, Pausa empieza a leer sola."
                    : done ? "Lectura terminada. Puedes volver a Dinero." : message);
            syncProgress.setText(message);
            if (cover && syncCover.getVisibility() != View.VISIBLE) PausaUi.rise(syncCover, 0);
            syncCover.setVisibility(cover ? View.VISIBLE : View.GONE);
            int steps = abancaSync == null ? 0 : abancaSync.steps();
            coverBar.set(done ? 1f : steps > 0 ? abancaSync.step() / (float) steps : -1f);
            coverTitle.setText(done ? "Listo" : "Leyendo tus productos");
            coverIcon.setImageDrawable(new PausaUi.Symbol(this, done ? "check" : "bank", PausaUi.GREEN, 40));
            if (done) PausaUi.pop(coverIcon);
            coverDone.setVisibility(done ? View.VISIBLE : View.GONE);
            viewSaved.setVisibility(done ? View.VISIBLE : View.GONE);
            syncCancel.setVisibility(done ? View.GONE : View.VISIBLE);
            syncCancel.setEnabled(cancellable);
            markStep(waiting || !active && !done ? 0 : done ? 2 : 1);
            syncButton.setText(active ? "Detener la lectura" : "Leer mis productos ahora");
            syncButton.setEnabled(cancellable);
            syncButton.setVisibility(waiting || cover ? View.GONE : View.VISIBLE);
            capture.setEnabled(!cover && !active && !reading && !loadFailed && browser != null && bank.allows(browser.getUrl()));
        });
        syncButton.setEnabled(true);
        markStep(0);
    }

    private void markStep(int current) {
        for (int i = 0; i < stepViews.length; i++) {
            if (stepViews[i] == null) continue;
            boolean on = i == current, past = i < current;
            stepViews[i].setTextColor(on ? PausaUi.SURFACE : past ? PausaUi.GREEN : PausaUi.MUTED);
            stepViews[i].setBackground(PausaUi.surface(this, on ? PausaUi.GREEN : past ? PausaUi.SAGE_SOFT : 0x80FFFCF6, 12));
            stepViews[i].setSelected(on);
        }
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
        if (abancaSync != null) abancaSync.navigating(url);
        if (bank.allows(url)) return false;
        status.setText("Este enlace abre otro destino. Usa Ayuda para acceder con el navegador del sistema si el banco lo necesita.");
        return true;
    }

    private void capture() {
        if (browser == null || reading || (abancaSync != null && abancaSync.isActive()) || !bank.allows(browser.getUrl())) return;
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
                if ("no_records".equals(data.optString("status"))) {
                    status.setText("Página reconocida, pero sin registros que se puedan guardar. La captura anterior se conserva.");
                    return;
                }
                if (!"captured".equals(data.optString("status"))) {
                    status.setText("No se encontraron datos legibles aquí. Abre el resumen o los movimientos. La captura anterior se conserva.");
                    return;
                }
                if (bank == BankProvider.ABANCA) data = AbancaSnapshot.prepare(data);
                data.put("schema", 1);
                data.put("bank", bank.id);
                data.put("capturedAt", System.currentTimeMillis());
                // Keep only the origin: never persist query parameters, fragments or path tokens.
                data.put("url", "https://" + Uri.parse(url).getHost() + "/");
                BankSnapshotStore.save(this, bank, data);
                if (bank == BankProvider.ABANCA) {
                    status.setText(AbancaSnapshot.title(data) + ": " + data.getJSONArray("rows").length()
                            + " registros guardados. Esta página sustituye la captura anterior; no incluye todo el historial."
                            + (data.optInt("omittedRows") > 0 ? " Algunas filas no se pudieron interpretar." : "")
                            + (data.optBoolean("truncated") ? " Se alcanzó el límite de lectura o de texto." : ""));
                } else status.setText("Captura guardada para consultar sin conexión. Solo incluye datos cargados de esta página, no todo el historial.");
            } catch (Exception error) {
                status.setText("No se pudo guardar la captura. Tus datos anteriores se conservan; no se ha registrado información bancaria en los logs.");
            }
        });
    }

    private String readAsset() throws Exception {
        return readAsset(bank == BankProvider.ABANCA ? "banking/read-abanca.js" : "banking/read-visible.js");
    }

    private String readAsset(String path) throws Exception {
        try (InputStream input = getAssets().open(path);
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
        if (abancaSync != null) abancaSync.pause();
        flushCookies();
        if (browser != null) browser.onPause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (browser != null && browser.canGoBack()) browser.goBack(); else super.onBackPressed();
    }

    private void destroyBrowser() {
        if (abancaSync != null) { abancaSync.close(); abancaSync = null; }
        if (browser == null) return;
        browser.stopLoading();
        if (browser.getParent() != null) ((ViewGroup) browser.getParent()).removeView(browser);
        browser.destroy();
        browser = null;
    }

    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); destroyBrowser(); super.onDestroy(); }
    private int dp(int value) { return PausaUi.dp(this, value); }
}
