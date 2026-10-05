package com.sejio.calorapp;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

/** Offline-first landing page: viewing snapshots never creates or refreshes a bank session. */
final class BankingView extends LinearLayout {
    private final LinearLayout content;

    BankingView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        ScrollView scroll = new PausaUi.Scroll(context);
        scroll.setTag(PausaUi.SCROLL_TAG);
        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), dp(20));
        scroll.addView(content);
        addView(scroll, new LayoutParams(-1, -1));
        // Decrypt only when this section is actually opened.
    }

    void refresh() {
        content.removeAllViews();
        content.addView(PausaUi.editorial(getContext(), "Banking", 30));
        paragraph(content, "Tus bancos, en tu teléfono. Consulta lo guardado sin volver a iniciar sesión.", false);
        for (BankProvider bank : BankProvider.values()) card(bank);
        content.addView(PausaUi.action(getContext(), "Cargar histórico inicial", false, () -> getContext().startActivity(
                new Intent(getContext(), BankingImportActivity.class))));
    }

    private void card(BankProvider bank) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(PausaUi.card(context));
        LayoutParams params = new LayoutParams(-1, -2);
        params.topMargin = dp(16);
        content.addView(card, params);
        card.addView(PausaUi.editorial(context, bank.label, 24));
        if (bank == BankProvider.TRADE_REPUBLIC) {
            paragraph(card, "Cliente directo: confirma el acceso en Trade Republic y vuelve para consultar saldo y movimientos.", false);
            card.addView(PausaUi.action(context, "Conectar y sincronizar", true, () -> context.startActivity(
                    new Intent(context, TradeRepublicActivity.class))));
            card.addView(PausaUi.quiet(context, "Consultas guardadas en la base de datos", PausaUi.GREEN, () -> context.startActivity(
                    new Intent(context, AbancaArchiveActivity.class).putExtra(AbancaArchiveActivity.EXTRA_BANK, bank.id))));
            card.addView(PausaUi.quiet(context, "Prueba anterior en navegador", PausaUi.GREEN, () -> context.startActivity(
                    new Intent(context, BankBrowserActivity.class).putExtra(BankBrowserActivity.EXTRA_BANK, bank.id))));
            card.addView(PausaUi.quiet(context, "Olvidar sesiones de la prueba anterior", PausaUi.TERRACOTTA, () -> confirmForget(bank)));
            return;
        }
        card.addView(PausaUi.action(context, "Conectar y sincronizar ABANCA", true, () -> context.startActivity(
                new Intent(context, BankBrowserActivity.class).putExtra(BankBrowserActivity.EXTRA_BANK, bank.id)
                        .putExtra(BankBrowserActivity.EXTRA_SYNC, true))));
        card.addView(PausaUi.action(context, "Ver sincronizaciones", false, () -> context.startActivity(
                new Intent(context, AbancaArchiveActivity.class))));
        paragraph(card, "Completa el acceso si lo pide. Pausa consulta los productos y conserva cada lectura en la base de datos del teléfono.", false);
        card.addView(PausaUi.quiet(context, "Abrir solo la web", PausaUi.GREEN, () -> context.startActivity(
                new Intent(context, BankBrowserActivity.class).putExtra(BankBrowserActivity.EXTRA_BANK, bank.id))));
        card.addView(PausaUi.quiet(context, "Olvidar sesión en este teléfono", PausaUi.TERRACOTTA, () -> confirmForget(bank)));
    }

    private void confirmForget(BankProvider bank) {
        new AlertDialog.Builder(getContext()).setTitle("¿Olvidar la sesión de " + bank.label + "?")
                .setMessage("Se eliminarán las cookies y los datos del navegador de este banco en Pausa. La captura guardada se conserva. "
                        + "Las consultas de la base de datos también se conservan. Esto no revoca la sesión en el banco; para eso utiliza sus opciones de seguridad.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Olvidar", (dialog, which) -> forget(bank)).show();
    }

    @SuppressLint("RequiresFeature") private void forget(BankProvider bank) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            PausaUi.snack(getContext(), "Actualiza WebView para gestionar los perfiles bancarios.", null, null);
            return;
        }
        try {
            ProfileStore profiles = ProfileStore.getInstance();
            if (profiles.getAllProfileNames().contains(bank.profileName()) && !profiles.deleteProfile(bank.profileName()))
                throw new IllegalStateException("Perfil en uso");
            if (bank == BankProvider.TRADE_REPUBLIC) {
                for (BankBrowserMode mode : BankBrowserMode.values()) {
                    String name = mode.profileName(bank);
                    if (profiles.getAllProfileNames().contains(name) && !profiles.deleteProfile(name))
                        throw new IllegalStateException("Perfil de prueba en uso");
                }
            }
            PausaUi.snack(getContext(), "Sesión local eliminada.", null, null);
        } catch (Exception error) {
            PausaUi.snack(getContext(), "No se pudo eliminar la sesión. Cierra el navegador del banco e inténtalo otra vez.", null, null);
        }
    }

    private void paragraph(LinearLayout parent, String value, boolean bold) {
        android.widget.TextView text = PausaUi.text(getContext(), value, 13, bold ? PausaUi.INK : PausaUi.MUTED, bold);
        text.setPadding(0, dp(8), 0, dp(8));
        parent.addView(text, new LayoutParams(-1, -2));
    }

    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
