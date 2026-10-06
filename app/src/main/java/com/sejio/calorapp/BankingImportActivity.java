package com.sejio.calorapp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-selected documents only. No bank calls, shared-storage writes or exported entry point. */
public final class BankingImportActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private BankingImport.Parsed parsed;
    private boolean busy;
    private long generation;
    private static final int PICK = 1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = PausaUi.dp(this, 20); content.setPadding(pad, pad, pad, pad); content.setBackgroundColor(PausaUi.CREAM);
        scroll.addView(content); setContentView(scroll); home();
    }
    @Override protected void onResume() { super.onResume(); if (parsed != null && !busy) preview(); }
    private boolean valid(long version) { return !isFinishing() && !isDestroyed() && version == generation; }
    private void paragraph(String value) {
        android.widget.TextView text = PausaUi.text(this, value, 14, PausaUi.INK, false);
        text.setPadding(0, PausaUi.dp(this, 10), 0, PausaUi.dp(this, 10)); content.addView(text);
    }
    private void heading(String title) { content.removeAllViews(); content.addView(PausaUi.editorial(this, title, 26)); }
    private void home() {
        parsed = null; heading("Cargar histórico inicial");
        paragraph("Selecciona uno de tus archivos de movimientos o el detalle del préstamo. Revisarás su contenido y el producto al que pertenece antes de guardarlo en este teléfono.");
        paragraph("Primero sincroniza cada banco para que aparezcan sus cuentas, tarjetas y préstamos. Esta carga complementa la sincronización habitual.");
        content.addView(PausaUi.action(this, "Seleccionar CSV o TXT", true, this::pick));
        content.addView(PausaUi.quiet(this, "Volver", PausaUi.GREEN, this::finish));
    }
    private void pick() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, PICK); }
        catch (android.content.ActivityNotFoundException error) { paragraph("No hay un selector de documentos disponible en este teléfono."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null || busy) return;
        Uri uri = data.getData(); if (!"content".equals(uri.getScheme())) { paragraph("Selecciona un documento desde el selector de archivos."); return; }
        long version = ++generation; busy = true; parsed = null; heading("Leyendo archivo…");
        worker.execute(() -> {
            byte[] bytes = null;
            try {
                try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (input == null) throw new IllegalArgumentException();
                    byte[] chunk = new byte[8192]; int length;
                    while ((length = input.read(chunk)) != -1) {
                        if (output.size() + length > BankingImport.MAX_BYTES) throw new BankingImport.Invalid("El archivo supera los 2 MiB.");
                        output.write(chunk, 0, length);
                    }
                    Arrays.fill(chunk, (byte) 0); bytes = output.toByteArray();
                }
                BankingImport.Parsed value = BankingImport.parse(bytes);
                runOnUiThread(() -> { if (valid(version)) { busy = false; parsed = value; preview(); } });
            } catch (Exception error) { failure(version, error, "No se pudo leer el archivo. No se ha guardado ningún dato."); }
            finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
        });
    }
    private void preview() {
        final BankingImport.Parsed value = parsed; if (value == null || busy) return;
        long version = ++generation; busy = true; heading("Preparando vista previa…");
        worker.execute(() -> {
            try {
                BankingDatabase db = BankingDatabase.get(getApplicationContext());
                JSONArray products = db.importProducts(value.bank, value.productType);
                runOnUiThread(() -> {
                    if (!valid(version)) return; busy = false; heading("Revisar histórico"); paragraph(value.summary());
                    paragraph("Se conservarán todas las filas y su archivo de origen, incluidos los campos adicionales. Repetir el mismo archivo no vuelve a añadirlo.");
                    if (products.length() == 0) {
                        paragraph("No hay un producto sincronizado compatible. Sincroniza el banco y vuelve aquí para asociar este archivo a su cuenta, tarjeta o préstamo.");
                        content.addView(PausaUi.action(this, "Sincronizar " + (value.bank.equals("abanca") ? "ABANCA" : "Trade Republic"), true, () -> {
                            if (value.bank.equals("abanca")) startActivity(new Intent(this, BankBrowserActivity.class)
                                    .putExtra(BankBrowserActivity.EXTRA_BANK, "abanca").putExtra(BankBrowserActivity.EXTRA_SYNC, true));
                            else startActivity(new Intent(this, TradeRepublicActivity.class));
                        }));
                    } else {
                        paragraph("Elige el producto al que pertenece el archivo. Las tarjetas de crédito y débito se guardan por separado.");
                        ArrayList<String> labels = new ArrayList<>(); labels.add("Selecciona un producto…");
                        for (int i = 0; i < products.length(); i++) { JSONObject p = products.optJSONObject(i);
                            labels.add(p.optString("label") + (p.optString("kind").isEmpty() ? "" : " · " + p.optString("kind"))); }
                        Spinner choice = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); choice.setAdapter(adapter); content.addView(choice);
                        content.addView(PausaUi.action(this, "Guardar en este producto", true, () -> {
                            int index = choice.getSelectedItemPosition() - 1;
                            if (index < 0) { PausaUi.snack(this, "Selecciona el producto de destino.", null, null); return; }
                            save(value, products.optJSONObject(index));
                        }));
                    }
                    content.addView(PausaUi.quiet(this, "Elegir otro archivo", PausaUi.GREEN, this::pick));
                    paragraph("Muestra de los primeros registros:");
                    for (int i = 0; i < Math.min(5, value.records.length()); i++) {
                        JSONObject r = value.records.optJSONObject(i), money = r.optJSONObject("amount");
                        paragraph(money == null ? r.optString("section") + " · " + r.optString("label") + ": " + r.optString("value")
                                : (r.isNull("operationDate") ? r.optString("bookingDate") + " (contable)" : r.optString("operationDate")) + " · " + r.optString("description") + " · " + money.optString("amount") + " " + money.optString("currency"));
                    }
                });
            } catch (Exception error) { failure(version, error, "No se pudieron consultar los productos guardados. No se ha importado el archivo."); }
        });
    }
    private void save(BankingImport.Parsed value, JSONObject product) {
        if (busy) return; busy = true; long version = ++generation; heading("Guardando histórico…");
        worker.execute(() -> {
            try {
                JSONObject result = BankingDatabase.get(getApplicationContext()).importFile(value, product, System.currentTimeMillis());
                runOnUiThread(() -> {
                    if (!valid(version)) return; busy = false; parsed = null; heading(result.optBoolean("saved") ? "Histórico guardado" : "Archivo ya guardado");
                    paragraph(result.optBoolean("saved") ? result.optInt("observations") + " registros conservados · " + result.optInt("newEntities")
                            + " registros nuevos identificados. Las coincidencias conservan también su origen; los datos que ya existían no se sustituyen."
                            : "Este archivo ya se había incorporado a este producto. No se ha duplicado.");
                    content.addView(PausaUi.action(this, "Ver datos guardados", true, () -> startActivity(new Intent(this, AbancaArchiveActivity.class)
                            .putExtra(AbancaArchiveActivity.EXTRA_BANK, value.bank))));
                    content.addView(PausaUi.action(this, "Cargar otro archivo", false, this::home));
                    content.addView(PausaUi.quiet(this, "Terminar", PausaUi.GREEN, this::finish));
                });
            } catch (Exception error) { failure(version, error, "No se pudo guardar el archivo. Se ha revertido esta carga completa; los datos anteriores se conservan."); }
        });
    }
    private void failure(long version, Exception error, String fallback) {
        String message = error instanceof BankingImport.Invalid ? error.getMessage() : fallback;
        runOnUiThread(() -> { if (valid(version)) { busy = false; parsed = null; heading("Archivo sin importar"); paragraph(message);
            content.addView(PausaUi.action(this, "Seleccionar archivo", true, this::pick));
            content.addView(PausaUi.quiet(this, "Volver", PausaUi.GREEN, this::finish)); } });
    }
    // A save already requested finishes atomically even if the screen is closed or recreated.
    @Override protected void onDestroy() { generation++; parsed = null; worker.shutdown(); super.onDestroy(); }
}
