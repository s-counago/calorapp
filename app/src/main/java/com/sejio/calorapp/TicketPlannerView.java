package com.sejio.calorapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Weekday commutes between Santiago and A Coruña, reviewed and formalised in one go. */
final class TicketPlannerView extends PausaUi.Scroll {
    // Solo días laborables: el Cogedor es para los viajes de trabajo.
    private static final String[] DAY_NAMES = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes"};
    private static final int DAYS = DAY_NAMES.length;

    private final Activity activity;
    private final Calendar weekMonday = Calendar.getInstance();
    // La casa está en Santiago: salir hacia A Coruña es la ida, volver a Santiago es la vuelta.
    private final TrainSchedule[] idaSelections = new TrainSchedule[DAYS];
    private final TrainSchedule[] vueltaSelections = new TrainSchedule[DAYS];
    private final LinearLayout[] idaPills = new LinearLayout[DAYS];
    private final LinearLayout[] vueltaPills = new LinearLayout[DAYS];
    private final TextView[] dayNameLabels = new TextView[DAYS];
    private final TextView[] dateLabels = new TextView[DAYS];
    private boolean sergio = true, miriam = true;
    private TextView sergioToggle, miriamToggle;
    private TextView weekLabel;
    private TextView thisWeekChip;
    private TextView relativeLabel;
    private TextView summaryTitle, summaryDetail;

    TicketPlannerView(Activity activity) {
        super(activity);
        this.activity = activity;
        setTag(PausaUi.SCROLL_TAG);
        setFillViewport(true);
        setClipToPadding(false);
        setVerticalScrollBarEnabled(false);
        int originalDayOfWeek = weekMonday.get(Calendar.DAY_OF_WEEK);
        normalizeToMonday(weekMonday);
        if (originalDayOfWeek != Calendar.MONDAY) weekMonday.add(Calendar.WEEK_OF_YEAR, 1);
        addView(buildContent(), new ScrollView.LayoutParams(-1, -2));
        refreshWeekLabels();
        refreshSummary();
    }

    private View buildContent() {
        LinearLayout root = column();
        root.setPadding(dp(20), dp(10), dp(20), 0);
        root.addView(PausaUi.editorial(activity, "Viajes", 30), matchWrap());
        TextView route = PausaUi.text(activity, "Santiago  ⇄  A Coruña · idas y vueltas", 13, PausaUi.MUTED, false);
        route.setCompoundDrawables(new PausaUi.Symbol(activity, "train", PausaUi.MUTED, 16), null, null, null);
        route.setCompoundDrawablePadding(dp(8));
        route.setPadding(0, dp(6), 0, dp(18));
        root.addView(route, matchWrap());

        LinearLayout travelers = new LinearLayout(activity);
        sergioToggle = travelerToggle("Sergio", () -> { sergio = !sergio; renderTravelers(); refreshSummary(); });
        miriamToggle = travelerToggle("Miriam", () -> { miriam = !miriam; renderTravelers(); refreshSummary(); });
        travelers.addView(sergioToggle, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(52), 1);
        mp.leftMargin = dp(10);
        travelers.addView(miriamToggle, mp);
        renderTravelers();
        root.addView(travelers, spaced(18));

        LinearLayout week = new LinearLayout(activity);
        week.setGravity(Gravity.CENTER_VERTICAL);
        week.addView(PausaUi.iconButton(activity, "back", "Semana anterior", PausaUi.GREEN, () -> changeWeek(-1)),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout center = column();
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        weekLabel = PausaUi.editorial(activity, "", 19);
        weekLabel.setGravity(Gravity.CENTER);
        center.addView(weekLabel, matchWrap());
        LinearLayout relativeRow = new LinearLayout(activity);
        relativeRow.setOrientation(LinearLayout.VERTICAL);
        relativeRow.setGravity(Gravity.CENTER_HORIZONTAL);
        relativeLabel = PausaUi.text(activity, "", 12, PausaUi.MUTED, false);
        relativeRow.addView(relativeLabel);
        thisWeekChip = PausaUi.text(activity, "Ir a esta semana", 12, PausaUi.GREEN, true);
        thisWeekChip.setGravity(Gravity.CENTER);
        thisWeekChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        thisWeekChip.setBackground(PausaUi.ripple(activity, PausaUi.SAGE_SOFT, 12));
        thisWeekChip.setOnClickListener(v -> goToCurrentWeek());
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.topMargin = dp(6);
        relativeRow.addView(thisWeekChip, chipParams);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(4);
        center.addView(relativeRow, cp);
        week.addView(center, new LinearLayout.LayoutParams(0, -2, 1));
        week.addView(PausaUi.iconButton(activity, "chevron", "Semana siguiente", PausaUi.GREEN, () -> changeWeek(1)),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(week, spaced(12));

        LinearLayout days = column();
        days.setBackground(PausaUi.card(activity));
        days.setPadding(dp(14), dp(6), dp(12), dp(6));
        for (int dayIndex = 0; dayIndex < DAYS; dayIndex++) {
            if (dayIndex > 0) days.addView(PausaUi.hairline(activity), new LinearLayout.LayoutParams(-1, dp(1)));
            days.addView(buildDayRow(dayIndex), matchWrap());
        }
        root.addView(days, spaced(6));

        Button copyButton = PausaUi.quiet(activity, "Copiar el primer día al resto", PausaUi.GREEN, this::copyFirstCombination);
        copyButton.setCompoundDrawables(new PausaUi.Symbol(activity, "swap", PausaUi.GREEN, 18), null, null, null);
        copyButton.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-2, -2);
        copyParams.gravity = Gravity.CENTER_HORIZONTAL;
        copyParams.bottomMargin = dp(14);
        root.addView(copyButton, copyParams);

        LinearLayout summary = column();
        summary.setBackground(PausaUi.surface(activity, PausaUi.NIGHT, 28));
        summary.setPadding(dp(20), dp(18), dp(20), dp(18));
        summary.addView(PausaUi.eyebrow(activity, "Resumen", PausaUi.ON_NIGHT_MUTED), matchWrap());
        summaryTitle = PausaUi.editorial(activity, "", 24);
        summaryTitle.setTextColor(PausaUi.ON_NIGHT);
        summaryTitle.setPadding(0, dp(6), 0, dp(4));
        summary.addView(summaryTitle, matchWrap());
        summaryDetail = PausaUi.text(activity, "", 13, PausaUi.ON_NIGHT_MUTED, false);
        summary.addView(summaryDetail, matchWrap());
        Button formalize = PausaUi.action(activity, "Revisar y formalizar", true, this::review);
        formalize.setBackground(PausaUi.ripple(activity, PausaUi.SUN, 18));
        formalize.setTextColor(PausaUi.NIGHT);
        formalize.setCompoundDrawables(null, null, new PausaUi.Symbol(activity, "arrow", PausaUi.NIGHT, 18), null);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(54));
        fp.topMargin = dp(16);
        summary.addView(formalize, fp);
        root.addView(summary, spaced(12));

        TextView warning = PausaUi.text(activity,
                "Renfe puede pedir la verificación en dos pasos. La app se detendrá para que introduzcas el código y continuará después.",
                12, PausaUi.MUTED, false);
        warning.setLineSpacing(0, 1.15f);
        warning.setCompoundDrawables(new PausaUi.Symbol(activity, "info", PausaUi.MUTED, 16), null, null, null);
        warning.setCompoundDrawablePadding(dp(10));
        warning.setPadding(dp(4), 0, dp(4), 0);
        root.addView(warning, matchWrap());
        return root;
    }

    private TextView travelerToggle(String name, Runnable toggle) {
        TextView view = PausaUi.text(activity, name, 15, PausaUi.INK, true);
        view.setGravity(Gravity.CENTER);
        view.setCompoundDrawablePadding(dp(10));
        view.setPadding(dp(12), 0, dp(20), 0);
        view.setClickable(true);
        view.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); toggle.run(); PausaUi.pop(v); });
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(CheckBox.class.getName());
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
        return view;
    }

    private void renderTravelers() {
        styleToggle(sergioToggle, "S", sergio);
        styleToggle(miriamToggle, "M", miriam);
    }

    private void styleToggle(TextView view, String initial, boolean on) {
        view.setSelected(on);
        view.setTextColor(on ? PausaUi.SURFACE : PausaUi.MUTED);
        view.setBackground(PausaUi.ripple(activity, on ? PausaUi.GREEN : PausaUi.NEUTRAL, 26));
        view.setCompoundDrawables(new Avatar(initial, on), null, null, null);
    }

    /** Initial in a small circle: sun on green when travelling, quiet otherwise. */
    private final class Avatar extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final String initial;
        Avatar(String initial, boolean on) {
            this.initial = initial;
            fill.setColor(on ? PausaUi.SUN : PausaUi.LINE);
            text.setColor(on ? PausaUi.NIGHT : PausaUi.MUTED);
            text.setTextAlign(android.graphics.Paint.Align.CENTER);
            text.setTextSize(dp(13));
            text.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
            setBounds(0, 0, dp(28), dp(28));
        }
        @Override public void draw(android.graphics.Canvas canvas) {
            float r = getBounds().width() / 2f;
            canvas.drawCircle(getBounds().left + r, getBounds().top + r, r, fill);
            canvas.drawText(initial, getBounds().left + r, getBounds().top + r - (text.descent() + text.ascent()) / 2, text);
        }
        @Override public int getIntrinsicWidth() { return dp(28); }
        @Override public int getIntrinsicHeight() { return dp(28); }
        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private View buildDayRow(int dayIndex) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout labels = column();
        dayNameLabels[dayIndex] = PausaUi.editorial(activity, DAY_NAMES[dayIndex].substring(0, 3), 18);
        labels.addView(dayNameLabels[dayIndex], matchWrap());
        dateLabels[dayIndex] = PausaUi.text(activity, "", 12, PausaUi.MUTED, false);
        dateLabels[dayIndex].setPadding(0, dp(3), 0, 0);
        labels.addView(dateLabels[dayIndex], matchWrap());
        labels.setContentDescription(DAY_NAMES[dayIndex] + ". Mantén pulsado para quitar sus viajes.");
        labels.setLongClickable(true);
        labels.setOnLongClickListener(v -> { clearDay(dayIndex); return true; });
        row.addView(labels, new LinearLayout.LayoutParams(dp(58), -2));
        idaPills[dayIndex] = ticketPill();
        idaPills[dayIndex].setOnClickListener(v -> showScheduleSheet(dayIndex, false, TrainSchedules.SANTIAGO_TO_A_CORUNA));
        row.addView(idaPills[dayIndex], new LinearLayout.LayoutParams(0, dp(60), 1));
        vueltaPills[dayIndex] = ticketPill();
        vueltaPills[dayIndex].setOnClickListener(v -> showScheduleSheet(dayIndex, true, TrainSchedules.A_CORUNA_TO_SANTIAGO));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, dp(60), 1);
        vp.leftMargin = dp(8);
        row.addView(vueltaPills[dayIndex], vp);
        return row;
    }

    private LinearLayout ticketPill() {
        LinearLayout pill = column();
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), 0, dp(8), 0);
        pill.setClickable(true);
        pill.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(activity, R.animator.button_press));
        return pill;
    }

    private void renderPill(LinearLayout pill, boolean vuelta, TrainSchedule train, String dayName) {
        pill.removeAllViews();
        String direction = vuelta ? "Vuelta" : "Ida";
        TextView eyebrow = PausaUi.eyebrow(activity, direction, train == null ? PausaUi.MUTED : vuelta ? PausaUi.GREEN : 0xFF9A6A12);
        eyebrow.setTextSize(10);
        pill.addView(eyebrow);
        if (train == null) {
            pill.setBackground(PausaUi.dashed(activity, 0xFFCFC9BC, 16));
            TextView choose = PausaUi.text(activity, "Elegir", 14, PausaUi.MUTED, false);
            choose.setPadding(0, dp(4), 0, 0);
            pill.addView(choose);
            pill.setContentDescription(direction + " del " + dayName.toLowerCase(Locale.ROOT) + ", "
                    + (vuelta ? "A Coruña a Santiago" : "Santiago a A Coruña") + ". Elegir tren");
        } else {
            pill.setBackground(PausaUi.ripple(activity, vuelta ? PausaUi.SAGE_SOFT : PausaUi.SUN_SOFT, 16));
            LinearLayout times = new LinearLayout(activity);
            times.setGravity(Gravity.BOTTOM);
            TextView departure = PausaUi.editorial(activity, train.departure, 18);
            times.addView(departure);
            TextView arrival = PausaUi.text(activity, "  → " + train.arrival, 11, PausaUi.MUTED, false);
            arrival.setPadding(0, 0, 0, dp(2));
            times.addView(arrival);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = dp(3);
            pill.addView(times, tp);
            pill.setContentDescription(direction + " del " + dayName.toLowerCase(Locale.ROOT) + ", " + train.displayName());
        }
    }

    private void showScheduleSheet(int dayIndex, boolean toSantiago, List<TrainSchedule> schedules) {
        TrainSchedule selected = toSantiago ? vueltaSelections[dayIndex] : idaSelections[dayIndex];
        Calendar day = (Calendar) weekMonday.clone();
        day.add(Calendar.DAY_OF_MONTH, dayIndex);
        String date = new SimpleDateFormat("EEEE d MMM", PausaUi.SPANISH).format(day.getTime());
        PausaUi.Sheet sheet = new PausaUi.Sheet(activity, (toSantiago ? "Vuelta · " : "Ida · ") + date).tall();
        sheet.subtitle(toSantiago ? "A Coruña → Santiago" : "Santiago → A Coruña");
        boolean[] wholeWeek = {false};
        LinearLayout options = new LinearLayout(activity);
        TextView none = PausaUi.chip(activity, "Sin viaje", selected == null, () -> {
            choose(dayIndex, toSantiago, null, wholeWeek[0]);
            sheet.dismiss();
        });
        options.addView(none, new LinearLayout.LayoutParams(0, -2, 1));
        TextView week = PausaUi.chip(activity, "Toda la semana", false, null);
        week.setCompoundDrawablePadding(dp(6));
        week.setOnClickListener(v -> {
            wholeWeek[0] = !wholeWeek[0];
            PausaUi.setChip(week, wholeWeek[0]);
            week.setCompoundDrawables(wholeWeek[0] ? new PausaUi.Symbol(activity, "check", PausaUi.SURFACE, 16) : null, null, null, null);
        });
        week.setContentDescription("Aplicar la elección a toda la semana");
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
        wp.leftMargin = dp(8);
        options.addView(week, wp);
        sheet.body.addView(options, spaced(6));
        ScrollView gridScroll = new ScrollView(activity);
        gridScroll.setVerticalScrollBarEnabled(false);
        LinearLayout grid = column();
        String[] bands = {"Mañana", "Tarde", "Noche"};
        for (int band = 0; band < 3; band++) {
            LinearLayout row = null;
            int inRow = 0;
            boolean labelAdded = false;
            for (TrainSchedule train : schedules) {
                int hour = Integer.parseInt(train.departure.substring(0, 2));
                int trainBand = hour < 14 ? 0 : hour < 20 ? 1 : 2;
                if (trainBand != band) continue;
                if (!labelAdded) {
                    TextView label = PausaUi.eyebrow(activity, bands[band], PausaUi.MUTED);
                    label.setPadding(dp(2), dp(16), 0, dp(8));
                    grid.addView(label, matchWrap());
                    labelAdded = true;
                }
                if (row == null || inRow == 3) {
                    row = new LinearLayout(activity);
                    grid.addView(row, spaced(8));
                    inRow = 0;
                }
                boolean on = train.equals(selected) || (selected != null && train.departure.equals(selected.departure)
                        && train.service.equals(selected.service));
                LinearLayout chip = column();
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(4), dp(8), dp(4), dp(8));
                chip.setBackground(PausaUi.ripple(activity, on ? PausaUi.GREEN : PausaUi.CREAM, 16));
                TextView dep = PausaUi.editorial(activity, train.departure, 18);
                dep.setTextColor(on ? PausaUi.SURFACE : PausaUi.INK);
                dep.setGravity(Gravity.CENTER);
                chip.addView(dep);
                TextView detail = PausaUi.text(activity, "→ " + train.arrival, 11, on ? PausaUi.ON_NIGHT_MUTED : PausaUi.MUTED, false);
                detail.setGravity(Gravity.CENTER);
                detail.setPadding(0, dp(3), 0, dp(2));
                chip.addView(detail);
                TextView service = PausaUi.text(activity, train.service, 9, on ? PausaUi.SUN : 0xFF9A958A, true);
                service.setLetterSpacing(.08f);
                service.setGravity(Gravity.CENTER);
                chip.addView(service);
                chip.setContentDescription(train.displayName());
                chip.setClickable(true);
                chip.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(activity, R.animator.button_press));
                chip.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    choose(dayIndex, toSantiago, train, wholeWeek[0]);
                    sheet.dismiss();
                });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                if (inRow > 0) p.leftMargin = dp(8);
                row.addView(chip, p);
                inRow++;
            }
            while (row != null && inRow < 3) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1);
                p.leftMargin = dp(8);
                row.addView(new View(activity), p);
                inRow++;
            }
        }
        gridScroll.addView(grid);
        sheet.body.addView(gridScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        sheet.show();
    }

    private void choose(int dayIndex, boolean toSantiago, TrainSchedule choice, boolean wholeWeek) {
        for (int index = 0; index < DAYS; index++) {
            if (!wholeWeek && index != dayIndex) continue;
            if (toSantiago) vueltaSelections[index] = choice; else idaSelections[index] = choice;
            refreshSelectionButtons(index);
        }
        refreshSummary();
        PausaUi.pop(toSantiago ? vueltaPills[dayIndex] : idaPills[dayIndex]);
    }

    private void clearDay(int dayIndex) {
        TrainSchedule ida = idaSelections[dayIndex], vuelta = vueltaSelections[dayIndex];
        if (ida == null && vuelta == null) return;
        idaSelections[dayIndex] = null;
        vueltaSelections[dayIndex] = null;
        refreshSelectionButtons(dayIndex);
        refreshSummary();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PausaUi.snack(activity, "Viajes del " + DAY_NAMES[dayIndex].toLowerCase(Locale.ROOT) + " quitados", "Deshacer", () -> {
            idaSelections[dayIndex] = ida;
            vueltaSelections[dayIndex] = vuelta;
            refreshSelectionButtons(dayIndex);
            refreshSummary();
        });
    }

    private void copyFirstCombination() {
        int source = -1;
        for (int index = 0; index < DAYS; index++) {
            if (idaSelections[index] != null || vueltaSelections[index] != null) { source = index; break; }
        }
        if (source < 0) {
            PausaUi.snack(activity, "Elige primero al menos un horario.", null, null);
            return;
        }
        for (int index = 0; index < DAYS; index++) {
            idaSelections[index] = idaSelections[source];
            vueltaSelections[index] = vueltaSelections[source];
            refreshSelectionButtons(index);
        }
        refreshSummary();
        PausaUi.snack(activity, "El " + DAY_NAMES[source].toLowerCase(Locale.ROOT) + " se repite toda la semana", null, null);
    }

    private void refreshSummary() {
        TicketPlan plan = createPlan();
        int trips = plan.trips.size(), travelers = plan.travelerCount();
        int total = trips * travelers;
        summaryTitle.setText(trips == 0 ? "Elige tus trenes" : total + (total == 1 ? " formalización" : " formalizaciones"));
        summaryDetail.setText(trips + (trips == 1 ? " trayecto" : " trayectos") + " · "
                + travelers + (travelers == 1 ? " viajero" : " viajeros"));
    }

    /** Validates, then shows the full list before anything is sent to Renfe. */
    void review() {
        TicketPlan plan = createPlan();
        if (plan.travelerCount() == 0) {
            PausaUi.snack(activity, "Marca a Sergio, a Miriam o a los dos.", null, null);
            return;
        }
        if (plan.trips.isEmpty()) {
            PausaUi.snack(activity, "Elige al menos un tren.", null, null);
            return;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
        for (TicketPlan.Trip trip : plan.trips) {
            if (trip.date.compareTo(today) < 0) {
                PausaUi.snack(activity, "El plan contiene una fecha pasada. Elige la semana actual o una posterior.", null, null);
                return;
            }
        }
        int total = plan.travelerCount() * plan.trips.size();
        PausaUi.Sheet sheet = new PausaUi.Sheet(activity, "Revisar selección");
        sheet.subtitle(plan.travelerCount() + (plan.travelerCount() == 1 ? " viajero" : " viajeros") + " · "
                + plan.trips.size() + (plan.trips.size() == 1 ? " trayecto" : " trayectos") + " · "
                + total + " formalizaciones en total");
        for (TicketPlan.Trip trip : plan.trips) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(PausaUi.surface(activity, trip.outbound ? PausaUi.SAGE_SOFT : PausaUi.SUN_SOFT, 16));
            LinearLayout labels = column();
            labels.addView(PausaUi.text(activity, PausaUi.capitalize(displayDate(trip.date)), 14, PausaUi.INK, true));
            TextView kind = PausaUi.text(activity, trip.tripLabel(), 12, PausaUi.MUTED, false);
            kind.setPadding(0, dp(3), 0, 0);
            labels.addView(kind);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(PausaUi.editorial(activity, trip.train.departure + " → " + trip.train.arrival, 17));
            sheet.add(row, 6);
        }
        sheet.footer(PausaUi.quiet(activity, "Volver", PausaUi.MUTED, sheet::dismiss),
                PausaUi.action(activity, "Formalizar", true, () -> { sheet.dismiss(); launchAutomation(plan); }));
        sheet.show();
    }

    private TicketPlan createPlan() {
        TicketPlan plan = new TicketPlan();
        plan.sergio = sergio;
        plan.miriam = miriam;
        SimpleDateFormat storageFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int dayIndex = 0; dayIndex < DAYS; dayIndex++) {
            Calendar day = (Calendar) weekMonday.clone();
            day.add(Calendar.DAY_OF_MONTH, dayIndex);
            String date = storageFormat.format(day.getTime());
            if (idaSelections[dayIndex] != null) plan.trips.add(new TicketPlan.Trip(date, false, idaSelections[dayIndex]));
            if (vueltaSelections[dayIndex] != null) plan.trips.add(new TicketPlan.Trip(date, true, vueltaSelections[dayIndex]));
        }
        return plan;
    }

    private void launchAutomation(TicketPlan plan) {
        try {
            Intent intent = new Intent(activity, RenfeAutomationActivity.class);
            intent.putExtra(TicketPlan.EXTRA_PLAN, plan.toJsonString());
            activity.startActivity(intent);
        } catch (JSONException error) {
            PausaUi.snack(activity, "No se pudo preparar el plan.", null, null);
        }
    }

    private void changeWeek(int amount) {
        weekMonday.add(Calendar.WEEK_OF_YEAR, amount);
        refreshWeekLabels();
        refreshSummary();
        if (PausaUi.motion(activity)) {
            weekLabel.setTranslationX(dp(amount * 16));
            weekLabel.setAlpha(0);
            weekLabel.animate().translationX(0).alpha(1).setDuration(260).setInterpolator(PausaUi.EASE).start();
        }
    }

    private Calendar currentWorkWeek() {
        Calendar current = Calendar.getInstance();
        int dayOfWeek = current.get(Calendar.DAY_OF_WEEK);
        normalizeToMonday(current);
        // En sábado o domingo la semana laboral ya pasó: salta a la siguiente.
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) current.add(Calendar.WEEK_OF_YEAR, 1);
        return current;
    }

    private void goToCurrentWeek() {
        int direction = currentWorkWeek().before(weekMonday) ? -1 : 1;
        weekMonday.setTimeInMillis(currentWorkWeek().getTimeInMillis());
        changeWeek(0);
        if (PausaUi.motion(activity)) weekLabel.setTranslationX(dp(direction * 16));
    }

    private void refreshWeekLabels() {
        SimpleDateFormat dayFormat = new SimpleDateFormat("d MMM", PausaUi.SPANISH);
        SimpleDateFormat storageFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = storageFormat.format(Calendar.getInstance().getTime());
        Calendar lastDay = (Calendar) weekMonday.clone();
        lastDay.add(Calendar.DAY_OF_MONTH, DAYS - 1);
        weekLabel.setText(dayFormat.format(weekMonday.getTime()) + " – " + dayFormat.format(lastDay.getTime()));
        boolean current = storageFormat.format(weekMonday.getTime()).equals(storageFormat.format(currentWorkWeek().getTime()));
        thisWeekChip.setVisibility(current ? GONE : VISIBLE);
        long weeks = Math.round((weekMonday.getTimeInMillis() - currentWorkWeek().getTimeInMillis()) / (7 * 86400000.0));
        relativeLabel.setText(weeks == 0 ? "Esta semana" : weeks == 1 ? "Semana que viene" : weeks == -1 ? "Semana pasada"
                : weeks > 0 ? "Dentro de " + weeks + " semanas" : "Hace " + (-weeks) + " semanas");
        for (int dayIndex = 0; dayIndex < DAYS; dayIndex++) {
            Calendar day = (Calendar) weekMonday.clone();
            day.add(Calendar.DAY_OF_MONTH, dayIndex);
            boolean isToday = storageFormat.format(day.getTime()).equals(today);
            dateLabels[dayIndex].setText(isToday ? "Hoy" : dayFormat.format(day.getTime()));
            dateLabels[dayIndex].setTextColor(isToday ? PausaUi.TERRACOTTA : PausaUi.MUTED);
            dayNameLabels[dayIndex].setTextColor(isToday ? PausaUi.GREEN : PausaUi.INK);
            refreshSelectionButtons(dayIndex);
        }
    }

    private void refreshSelectionButtons(int dayIndex) {
        renderPill(idaPills[dayIndex], false, idaSelections[dayIndex], DAY_NAMES[dayIndex]);
        renderPill(vueltaPills[dayIndex], true, vueltaSelections[dayIndex], DAY_NAMES[dayIndex]);
    }

    private String displayDate(String storageDate) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(storageDate);
            return new SimpleDateFormat("EEEE d MMM", PausaUi.SPANISH).format(date);
        } catch (Exception ignored) {
            return storageDate;
        }
    }

    private void normalizeToMonday(Calendar calendar) {
        int day = calendar.get(Calendar.DAY_OF_WEEK);
        int daysFromMonday = (day + 5) % 7;
        calendar.add(Calendar.DAY_OF_MONTH, -daysFromMonday);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams spaced(int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(bottom);
        return params;
    }
    private int dp(int value) { return PausaUi.dp(activity, value); }
}
