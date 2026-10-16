package com.sejio.calorapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Biblioteca y Archivo del cajón (feature captures): what was kept or archived, so it can be found
 * again on the phone, turned into a task, sent back to the cajón or deleted. The same tabs as the
 * web's Cajón view; new captures stay on top of the Bandeja in Planificar.
 */
final class CaptureLibrarySheet {
    private static final String[] STATUSES = {"kept", "archived"};
    private static final String[] LABELS = {"Biblioteca", "Archivo"};
    private static final int SHOWN = 100;

    private final Context c;
    private final Runnable changed;
    private final PausaUi.Sheet sheet;
    private final LinearLayout tabs, list;
    private final EditText search;
    private String status = "kept";

    private CaptureLibrarySheet(Context c, Runnable changed) {
        this.c = c;
        this.changed = changed;
        sheet = new PausaUi.Sheet(c, "Biblioteca y archivo").tall();
        tabs = new LinearLayout(c);
        sheet.add(tabs, 12);
        search = new EditText(c);
        PausaUi.input(search);
        search.setSingleLine(true);
        search.setHint("Buscar por texto, enlace o etiqueta");
        search.setContentDescription("Buscar en el cajón");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { render(); }
        });
        sheet.add(search, 12);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroller = new android.widget.ScrollView(c);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(list, new android.widget.ScrollView.LayoutParams(-1, -2));
        sheet.body.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        sheet.footer(null, PausaUi.quiet(c, "Listo", PausaUi.GREEN, sheet::dismiss));
    }

    static void show(Context c, Runnable changed) {
        CaptureLibrarySheet library = new CaptureLibrarySheet(c, changed);
        library.render();
        library.sheet.show();
        PausaLog.i("captures", "library opened");
    }

    private void render() {
        tabs.removeAllViews();
        for (int i = 0; i < STATUSES.length; i++) {
            final String value = STATUSES[i];
            TextView chip = PausaUi.chip(c, LABELS[i] + " · " + CaptureStore.count(c, value), value.equals(status), () -> { status = value; render(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = dp(8);
            tabs.addView(chip, params);
        }
        list.removeAllViews();
        List<CaptureStore.Capture> captures = CaptureStore.sorted(c, status, search.getText().toString());
        if (captures.isEmpty()) {
            boolean searching = search.getText().toString().trim().length() > 0;
            TextView empty = PausaUi.text(c, searching ? "Nada coincide con la búsqueda."
                    : "kept".equals(status) ? "Aún no has guardado nada en la biblioteca. Desde el cajón, «Guardar» lo deja aquí."
                    : "El archivo está vacío.", 14, PausaUi.MUTED, false);
            empty.setLineSpacing(0, 1.12f);
            list.addView(empty, full());
            return;
        }
        for (int i = 0; i < Math.min(SHOWN, captures.size()); i++) {
            LinearLayout.LayoutParams params = full();
            params.bottomMargin = dp(10);
            list.addView(card(captures.get(i)), params);
        }
        if (captures.size() > SHOWN) {
            list.addView(PausaUi.text(c, "Y " + (captures.size() - SHOWN) + " más. Busca para encontrarlas, o míralas en la web.", 13, PausaUi.MUTED, false), full());
        }
    }

    private LinearLayout card(CaptureStore.Capture capture) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(PausaUi.surface(c, "kept".equals(capture.status) ? PausaUi.SAGE_SOFT : PausaUi.NEUTRAL, 20));
        card.setPadding(dp(16), dp(12), dp(10), dp(6));
        StringBuilder meta = new StringBuilder(CaptureStore.kindLabel(capture.kind == null ? (capture.url != null ? "link" : "note") : capture.kind));
        if (capture.tags != null && !capture.tags.trim().isEmpty()) {
            for (String tag : capture.tags.split(",")) if (!tag.trim().isEmpty()) meta.append("  #").append(tag.trim());
        }
        card.addView(PausaUi.eyebrow(c, meta.toString(), PausaUi.MUTED), full());
        TextView text = PausaUi.text(c, capture.title != null && !capture.title.isEmpty() ? capture.title + "\n" + capture.text : capture.text, 15, PausaUi.INK, false);
        text.setMaxLines(6);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setLineSpacing(0, 1.12f);
        text.setPadding(0, dp(4), 0, 0);
        card.addView(text, full());
        if (capture.url != null) {
            TextView link = PausaUi.text(c, host(capture.url), 12, PausaUi.SAGE, true);
            link.setPadding(0, dp(4), 0, 0);
            link.setContentDescription("Abrir enlace");
            link.setOnClickListener(v -> {
                try { c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(capture.url))); }
                catch (RuntimeException none) { PausaUi.snack(c, "No hay app para abrir este enlace.", null, null); }
            });
            card.addView(link, full());
        }
        LinearLayout actions = new LinearLayout(c);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.addView(PausaUi.quiet(c, "Tarea", PausaUi.GREEN, () -> toTask(c, capture, this::after)));
        actions.addView(PausaUi.quiet(c, "Al cajón", PausaUi.GREEN, () ->
                move(capture, () -> CaptureStore.reopen(c, capture), "De vuelta al cajón.")));
        if ("kept".equals(capture.status)) {
            actions.addView(PausaUi.quiet(c, "Archivar", PausaUi.MUTED, () ->
                    move(capture, () -> CaptureStore.archive(c, capture), "Archivada.")));
        } else {
            actions.addView(PausaUi.quiet(c, "Guardar", PausaUi.GREEN, () ->
                    move(capture, () -> CaptureStore.keep(c, capture), "Guardada en la biblioteca.")));
        }
        actions.addView(PausaUi.quiet(c, "Borrar", PausaUi.TERRACOTTA, () -> {
            CaptureStore.delete(c, capture);
            after();
            PausaUi.snack(c, "Borrada.", "Deshacer", () -> { CaptureStore.undelete(c, capture); after(); });
        }));
        HorizontalScrollView scroller = new HorizontalScrollView(c);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(actions);
        card.addView(scroller, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) scroller.getLayoutParams()).gravity = Gravity.END;
        return card;
    }

    private void move(CaptureStore.Capture capture, Runnable change, String message) {
        change.run();
        after();
        PausaUi.snack(c, message, "Deshacer", () -> { CaptureStore.restore(c, capture); after(); });
    }

    private void after() {
        render();
        if (changed != null) changed.run();
    }

    /** Which lane the new task goes to; the title can be edited first. Shared with the cajón in Planificar. */
    static void toTask(Context c, CaptureStore.Capture capture, Runnable changed) {
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Convertir en tarea");
        EditText title = new EditText(c);
        PausaUi.input(title);
        CaptureStore.Suggestion suggestion = capture.suggested();
        title.setText(suggestion != null && suggestion.title != null ? suggestion.title : CaptureStore.defaultTitle(capture));
        title.setSelection(title.length());
        title.setMaxLines(4);
        title.setContentDescription("Título de la tarea");
        sheet.add(title, 8);
        sheet.add(PausaUi.text(c, "El texto y el enlace quedan en las notas de la tarea.", 12, PausaUi.MUTED, false), 14);
        sheet.add(PausaUi.eyebrow(c, "A qué carril", PausaUi.MUTED), 8);
        LinearLayout lanes = new LinearLayout(c);
        for (int i = 0; i < TaskStore.LANES.length; i++) {
            final String to = TaskStore.LANES[i];
            TextView chip = PausaUi.chip(c, TaskStore.LANE_LABELS[i], false, () -> {
                String value = title.getText().toString().trim();
                if (value.isEmpty()) { title.setError("Escribe un título"); return; }
                sheet.dismiss();
                CaptureStore.toTask(c, capture, value, to, null);
                if (changed != null) changed.run();
                PausaUi.snack(c, "Ahora es una tarea de " + TaskStore.laneLabel(to).toLowerCase(PausaUi.SPANISH) + ".", null, null);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = PausaUi.dp(c, 8);
            lanes.addView(chip, params);
        }
        HorizontalScrollView scroller = new HorizontalScrollView(c);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(lanes);
        sheet.add(scroller, 8);
        sheet.footer(null, PausaUi.quiet(c, "Cancelar", PausaUi.MUTED, sheet::dismiss));
        sheet.show();
    }

    static String host(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? url : host.startsWith("www.") ? host.substring(4) : host;
        } catch (RuntimeException invalid) {
            return url;
        }
    }

    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return PausaUi.dp(c, value); }
}
