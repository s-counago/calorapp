package com.sejio.calorapp;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.sejio.calorapp.trade.TradeException;
import com.sejio.calorapp.trade.TradeRepublicClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One serial client per app process. Activities observe it; they do not own the login. */
final class TradeRepository {
    interface Listener { void changed(JSONObject view, String error, boolean busy); }
    interface Work { void run(TradeRepublicClient client) throws Exception; }
    private interface Task { void run() throws Exception; }
    // get() passes only applicationContext; no Activity or View is retained here.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static TradeRepository instance;
    static synchronized TradeRepository get(Context context) {
        if (instance == null) instance = new TradeRepository(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private TradeRepublicClient client;
    private volatile Listener listener;
    private volatile JSONObject view;
    private volatile String error = "";

    private TradeRepository(Context context) { this.context = context; }
    void observe(Listener listener) { this.listener = listener; notifyUi(); if (view == null) execute(c -> {}); }
    void detach(Listener old) { if (listener == old) listener = null; }
    JSONObject current() { return view; }
    String error() { return error; }
    boolean busy() { return busy.get(); }

    void execute(Work work) {
        submit(() -> {
            if (client == null) client = createClient();
            work.run(client);
        });
    }

    void forget() {
        submit(() -> {
            // Recovery also works when an unreadable vault prevented client construction.
            new TradeVault(context).erase();
            if (client != null) client.close();
            client = null; view = null;
            client = createClient();
        });
    }

    private void submit(Task task) {
        if (!busy.compareAndSet(false, true)) return;
        error = ""; notifyUi();
        worker.execute(() -> {
            try {
                task.run();
            } catch (TradeException failure) { error = failure.getMessage() + " [" + failure.code + "]"; }
            catch (Exception failure) { error = "No se pudo completar la operación. La copia anterior se conserva. [CLIENT]"; }
            finally {
                if (client != null) try { view = client.view(); } catch (Exception ignored) { }
                busy.set(false); notifyUi();
            }
        });
    }

    private TradeRepublicClient createClient() throws Exception {
        // Compatibility identity for the web protocol, not the native banking-app protocol.
        String chrome = "146.0.0.0";
        String agent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome + " Safari/537.36";
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        JSONObject device = new JSONObject().put("browser", "Chrome").put("browserVersion", chrome)
                .put("os", "Android").put("osVersion", Build.VERSION.RELEASE)
                .put("timezone", TimeZone.getDefault().getID())
                .put("timezoneOffset", -TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
                .put("screen", metrics.widthPixels + "x" + metrics.heightPixels + "x24")
                .put("preferredLanguages", new JSONArray().put(Locale.getDefault().toLanguageTag()))
                .put("numberOfCores", Runtime.getRuntime().availableProcessors());
        return new TradeRepublicClient(new TradeVault(context), agent, device);
    }

    private void notifyUi() {
        main.post(() -> { Listener active = listener; if (active != null) active.changed(view, error, busy.get()); });
    }
}
