package com.sejio.calorapp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real WebView, one explicitly requested pass. No login submission, retries or token export. */
final class AbancaSyncController {
    interface Host { void update(boolean coverWeb, boolean cancellable, String message); }
    private final Context context;
    private final WebView web;
    private final String reader, links;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean active, waitingLogin, reading, committing, closed, runQueued;
    private long generation, navigation;
    private String expected, runId;
    private long startedAt;
    private AbancaSyncPolicy.Plan plan;
    private AbancaSyncPolicy.Target current;
    private int index, saved;
    private final Runnable pageTimeout = () -> stop("La consulta tardó demasiado. Lo que ya se había leído queda guardado; revisa la web.", true, "interrupted");
    private final Runnable totalTimeout = () -> stop("Se alcanzó el tiempo de esta lectura. Las páginas obtenidas quedan guardadas.", true, "interrupted");

    AbancaSyncController(Context context, WebView web, String reader, String links, Host host) {
        this.context = context.getApplicationContext(); this.web = web; this.reader = reader; this.links = links; this.host = host;
    }
    boolean isActive() { return active; }
    /** Waiting for the owner to finish the bank's login, before any reading. */
    boolean isWaitingForLogin() { return active && waitingLogin; }
    /** Products read so far and planned in this pass; 0 of 0 until the summary is recognised. */
    int step() { return plan == null ? 0 : Math.max(0, index - 1); }
    int steps() { return plan == null ? 0 : plan.targets.size(); }
    boolean isCommitting() { return committing; }

    void request() {
        if (closed || active) return;
        long now = System.currentTimeMillis();
        android.content.SharedPreferences prefs = context.getSharedPreferences("abanca_sync", Context.MODE_PRIVATE);
        long elapsed = now - prefs.getLong("last_attempt", 0);
        if (elapsed >= 0 && elapsed < 60_000) { host.update(false, true, "Espera un minuto entre sincronizaciones. Los datos guardados siguen disponibles."); return; }
        prefs.edit().putLong("last_attempt", now).apply();
        active = true; waitingLogin = true; reading = false; committing = false; runQueued = false;
        generation++; index = 0; saved = 0; plan = null; current = null;
        runId = UUID.randomUUID().toString(); startedAt = now; expected = AbancaSyncPolicy.OVERVIEW;
        host.update(false, true, "Completa el acceso en ABANCA si lo pide. La lectura continuará automáticamente al llegar al resumen.");
        web.loadUrl(expected);
    }
    void pageStarted(String url) {
        navigation++; reading = false;
        if (active && !waitingLogin && !committing && !url.equals(expected))
            stop("El banco ha abierto otra página o pide una verificación. La lectura se ha detenido; completa el paso en la web.", false, "interrupted");
    }
    void navigating(String url) {
        if (active && !waitingLogin && !committing && !url.equals(expected))
            stop("La navegación necesita tu intervención. Las páginas ya consultadas se conservan.", false, "interrupted");
    }
    void pageFinished(String url) {
        if (!active || reading || committing || !url.equals(web.getUrl())) return;
        if (waitingLogin) {
            if (!"overview".equals(AbancaSyncPolicy.pageType(url))) return;
            expected = url;
        } else if (!url.equals(expected)) return;
        reading = true;
        long version = generation, nav = navigation; String address = expected;
        main.removeCallbacks(pageTimeout); main.postDelayed(pageTimeout, 25_000);
        web.evaluateJavascript(reader, result -> {
            if (!valid(version, nav, address)) return;
            try {
                JSONObject raw = new JSONObject(result); String status = raw.optString("status");
                if (!status.equals("captured") && !status.equals("no_records")) {
                    reading = false;
                    if (waitingLogin) { main.removeCallbacks(pageTimeout); host.update(false, true, "Completa el acceso o la verificación de ABANCA. La lectura espera a reconocer el resumen."); return; }
                    stop("No se pudo reconocer la página o el banco necesita tu intervención. Lo ya leído queda guardado.", false, "interrupted"); return;
                }
                boolean overview = waitingLogin;
                if (overview) {
                    waitingLogin = false; current = new AbancaSyncPolicy.Target("overview", "Resumen de productos", "", address);
                    main.postDelayed(totalTimeout, 120_000);
                }
                if (!current.type.equals(raw.optString("pageType"))) throw new IllegalArgumentException();
                host.update(true, true, overview ? "Guardando el resumen…" : "Guardando producto " + index + " de " + plan.targets.size() + "…");
                main.removeCallbacks(pageTimeout); main.postDelayed(pageTimeout, 25_000);
                persist(raw, overview, version, nav, address);
            } catch (Exception error) { stop("No se pudo interpretar la página. Los datos guardados se conservan.", false, "failed"); }
        });
    }
    private void persist(JSONObject raw, boolean overview, long version, long nav, String address) {
        final String run = runId; final long started = startedAt; final AbancaSyncPolicy.Target target = current;
        final long capturedAt = System.currentTimeMillis(); runQueued = true;
        worker.execute(() -> {
            try {
                BankingDatabase db = BankingDatabase.get(context);
                if (overview) db.begin(run, "abanca", "webview", started);
                JSONObject page = AbancaCapture.fromPage(target.label, target.kind, address, raw, capturedAt);
                db.capture(run, page);
                main.post(() -> {
                    if (!valid(version, nav, address)) return;
                    saved++; main.removeCallbacks(pageTimeout);
                    if (overview) discover(version, nav, address); else next();
                });
            } catch (Exception error) { main.post(() -> {
                if (valid(version, nav, address)) stop("No se pudo guardar esta página en la base de datos. La lectura se ha detenido; las anteriores se conservan.", true, "failed");
            }); }
        });
    }
    private void discover(long version, long nav, String address) {
        main.postDelayed(pageTimeout, 25_000);
        web.evaluateJavascript(links, result -> {
            if (!valid(version, nav, address)) return;
            try { plan = AbancaSyncPolicy.plan(new JSONObject(result)); main.removeCallbacks(pageTimeout); next(); }
            catch (Exception error) { stop("El resumen se ha guardado, pero no se pudieron identificar sus enlaces de consulta.", false, "interrupted"); }
        });
    }
    private void next() {
        reading = false;
        if (index >= plan.targets.size()) { complete(); return; }
        current = plan.targets.get(index++); expected = current.url;
        host.update(true, true, "Consultando producto " + index + " de " + plan.targets.size() + "…");
        main.removeCallbacks(pageTimeout); main.postDelayed(pageTimeout, 25_000);
        web.loadUrl(expected);
    }
    private void complete() {
        committing = true; main.removeCallbacks(pageTimeout); main.removeCallbacks(totalTimeout);
        host.update(true, false, "Finalizando la consulta guardada…");
        final String run = runId; final int skipped = plan.skipped; final long version = generation;
        worker.execute(() -> {
            boolean success;
            try { BankingDatabase.get(context).finish(run, "complete", skipped); success = true; }
            catch (Exception error) { success = false; }
            final boolean ok = success;
            main.post(() -> {
                if (closed || generation != version) return;
                active = false; committing = false; generation++;
                expected = null; current = null; plan = null;
                host.update(true, true, ok ? "Lectura terminada: " + saved + " páginas guardadas en la base de datos."
                        + (skipped > 0 ? " Algunos productos no se incluyeron." : "") + " Ya puedes volver a Dinero."
                        : "Las páginas leídas están guardadas, pero no se pudo marcar la consulta como terminada.");
            });
        });
    }
    private boolean valid(long version, long nav, String address) {
        return !closed && active && generation == version && navigation == nav && address.equals(web.getUrl());
    }
    void error() { if (active) stop("La web no pudo completar la consulta. No se reintentará automáticamente; los datos leídos se conservan.", false, "interrupted"); }
    void cancel() { stop("Lectura detenida. Lo que ya se había consultado queda guardado.", true, "interrupted"); }
    void pause() { if (active && !waitingLogin && !committing) cancel(); }
    private void stop(String message, boolean stopLoading, String status) {
        if (!active || committing) return;
        active = false; waitingLogin = false; reading = false; generation++;
        main.removeCallbacks(pageTimeout); main.removeCallbacks(totalTimeout);
        if (stopLoading) web.stopLoading();
        final String run = runId; final int skipped = plan == null ? 0 : plan.skipped;
        if (runQueued) worker.execute(() -> { try { BankingDatabase.get(context).finish(run, status, skipped); } catch (Exception ignored) { /* Already persisted captures survive. */ } });
        expected = null; current = null; plan = null;
        if (!closed) host.update(false, true, message);
    }
    void close() { if (!committing) cancel(); closed = true; generation++; main.removeCallbacksAndMessages(null); worker.shutdown(); }
}
