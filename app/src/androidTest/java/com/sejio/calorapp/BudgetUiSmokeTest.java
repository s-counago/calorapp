package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Random;

/**
 * Drives the Dinero screen with a synthetic ledger (or, on a development emulator only, a fixture pushed to
 * the app's external files as budget-fixture.json) and saves screenshots there. Never touches the bank database.
 * Arguments: -e today 2026-10-05.
 */
public final class BudgetUiSmokeTest extends Instrumentation {
    private int checks;
    private Activity activity;
    private String today = "2026-10-05";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null && arguments.getString("today") != null) today = arguments.getString("today");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            File fixture = new File(context.getExternalFilesDir(null), "budget-fixture.json");
            JSONArray rows = fixture.exists() ? new JSONArray(new String(Files.readAllBytes(fixture.toPath()), StandardCharsets.UTF_8)) : synthetic();
            BudgetView.source = new BudgetView.Source() {
                @Override public JSONArray movements(Context c) { return rows; }
                @Override public long lastSync(Context c) { return System.currentTimeMillis() - 2 * 3_600_000L; }
            };
            BudgetView.todayOverride = Ledger.parseIso(today);
            BudgetStore.reset(context);
            getUiAutomation();
            activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            tap("Dinero", true);
            SystemClock.sleep(1800);
            unsecure();
            check(findContains("Libre para vivir") != null || findContains("Por encima de lo libre") != null, "hero shows free money");
            check(find("Lo fijo", false) != null, "fixed section present");
            capture("budget-01-mes.png");
            render("budget-00-mes-completo.png");
            scrollBy(900); capture("budget-02-fijo.png");
            scrollBy(900); capture("budget-03-dia-a-dia.png");
            scrollBy(1100); capture("budget-04-categorias.png");

            AccessibilityNodeInfo rent = findContains("Alquiler y casa,");
            check(rent != null, "rent tile present");
            tapNode(rent);
            capture("budget-05-fijo-detalle.png");
            pressBack();

            tap("Movimientos", false);
            SystemClock.sleep(600);
            unsecure();
            capture("budget-06-movimientos.png");
            AccessibilityNodeInfo coffee = findContains("A Crucena,");
            if (coffee == null) coffee = findContains("Cafés y bares");
            check(coffee != null, "a café movement is listed");
            tapNode(coffee);
            capture("budget-07-clasificar.png");
            tap("Tabaco", false);
            SystemClock.sleep(500);
            Budget.Settings settings = BudgetStore.load(context);
            check(settings.merchant.containsValue("cat:tabaco"), "teaching a merchant is saved");
            tap("Deshacer", false);
            check(!BudgetStore.load(context).merchant.containsValue("cat:tabaco"), "undo restores the previous settings");

            tap("Mes", false);
            SystemClock.sleep(500);
            tap("Ciclo anterior", true);
            SystemClock.sleep(900);
            unsecure();
            check(findContains("Te pasaste") != null || findContains("Te sobró") != null, "previous cycle shows its result");
            capture("budget-08-ciclo-anterior.png");
            BudgetStore.reset(context);
            result.putString("stream", "PASS: " + checks + " budget UI checks. Screenshots in " + context.getExternalFilesDir(null) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** Pausa forbids captures of money screens; only this test lifts that, to review the layout. */
    private void unsecure() {
        runOnMainSync(() -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        settle();
    }

    // ------------------------------------------------------------ synthetic ledger

    /** Four paydays of believable, non-personal movements with the usual bills and everyday spending. */
    private static JSONArray synthetic() throws Exception {
        JSONArray rows = new JSONArray();
        Random random = new Random(7);
        int[] counter = {0};
        String[] paydays = {"2026-06-30", "2026-07-31", "2026-08-31", "2026-09-30"};
        for (String payday : paydays) account(rows, counter, payday, "1408.25", "EMPRESA DEMO SL NOMINA");
        for (int month = 6; month <= 10; month++) {
            String m = String.format(Locale.ROOT, "2026-%02d-", month);
            if (month >= 7) {
                account(rows, counter, m + "01", "-118.24", "CARGO PRESTAMO 0000-000000-000 00-");
                account(rows, counter, m + "01", "-39.80", "000000000000 AMORTIZACION DEUDA 00000-0000");
                account(rows, counter, m + "01", "-49.16", "000000000000 AMORTIZACION DEUDA 00000-0000");
                account(rows, counter, m + "02", "-500.00", "CUENTA PAREJA");
                account(rows, counter, m + (month == 10 ? "05" : "04"), month == 10 ? "-28.53" : "-245.00", "ALQUILER, LUZ, INTERNET");
                card(rows, counter, m + "04", "-29.90", "EVOFIT SANTIAGO");
                card(rows, counter, m + "03", "-17.20", "SIMYO");
                trade(rows, counter, m + "02", "-50.00", "Core S&P 500", "SAVINGS_PLAN_EXECUTED");
                account(rows, counter, m + "02", "-300.00", "TRADE REPUBLIC");
                trade(rows, counter, m + "02", "300.00", "Cuenta propia", "INCOMING_TRANSFER");
            }
            if (month >= 7 && month <= 9) {
                card(rows, counter, m + "10", "-14.99", "NETFLIX.COM");
                card(rows, counter, m + "19", "-9.80", "SEGURO PROT. INT.TARJETA");
                card(rows, counter, m + "24", "-6.49", "GOOGLE*GOOGLE PLAY APP");
                card(rows, counter, m + "25", "-4.99", "AMAZON PRIME");
                card(rows, counter, m + "14", "-60.00", "RENFE VIRTUAL INTERNET");
                card(rows, counter, m + "01", "-6.30", "OBSIDIAN");
            }
            int days = month == 10 ? 5 : Ledger.monthLength(2026, month);
            for (int d = 1; d <= days; d++) {
                String date = m + String.format(Locale.ROOT, "%02d", d);
                if (month == 6 && d < 20) continue;
                if (random.nextInt(10) < 6) card(rows, counter, date, "-1.80", "A CRUCENA");
                if (random.nextInt(10) < 3) card(rows, counter, date, "-6.80", "ESTANCO AURORA");
                if (random.nextInt(10) < 2) card(rows, counter, date, decimal(3 + random.nextInt(28)), "DIA RETAIL 4532");
                if (random.nextInt(14) == 0) card(rows, counter, date, decimal(9 + random.nextInt(30)), "MAMA DONER KEBAP");
                if (random.nextInt(18) == 0) card(rows, counter, date, decimal(12 + random.nextInt(60)), "WWW.AMAZON");
                if (random.nextInt(16) == 0) card(rows, counter, date, decimal(2 + random.nextInt(14)), "BOLT.EU");
                if (random.nextInt(25) == 0) account(rows, counter, date, "-" + decimal(5 + random.nextInt(30)).substring(1), "PAGO BIZUM - cena");
            }
        }
        return rows;
    }

    private static String decimal(int euros) { return String.format(Locale.ROOT, "-%d.%02d", euros, (euros * 37) % 100); }

    private static void account(JSONArray rows, int[] counter, String date, String amount, String description) throws Exception {
        rows.put(new JSONObject().put("id", "s" + counter[0]++).put("bank", "abanca").put("productType", "account").put("productLabel", "Cuenta ABANCA")
                .put("data", new JSONObject().put("operationDate", date).put("valueDate", date).put("description", description)
                        .put("amount", new JSONObject().put("amount", amount).put("currency", "EUR"))));
    }

    private static void card(JSONArray rows, int[] counter, String date, String amount, String title) throws Exception {
        trade(rows, counter, date, amount, title, "card_successful_transaction");
    }

    private static void trade(JSONArray rows, int[] counter, String date, String amount, String title, String event) throws Exception {
        rows.put(new JSONObject().put("id", "s" + counter[0]++).put("bank", "trade_republic").put("productType", "investment_account")
                .put("productLabel", "Trade Republic").put("data", new JSONObject().put("bankId", "t" + counter[0])
                        .put("occurredAt", date + "T10:00:00Z").put("eventType", event).put("status", "EXECUTED").put("description", title)
                        .put("amount", new JSONObject().put("amount", amount).put("currency", "EUR"))));
    }

    // ------------------------------------------------------------ driving

    private void settle() { waitForIdleSync(); SystemClock.sleep(500); }

    private void pressBack() { sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); settle(); }

    private void scrollBy(int pixels) {
        runOnMainSync(() -> {
            View scroll = findScroll(activity.getWindow().getDecorView());
            if (scroll != null) scroll.scrollBy(0, pixels);
        });
        settle();
        SystemClock.sleep(400);
    }

    /** The visible scroll view of the money screen. */
    private View findScroll(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof PausaUi.Scroll && view.isShown() && view.getParent() instanceof ViewGroup
                && ((View) view.getParent()).getParent() instanceof BudgetView) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void tap(String label, boolean description) {
        AccessibilityNodeInfo node = find(label, description);
        if (node == null) throw new AssertionError("Missing target: " + label);
        tapNode(node);
    }

    private void tapNode(AccessibilityNodeInfo node) {
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        settle();
        node.refresh();
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        long time = SystemClock.uptimeMillis();
        pointer(time, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
        SystemClock.sleep(80);
        pointer(time, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY());
        settle();
        SystemClock.sleep(500);
    }

    private void pointer(long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0);
        getUiAutomation().injectInputEvent(event, true);
        event.recycle();
    }

    private AccessibilityNodeInfo find(String label, boolean description) {
        for (int attempt = 0; attempt < 15; attempt++) {
            AccessibilityNodeInfo node = search(getUiAutomation().getRootInActiveWindow(), label, description, false);
            if (node != null) return node;
            SystemClock.sleep(300);
        }
        return null;
    }

    private AccessibilityNodeInfo findContains(String fragment) {
        for (int attempt = 0; attempt < 10; attempt++) {
            AccessibilityNodeInfo node = search(getUiAutomation().getRootInActiveWindow(), fragment, true, true);
            if (node == null) node = search(getUiAutomation().getRootInActiveWindow(), fragment, false, true);
            if (node != null) return node;
            SystemClock.sleep(300);
        }
        return null;
    }

    private AccessibilityNodeInfo search(AccessibilityNodeInfo node, String label, boolean description, boolean partial) {
        if (node == null) return null;
        CharSequence value = description ? node.getContentDescription() : node.getText();
        if (value != null && (partial ? value.toString().contains(label) : label.equalsIgnoreCase(value.toString()))) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = search(node.getChild(index), label, description, partial);
            if (child != null) return child;
        }
        return null;
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    private void capture(String name) throws Exception {
        SystemClock.sleep(900);
        File file = new File(getTargetContext().getExternalFilesDir(null), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            Bitmap bitmap = getUiAutomation().takeScreenshot();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
        }
    }

    /** The whole Mes page as one tall image, for design review. */
    private void render(String name) throws Exception {
        final Bitmap[] out = new Bitmap[1];
        runOnMainSync(() -> {
            View scroll = findScroll(activity.getWindow().getDecorView());
            View content = ((ViewGroup) scroll).getChildAt(0);
            Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(PausaUi.CREAM);
            content.draw(canvas);
            out[0] = bitmap;
        });
        File file = new File(getTargetContext().getExternalFilesDir(null), name);
        try (FileOutputStream output = new FileOutputStream(file)) { out[0].compress(Bitmap.CompressFormat.PNG, 100, output); }
        out[0].recycle();
    }
}
