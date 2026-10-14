package com.sejio.calorapp;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.job.JobScheduler;
import android.content.Intent;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.common.api.ApiException;
import java.util.Collections;

public final class CalendarConnectionActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private String account = "";
    private TextView status;
    private LinearLayout content;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        account = saved == null ? CalendarSync.prefs(this).getString("account", "") : saved.getString("account", "");
        PausaUi.Scroll scroll = new PausaUi.Scroll(this);
        scroll.setBackgroundColor(PausaUi.CREAM);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int inset = PausaUi.dp(this, 20);
        content.setPadding(inset, inset, inset, inset);
        scroll.setOnApplyWindowInsetsListener((view, window) -> {
            view.setPadding(0, window.getSystemWindowInsetTop(), 0, window.getSystemWindowInsetBottom()); return window;
        });
        scroll.addView(content); setContentView(scroll);
        render();
    }

    private void render() {
        content.removeAllViews();
        content.addView(PausaUi.quiet(this, "Volver", PausaUi.GREEN, this::finish));
        content.addView(PausaUi.editorial(this, "Tu tiempo, conectado", 28));
        TextView description = PausaUi.text(this, "Las tareas que guardes con día y hora aparecerán en un calendario propio llamado Pausa. "
                + "Los cambios se envían desde esta app. Las tareas sin horario siguen en tu lista.", 15, PausaUi.MUTED, false);
        description.setPadding(0, PausaUi.dp(this, 16), 0, PausaUi.dp(this, 20));
        content.addView(description);
        status = PausaUi.text(this, CalendarSync.status(this), 14, PausaUi.GREEN, true);
        status.setPadding(PausaUi.dp(this, 16), PausaUi.dp(this, 16), PausaUi.dp(this, 16), PausaUi.dp(this, 16));
        status.setBackground(PausaUi.card(this)); content.addView(status);
        if (!account.isEmpty()) {
            TextView identity = PausaUi.text(this, account, 13, PausaUi.MUTED, false);
            identity.setPadding(0, PausaUi.dp(this, 12), 0, PausaUi.dp(this, 12)); content.addView(identity);
        }
        content.addView(PausaUi.action(this, CalendarSync.enabled(this) ? "Revisar permisos y sincronizar" : "Conectar Google Calendar", true, this::connect));
        TextView permission = PausaUi.text(this, "Permisos: crear y gestionar los eventos del calendario Pausa y consultar los nombres de tus calendarios para recuperarlo. "
                + "Puedes desconectarlo cuando quieras. Los eventos existentes se conservarán.", 12, PausaUi.MUTED, false);
        permission.setPadding(0, PausaUi.dp(this, 12), 0, PausaUi.dp(this, 12)); content.addView(permission);
        if (CalendarSync.enabled(this)) content.addView(PausaUi.quiet(this, "Desconectar", PausaUi.TERRACOTTA, this::disconnect));
    }

    private void connect() {
        if (!account.isEmpty()) { authorize(); return; }
        try {
            startActivityForResult(AccountPicker.newChooseAccountIntent(new AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(Collections.singletonList("com.google")).setAlwaysShowAccountPicker(true).build()), 601);
        } catch (Exception failure) { status.setText("Necesitas Google Play Services y una cuenta de Google en el teléfono."); }
    }

    private void authorize() {
        status.setText("Comprobando los permisos de Calendar…");
        Identity.getAuthorizationClient(this).authorize(CalendarSync.authorization(account))
                .addOnSuccessListener(this, this::authorized).addOnFailureListener(this, this::failed);
    }

    private void authorized(AuthorizationResult result) {
        if (result.hasResolution()) {
            try { startIntentSenderForResult(result.getPendingIntent().getIntentSender(), 602, null, 0, 0, 0); }
            catch (Exception failure) { failed(failure); }
            return;
        }
        if (!CalendarSync.hasScopes(result)) { status.setText("Falta el permiso de Calendar. Tus tareas siguen guardadas en Pausa."); return; }
        CalendarSync.prefs(this).edit().putString("account", account).putBoolean("enabled", true).apply();
        CalendarSync.request(this); render();
    }

    private void failed(Exception failure) {
        int code = failure instanceof ApiException ? ((ApiException) failure).getStatusCode() : -1;
        status.setText(code == 10 ? "Falta registrar esta versión de Pausa en Google Cloud. La conexión aún no está configurada."
                : "No se pudo autorizar Calendar. Puedes volver a intentarlo. Código " + code);
    }

    private void disconnect() {
        CalendarSync.prefs(this).edit().putBoolean("enabled", false).putString("status", "Desconectado · eventos conservados").apply();
        JobScheduler scheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(CalendarSync.JOB);
        Identity.getAuthorizationClient(this).revokeAccess(RevokeAccessRequest.builder()
                .setAccount(new Account(account, "com.google")).setScopes(CalendarSync.scopes()).build())
                .addOnFailureListener(this, error -> status.setText("Sincronización detenida. No se pudo revocar el permiso; puedes retirarlo desde tu cuenta Google."));
        render();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) { status.setText("Conexión cancelada · tus tareas siguen guardadas"); return; }
        if (request == 601) {
            String selected = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (selected == null || selected.isEmpty()) return;
            account = selected; authorize();
        } else if (request == 602) {
            try { authorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)); }
            catch (Exception failure) { failed(failure); }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) { state.putString("account", account); super.onSaveInstanceState(state); }
    @Override protected void onStart() { super.onStart(); CalendarSync.prefs(this).registerOnSharedPreferenceChangeListener(this); }
    @Override protected void onStop() { CalendarSync.prefs(this).unregisterOnSharedPreferenceChangeListener(this); super.onStop(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if ("status".equals(key)) status.setText(CalendarSync.status(this));
    }
}
