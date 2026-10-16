package com.sejio.calorapp;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apuntar: one line that understands the basics, offline (pausa_v7).
 *
 * <ul>
 *   <li>{@code llamar al banco mañana tarde} → a task planned tomorrow afternoon.</li>
 *   <li>{@code !comprar regalo} → Prioridad. {@code esperar factura de Juan} → Esperando.</li>
 *   <li>{@code +350} → 350 kcal. {@code p30} → 30 g of protein. {@code c1} → one cigarette.</li>
 * </ul>
 * Day and slot words are only read at the end of the line, so a title like «preparar la cena de
 * mañana para Ana» stays as it is. Without keywords the task goes to the given lane (the Bandeja).
 */
final class QuickCapture {
    static final class Parsed {
        /** "task" or a counter: "calories", "protein", "cigarettes". */
        String kind = "task";
        String title;
        String lane;
        /** Days from today, or null when the line names no day. */
        Integer day;
        /** morning, afternoon, evening, or null for any time that day. */
        String slot;
        int amount;

        String describe() {
            switch (kind) {
                case "calories": return PausaUi.number(amount) + " kcal";
                case "protein": return amount + " g de proteína";
                case "cigarettes": return amount == 1 ? "1 cigarro" : amount + " cigarros";
                default:
                    String where = TaskStore.laneLabel(lane);
                    if (day == null) return "Tarea en " + where;
                    return "Tarea en " + where + " · " + Planning.describe(DayPlanStore.day(day), slot);
            }
        }
    }

    private static final Pattern CALORIES = Pattern.compile("^\\+\\s*(\\d{1,5})(\\s*kcal)?$");
    private static final Pattern PROTEIN = Pattern.compile("^p\\s*(\\d{1,4})(\\s*g)?$");
    private static final Pattern CIGARETTES = Pattern.compile("^c\\s*(\\d{1,2})$");
    private static final List<String> WAITING = Arrays.asList("esperar", "esperando", "espero");

    /** Not PausaUi.SPANISH: the parser runs in plain JVM unit tests, where PausaUi cannot load. */
    private static final Locale ES = new Locale("es", "ES");

    private QuickCapture() {}

    /** `weekday` is today's day of the week (0 = domingo), so the parser does not depend on the clock. */
    static Parsed parse(String line, String defaultLane, int weekday) {
        Parsed result = new Parsed();
        String text = line == null ? "" : line.trim().replaceAll("\\s+", " ");
        String lower = text.toLowerCase(ES);
        Matcher m;
        if ((m = CALORIES.matcher(lower)).matches()) return counter(result, "calories", m.group(1));
        if ((m = PROTEIN.matcher(lower)).matches()) return counter(result, "protein", m.group(1));
        if ((m = CIGARETTES.matcher(lower)).matches()) return counter(result, "cigarettes", m.group(1));

        result.lane = defaultLane == null ? "inbox" : defaultLane;
        if (text.startsWith("!")) {
            result.lane = "priority";
            text = text.substring(1).trim();
        }
        List<String> words = new ArrayList<>(Arrays.asList(text.split(" ")));
        if (!words.isEmpty() && WAITING.contains(norm(words.get(0)))) result.lane = "waiting";

        // Read day and slot words from the end of the line.
        int end = words.size();
        Integer day = null;
        String slot = null;
        boolean spelledSlot = false;
        while (end > 1) {
            String word = norm(words.get(end - 1));
            String before = end >= 2 ? norm(words.get(end - 2)) : "";
            String twoBefore = end >= 3 ? norm(words.get(end - 3)) : "";
            if (slot == null && day == null && slotOf(word) != null && "la".equals(before) && "por".equals(twoBefore) && end > 3) {
                slot = slotOf(word); spelledSlot = true; end -= 3; continue;
            }
            if (slot == null && day == null && ("tarde".equals(word) || "noche".equals(word))) {
                slot = slotOf(word); end -= 1; continue;
            }
            if (day == null && "manana".equals(word)) {
                if ("pasado".equals(before) && end > 2) { day = 2; end -= 2; } else { day = 1; end -= 1; }
                continue;
            }
            if (day == null && "hoy".equals(word)) { day = 0; end -= 1; continue; }
            int weekdayIndex = weekdayOf(word);
            if (day == null && weekdayIndex >= 0) {
                day = (weekdayIndex - weekday + 7) % 7;
                end -= 1;
                if (end > 1 && "el".equals(norm(words.get(end - 1)))) end -= 1;
                continue;
            }
            break;
        }
        // A bare «tarde» or «noche» without a day is part of the title («llegar tarde»).
        if (day == null && slot != null && !spelledSlot) { slot = null; end = words.size(); }
        else if (day == null && spelledSlot) day = 0;
        String title = String.join(" ", words.subList(0, end)).trim();
        if (title.isEmpty()) { title = text; day = null; slot = null; }
        result.title = title;
        result.day = day;
        result.slot = slot;
        return result;
    }

    private static Parsed counter(Parsed result, String kind, String amount) {
        result.kind = kind;
        result.amount = Integer.parseInt(amount);
        return result;
    }

    private static String norm(String word) {
        return java.text.Normalizer.normalize(word.toLowerCase(ES), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[.,;:!?]+$", "");
    }

    private static String slotOf(String word) {
        switch (word) {
            case "manana": return "morning";
            case "tarde": return "afternoon";
            case "noche": return "evening";
            default: return null;
        }
    }

    private static int weekdayOf(String word) {
        for (int i = 0; i < Planning.WEEKDAYS.length; i++) if (norm(Planning.WEEKDAYS[i]).equals(word)) return i;
        return -1;
    }

    /** Saves what the line says. Returns the confirmation to show. */
    static String apply(Context context, Parsed parsed) {
        switch (parsed.kind) {
            case "calories": CalorieStore.add(context, parsed.amount); NotificationHelper.show(context); return "Sumadas " + parsed.describe() + ".";
            case "protein": CalorieStore.addProtein(context, parsed.amount); NotificationHelper.show(context); return "Sumados " + parsed.describe() + ".";
            case "cigarettes": CalorieStore.adjustCigaretteCount(context, parsed.amount); NotificationHelper.show(context); return "Apuntado: " + parsed.describe() + ".";
            default:
                long id = TaskStore.add(context, parsed.title, null);
                if (!"inbox".equals(parsed.lane)) TaskStore.setLane(context, id, parsed.lane);
                if (parsed.day != null) TaskStore.plan(context, id, DayPlanStore.day(parsed.day), parsed.slot);
                PausaLog.i("capture", "task to " + parsed.lane + (parsed.day == null ? "" : " planned +" + parsed.day));
                return parsed.describe() + ".";
        }
    }

    static int todayWeekday() {
        return java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1;
    }

    /**
     * The Apuntar sheet. It stays open so several things can go in a row. With the Cajón on, a
     * second button drops the line in the cajón as it is (a link, an idea) to sort later.
     */
    static void sheet(Context c, String defaultLane, Runnable changed) {
        PausaUi.Sheet sheet = new PausaUi.Sheet(c, "Apuntar");
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(c);
        PausaUi.input(input);
        input.setHint("Llamar al banco mañana tarde");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setContentDescription("Qué quieres apuntar");
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView preview = PausaUi.text(c, "", 13, PausaUi.SAGE, true);
        TextView help = PausaUi.text(c, "«!» delante para Prioridad · «esperar…» para Esperando · hoy, mañana o un día y «tarde» al final para planificarla · +350 kcal · p30 proteína · c1 cigarro", 12, PausaUi.MUTED, false);
        help.setLineSpacing(0, 1.15f);
        boolean cajon = Features.isOn(c, Features.CAPTURES);
        Runnable update = () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { preview.setText(cajon ? "Una tarea, un contador, o un enlace al cajón." : "Una tarea o un contador."); return; }
            if (cajon && CaptureStore.firstUrl(value) != null) { preview.setText("Un enlace: mejor al cajón."); return; }
            preview.setText(parse(value, defaultLane, todayWeekday()).describe());
        };
        Runnable add = () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("Escribe algo"); return; }
            String done = apply(c, parse(value, defaultLane, todayWeekday()));
            input.setText("");
            input.requestFocus();
            preview.setText(done + " Puedes apuntar otra.");
            preview.announceForAccessibility(done);
            PausaUi.pop(preview);
            if (changed != null) changed.run();
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { update.run(); }
        });
        input.setOnEditorActionListener((v, id, event) -> { if (PausaUi.isSubmit(id, event)) { add.run(); return true; } return false; });
        Button addButton = PausaUi.action(c, "Apuntar", true, add);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, PausaUi.dp(c, 52));
        ap.leftMargin = PausaUi.dp(c, 8);
        row.addView(addButton, ap);
        sheet.add(row, 12);
        sheet.add(preview, 10);
        sheet.add(help, 8);
        update.run();
        Button toCajon = !cajon ? null : PausaUi.quiet(c, "Al cajón", PausaUi.GREEN, () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("Escribe algo"); return; }
            CaptureStore.add(c, value, "apuntar");
            input.setText("");
            preview.setText("Guardado en el cajón. Puedes apuntar otra.");
            PausaUi.pop(preview);
            if (changed != null) changed.run();
        });
        if (toCajon != null) toCajon.setCompoundDrawables(new PausaUi.Symbol(c, "box", PausaUi.GREEN, 18), null, null, null);
        sheet.footer(toCajon, PausaUi.quiet(c, "Listo", PausaUi.GREEN, sheet::dismiss));
        TaskSheets.showWithKeyboard(sheet);
        input.requestFocus();
    }
}
