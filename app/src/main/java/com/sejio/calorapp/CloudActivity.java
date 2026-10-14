package com.sejio.calorapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Ajustes → Nube: connect the phone to the Pausa backend, see the sync state, disconnect. */
public final class CloudActivity extends Activity {
    private LinearLayout content;
    private TextView status;
    private boolean busy;
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (status != null) status.setText(PausaSync.status(context)); }
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
        content.addView(PausaUi.editorial(this, "Pausa en la nube", 28));
        TextView description = PausaUi.text(this, "Tus tareas, el plan, los hábitos y los contadores se guardan primero en el teléfono "
                + "y se sincronizan cuando hay cobertura. Así también los ves en la web y los pueden usar tus agentes. "
                + "La banca nunca sale del teléfono.", 15, PausaUi.MUTED, false);
        description.setPadding(0, PausaUi.dp(this, 16), 0, PausaUi.dp(this, 20));
        content.addView(description);
        status = PausaUi.text(this, PausaSync.status(this), 14, PausaUi.GREEN, true);
        status.setPadding(PausaUi.dp(this, 16), PausaUi.dp(this, 16), PausaUi.dp(this, 16), PausaUi.dp(this, 16));
        status.setBackground(PausaUi.card(this));
        // Hidden door to the diagnostics screen.
        status.setOnLongClickListener(v -> { startActivity(new Intent(this, DiagnosticsActivity.class)); return true; });
        content.addView(status);
        String account = CloudAuth.account(this);
        if (!account.isEmpty()) {
            TextView identity = PausaUi.text(this, account, 13, PausaUi.MUTED, false);
            identity.setPadding(0, PausaUi.dp(this, 12), 0, PausaUi.dp(this, 12));
            content.addView(identity);
        }
        boolean connected = CloudAuth.signedIn(this);
        if (connected) {
            content.addView(PausaUi.action(this, "Sincronizar ahora", true, () -> { PausaSync.requestSoon(this); status.setText("Sincronizando…"); }));
            content.addView(PausaUi.quiet(this, "Desconectar este teléfono", PausaUi.TERRACOTTA, () -> { CloudAuth.signOut(this); render(); }));
        } else {
            content.addView(PausaUi.action(this, CloudAuth.authError(this) ? "Volver a conectar con Google" : "Conectar con Google", true, this::connect));
        }
        TextView note = PausaUi.text(this, "Solo entra la cuenta de Google autorizada. Desconectar no borra nada del teléfono. "
                + "Mantén pulsado el estado para ver el diagnóstico.", 12, PausaUi.MUTED, false);
        note.setPadding(0, PausaUi.dp(this, 12), 0, PausaUi.dp(this, 12));
        content.addView(note);
    }

    private void connect() {
        if (busy) return;
        busy = true;
        status.setText("Abriendo Google…");
        CloudAuth.signIn(this, error -> {
            busy = false;
            if (isFinishing()) return;
            render();
            if (error != null) status.setText(error);
        });
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PausaSync.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changed, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(changed, filter);
        if (status != null && !busy) status.setText(PausaSync.status(this));
    }

    @Override protected void onStop() {
        unregisterReceiver(changed);
        super.onStop();
    }
}
