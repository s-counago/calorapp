package com.sejio.calorapp;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small inspection screen for the ledger; reading it never contacts a bank. */
public final class AbancaArchiveActivity extends Activity {
    static final String EXTRA_BANK = "bank";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private long generation;
    private BankProvider bank;
    private int offset;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String id = getIntent().getStringExtra(EXTRA_BANK);
        try { bank = id == null ? BankProvider.ABANCA : BankProvider.fromId(id); }
        catch (IllegalArgumentException error) { finish(); return; }
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = PausaUi.dp(this, 20); content.setPadding(pad, pad, pad, pad); content.setBackgroundColor(PausaUi.CREAM);
        scroll.addView(content); setContentView(scroll); loadRuns();
    }
    private void paragraph(String value) {
        android.widget.TextView text = PausaUi.text(this, value, 14, PausaUi.INK, false);
        text.setPadding(0, PausaUi.dp(this, 10), 0, PausaUi.dp(this, 10)); content.addView(text);
    }
    private String when(long at) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(at)); }
    private interface Read { void run(long version) throws Exception; }
    private void load(Read read) {
        long version = ++generation; content.removeAllViews(); paragraph("Leyendo la base de datos local…");
        worker.execute(() -> {
            try { read.run(version); }
            catch (Exception error) { runOnUiThread(() -> { if (valid(version)) {
                content.removeAllViews(); paragraph("No se pudieron leer los datos. No se ha sustituido ni borrado ninguna copia.");
                content.addView(PausaUi.quiet(this, "Volver", PausaUi.GREEN, this::finish));
            }}); }
        });
    }
    private boolean valid(long version) { return !isFinishing() && !isDestroyed() && generation == version; }
    private void loadRuns() {
        load(version -> {
            BankingDatabase db = BankingDatabase.get(this);
            boolean migrationProblem = false;
            try { db.migrateLegacy(getApplicationContext(), bank); } catch (Exception error) { migrationProblem = true; }
            final boolean migrationFailed = migrationProblem;
            JSONArray runs = db.runs(bank.id, offset); JSONObject counts = db.counts(bank.id);
            runOnUiThread(() -> {
                if (!valid(version)) return;
                content.removeAllViews(); content.addView(PausaUi.editorial(this, bank.label + " · datos guardados", 26));
                paragraph(counts.optLong("movements") + " movimientos identificados · " + counts.optLong("sync_runs") + " consultas conservadas.");
                if (migrationFailed) paragraph("No se pudo recuperar una copia anterior. Se conserva para poder volver a intentarlo.");
                paragraph("Las consultas anteriores se conservan. Las lecturas parciales no eliminan productos ni convierten datos ausentes en cero.");
                if (runs.length() == 0) paragraph("Todavía no hay consultas guardadas aquí.");
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject run = runs.optJSONObject(i); String status = run.optString("status");
                    String state = status.equals("complete") ? "Lectura terminada" : status.equals("collecting") ? "En curso" : "Lectura interrumpida";
                    content.addView(PausaUi.action(this, when(run.optLong("capturedAt")) + " · " + state + " · " + run.optInt("pages") + " páginas", false,
                            () -> loadPages(run)));
                }
                if (offset > 0) content.addView(PausaUi.quiet(this, "Consultas más recientes", PausaUi.GREEN, () -> { offset = Math.max(0, offset - 30); loadRuns(); }));
                if (runs.length() == 30) content.addView(PausaUi.quiet(this, "Consultas anteriores", PausaUi.GREEN, () -> { offset += 30; loadRuns(); }));
                content.addView(PausaUi.quiet(this, "Volver", PausaUi.GREEN, this::finish));
            });
        });
    }
    private void loadPages(JSONObject run) {
        load(version -> {
            JSONArray pages = BankingDatabase.get(this).pages(run.optString("id"));
            runOnUiThread(() -> {
                if (!valid(version)) return;
                content.removeAllViews(); content.addView(PausaUi.editorial(this, "Consulta · " + when(run.optLong("capturedAt")), 24));
                if (run.optInt("skipped") > 0) paragraph(run.optInt("skipped") + " productos no incluidos en esta lectura.");
                if (pages.length() == 0) paragraph("La consulta no llegó a guardar ninguna página.");
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject page = pages.optJSONObject(i), product = page.optJSONObject("product");
                    content.addView(PausaUi.action(this, product.optString("label") + (product.optString("kind").isEmpty() ? "" : " · " + product.optString("kind")), false,
                            () -> show(page)));
                }
                content.addView(PausaUi.quiet(this, "Volver a consultas", PausaUi.GREEN, this::loadRuns));
            });
        });
    }
    private void show(JSONObject page) {
        generation++; content.removeAllViews();
        content.addView(PausaUi.editorial(this, page.optJSONObject("product").optString("label"), 25));
        paragraph("Leído: " + when(page.optLong("capturedAt")));
        paragraph("Origen: " + page.optString("sourcePath") + "\nLector: " + page.optString("parserVersion"));
        if (page.optBoolean("sourceTruncated")) paragraph("La página superó el límite de captura. No está completa.");
        if (page.optInt("omittedRows") > 0) paragraph(page.optInt("omittedRows") + " filas no pudieron normalizarse; consulta la tabla original.");
        content.addView(PausaUi.quiet(this, "Volver a consultas", PausaUi.GREEN, this::loadRuns));
        JSONObject normalized = page.optJSONObject("normalized");
        if (normalized != null) {
            paragraph(AbancaSnapshot.title(normalized));
            if (normalized.optBoolean("truncated")) paragraph("La vista normalizada está limitada. La captura de las tablas se conserva por separado.");
            showRows(normalized.optJSONArray("rows"), 0);
        }
        if (normalized == null && page.optJSONObject("legacySnapshot") != null)
            showRows(page.optJSONObject("legacySnapshot").optJSONArray("rows"), 0);
        JSONArray tables = page.optJSONArray("sourceTables");
        if (tables != null) for (int i = 0; i < tables.length(); i++) {
            JSONObject table = tables.optJSONObject(i);
            content.addView(PausaUi.action(this, "Tabla original · " + table.optString("title"), false, () -> {
                content.removeAllViews(); paragraph(table.optString("title"));
                content.addView(PausaUi.quiet(this, "Volver a la página", PausaUi.GREEN, () -> show(page)));
                JSONArray lines = new JSONArray(), rows = table.optJSONArray("rows");
                for (int r = 0; r < rows.length(); r++) {
                    JSONObject row = rows.optJSONObject(r); JSONArray cells = row.optJSONArray("cells"); StringBuilder line = new StringBuilder();
                    line.append("Fila ").append(row.optInt("index")).append(" · ");
                    for (int c = 0; c < cells.length(); c++) { if (c > 0) line.append(" | "); line.append(cells.optJSONObject(c).optString("text")); }
                    lines.put(line.toString());
                }
                showRows(lines, 0);
            }));
        }
        JSONObject trade = page.optJSONObject("data");
        if (trade != null) {
            JSONArray lines = new JSONArray();
            for (String key : new String[]{"balances", "transactions", "activity", "positions", "totals"}) {
                Object value = trade.opt(key); if (value == null) continue;
                JSONArray values = value instanceof JSONArray ? (JSONArray) value : value instanceof JSONObject ? ((JSONObject) value).optJSONArray("items") : null;
                if (values == null) continue;
                lines.put(key);
                for (int i = 0; i < values.length(); i++) lines.put(values.opt(i).toString());
            }
            showRows(lines, 0);
        }
    }
    private void showRows(JSONArray rows, int start) {
        int end = Math.min(start + 50, rows.length());
        for (int i = start; i < end; i++) paragraph(rows.optString(i));
        if (end < rows.length()) {
            android.widget.Button more = PausaUi.action(this, "Ver más (" + end + " de " + rows.length() + ")", false, () -> {});
            more.setOnClickListener(view -> { content.removeView(more); showRows(rows, end); }); content.addView(more);
        }
    }
    @Override protected void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
}
