package com.sejio.calorapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Hidden screen (long press on the status in Ajustes → Nube, or on the version in Ajustes) with
 * what a debugging session needs: sync state, pending changes, last error, version and the tail
 * of the local log, plus buttons to force a sync and send the logs.
 */
public final class DiagnosticsActivity extends Activity {
    private LinearLayout content;
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        PausaLog.init(this);
        PausaUi.Scroll scroll = new PausaUi.Scroll(this);
        scroll.setBackgroundColor(PausaUi.CREAM);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = PausaUi.dp(this, 20);
        content.setPadding(inset, inset, inset, inset);
        scroll.setOnApplyWindowInsetsListener((view, window) -> {
            view.setPadding(0, window.getSystemWindowInsetTop(), 0, window.getSystemWindowInsetBottom()); return window;
        });
        scroll.addView(content);
        setContentView(scroll);
        render();
    }

    private void render() {
        content.removeAllViews();
        content.addView(PausaUi.quiet(this, "Volver", PausaUi.GREEN, this::finish));
        content.addView(PausaUi.editorial(this, "Diagnóstico", 28));
        PausaDb db = PausaDb.get(this);
        StringBuilder state = new StringBuilder();
        line(state, "Versión", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" + (BuildConfig.DEBUG ? " debug" : ""));
        line(state, "Servidor", BuildConfig.PAUSA_API_URL);
        line(state, "Estado", PausaSync.status(this));
        line(state, "Cuenta", CloudAuth.account(this).isEmpty() ? "—" : CloudAuth.account(this));
        line(state, "Dispositivo", CloudAuth.deviceId(this).isEmpty() ? "—" : CloudAuth.deviceId(this));
        if (CloudAuth.authError(this)) line(state, "Sesión", "rechazada: " + CloudAuth.prefs(this).getString("auth_error_code", ""));
        line(state, "Cursor", db.state("cursor", "0"));
        line(state, "Último éxito", time(db.state("last_success_at", "0")));
        line(state, "Último error", db.state("last_error", "").isEmpty() ? "—" : time(db.state("last_error_at", "0")) + "\n  " + db.state("last_error", ""));
        line(state, "Importado", time(db.state("imported", "0")));
        StringBuilder pending = new StringBuilder();
        for (Map<String, Object> row : db.rows("SELECT tbl, COUNT(*) AS n FROM outbox GROUP BY tbl ORDER BY tbl")) {
            pending.append(pending.length() == 0 ? "" : ", ").append(row.get("tbl")).append(' ').append(row.get("n"));
        }
        line(state, "Pendientes", pending.length() == 0 ? "ninguno" : pending.toString());
        line(state, "Logs por enviar", String.valueOf(db.count("SELECT COUNT(*) FROM log_queue")));
        StringBuilder counts = new StringBuilder();
        for (String table : SyncSchema.TABLES.keySet()) {
            counts.append(counts.length() == 0 ? "" : ", ").append(table).append(' ').append(db.count("SELECT COUNT(*) FROM " + table + " WHERE deleted = 0"));
        }
        line(state, "Registros", counts.toString());
        content.addView(block(state.toString()));

        content.addView(PausaUi.action(this, "Forzar sincronización", true, () -> {
            PausaLog.i("diagnostics", "Sync forced from the diagnostics screen");
            PausaSync.requestSoon(this);
        }));
        content.addView(PausaUi.quiet(this, "Enviar logs al servidor", PausaUi.GREEN, () -> {
            PausaLog.event("info", "sync", "Logs sent from the diagnostics screen", null);
            PausaSync.requestSoon(this);
        }));
        content.addView(PausaUi.quiet(this, "Actualizar", PausaUi.GREEN, this::render));

        TextView title = PausaUi.text(this, "Log local (" + PausaLog.file(this).getAbsolutePath() + ")", 12, PausaUi.MUTED, false);
        title.setPadding(0, PausaUi.dp(this, 16), 0, PausaUi.dp(this, 8));
        content.addView(title);
        String tail = PausaLog.tail(this, 12000);
        content.addView(block(tail.isEmpty() ? "Vacío." : tail));
    }

    private TextView block(String text) {
        TextView view = PausaUi.text(this, text, 11, PausaUi.INK, false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        int pad = PausaUi.dp(this, 12);
        view.setPadding(pad, pad, pad, pad);
        view.setBackground(PausaUi.card(this));
        return view;
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append(label).append(": ").append(value).append('\n');
    }

    private static String time(String millis) {
        long at = Long.parseLong(millis);
        return at == 0 ? "—" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(at)) + " (" + PausaSync.ago(at) + ")";
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PausaSync.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changed, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(changed, filter);
    }

    @Override protected void onStop() {
        unregisterReceiver(changed);
        super.onStop();
    }
}
