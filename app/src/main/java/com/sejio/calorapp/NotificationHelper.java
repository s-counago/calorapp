package com.sejio.calorapp;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Calendar;

final class NotificationHelper {
    static final String ACTION_ADD = "com.sejio.calorapp.ADD";
    static final String ACTION_CIGARETTE_CHANGE = "com.sejio.calorapp.CIGARETTE_CHANGE";
    static final String ACTION_PROTEIN_ADD = "com.sejio.calorapp.PROTEIN_ADD";
    /** Hourly redraw of the panel, so its light follows the day and the counters roll over at 03:00. */
    static final String ACTION_REFRESH = "com.sejio.calorapp.NOTIFICATION_REFRESH";
    static final String EXTRA_AMOUNT = "amount";
    static final String EXTRA_DELTA = "delta";
    static final String EXTRA_GRAMS = "grams";

    private static final String CHANNEL_ID = "counter";
    /** Its own channel so the panel sits with the visible notifications (silent ones start collapsed). */
    private static final String PANEL_CHANNEL_ID = "panel";
    private static final int FOOD_NOTIFICATION_ID = 1001;
    private static final int CIGARETTE_NOTIFICATION_ID = 1002;
    private static final int PROTEIN_NOTIFICATION_ID = 1003;
    private static final int PANEL_NOTIFICATION_ID = 1004;
    private static final int REFRESH_REQUEST_CODE = 30404;

    private NotificationHelper() {
    }

    static void show(Context context) {
        Context appContext = context.getApplicationContext();
        if (!CalorieStore.isNotificationEnabled(appContext) || !canPostNotifications(appContext)) {
            return;
        }

        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        ensureChannel(manager);

        Intent appIntent = new Intent(appContext, MainActivity.class);
        appIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent appPendingIntent = PendingIntent.getActivity(
                appContext,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Features.isOn(appContext, Features.NOTIFICATION_PANEL)) {
            try {
                manager.notify(PANEL_NOTIFICATION_ID, buildPanelNotification(appContext, appPendingIntent));
                manager.cancel(FOOD_NOTIFICATION_ID);
                manager.cancel(CIGARETTE_NOTIFICATION_ID);
                manager.cancel(PROTEIN_NOTIFICATION_ID);
                scheduleRefresh(appContext);
                return;
            } catch (RuntimeException error) {
                // A panel the system refuses must not cost Sejio the counters: fall back to the classic three.
                PausaLog.e("notification", "panel failed, showing the classic notifications", error);
                manager.cancel(PANEL_NOTIFICATION_ID);
            }
        } else {
            manager.cancel(PANEL_NOTIFICATION_ID);
            cancelRefresh(appContext);
        }

        manager.notify(FOOD_NOTIFICATION_ID, buildFoodNotification(appContext, appPendingIntent));
        manager.notify(CIGARETTE_NOTIFICATION_ID, buildCigaretteNotification(appContext, appPendingIntent));
        manager.notify(PROTEIN_NOTIFICATION_ID, buildProteinNotification(appContext, appPendingIntent));
    }

    // ------------------------------------------------------------------ panel (feature notification_panel)

    /** Light-on-sky tints for the three counters (the app's flame, sage and stone, lifted for a dark ground). */
    private static final int CAL_TINT = 0xFFF2C46B, PROTEIN_TINT = 0xFFB9CFA8, CIG_TINT = 0xFFDCD6C9, OVER_TINT = 0xFFEE8B63;
    private static final int ON_SKY_MUTED = 0xB3F8F5ED;

    /**
     * One notification for the three counters: a one-line summary when collapsed and, expanded, a
     * panel over a sky that follows the hour (NotificationSky) with two short buttons per counter.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    private static Notification buildPanelNotification(Context context, PendingIntent appPendingIntent) {
        Calendar now = Calendar.getInstance();
        int calories = CalorieStore.get(context), limit = CalorieStore.getCalorieLimit(context);
        int protein = CalorieStore.getProtein(context), proteinGoal = CalorieStore.getProteinGoal(context);
        int cigarettes = CalorieStore.getCigaretteCount(context), cigaretteGoal = CalorieStore.getCigaretteGoal(context);
        int remaining = limit - calories;

        RemoteViews panel = new RemoteViews(context.getPackageName(), R.layout.notification_panel);
        panel.setImageViewBitmap(R.id.panel_sky, sky(context, now, 64, R.dimen.notification_panel_height));
        panel.setTextViewText(R.id.panel_greeting, PausaUi.greeting() + " · "
                + new java.text.SimpleDateFormat("EEEE d", PausaUi.SPANISH).format(now.getTime()));
        panel.setTextViewText(R.id.panel_status, remaining >= 0
                ? "Quedan " + PausaUi.number(remaining) + " kcal"
                : "+" + PausaUi.number(-(long) remaining) + " kcal");

        counterRow(context, panel, R.id.panel_cal_icon, "flame", CAL_TINT,
                R.id.panel_cal_value, value(PausaUi.number(calories), " / " + PausaUi.number(limit) + " kcal"),
                R.id.panel_cal_bar, R.id.panel_cal_over, calories, limit, true);
        button(panel, R.id.panel_cal_a, "+100", "Añadir 100 kcal", addPendingIntent(context, 100, 11));
        button(panel, R.id.panel_cal_b, "+200", "Añadir 200 kcal", addPendingIntent(context, 200, 12));

        counterRow(context, panel, R.id.panel_protein_icon, "protein", PROTEIN_TINT,
                R.id.panel_protein_value, value(protein + " g", " / " + proteinGoal + " g"),
                R.id.panel_protein_bar, R.id.panel_protein_over, protein, proteinGoal, false);
        button(panel, R.id.panel_protein_a, "+5", "Añadir 5 g de proteína", proteinPendingIntent(context, 5, 13));
        button(panel, R.id.panel_protein_b, "+10", "Añadir 10 g de proteína", proteinPendingIntent(context, 10, 14));

        counterRow(context, panel, R.id.panel_cig_icon, "cigarette", CIG_TINT,
                R.id.panel_cig_value, value(String.valueOf(cigarettes), " / " + cigaretteGoal + (cigaretteGoal == 1 ? " cigarro" : " cigarros")),
                R.id.panel_cig_bar, R.id.panel_cig_over, cigarettes, cigaretteGoal, true);
        button(panel, R.id.panel_cig_a, "\u22121", "Quitar un cigarro", cigarettePendingIntent(context, -1, 15));
        button(panel, R.id.panel_cig_b, "+1", "Añadir un cigarro", cigarettePendingIntent(context, 1, 16));

        RemoteViews compact = new RemoteViews(context.getPackageName(), R.layout.notification_compact);
        compact.setImageViewBitmap(R.id.compact_sky, sky(context, now, 120, R.dimen.notification_compact_height));
        compact.setImageViewBitmap(R.id.compact_cal_icon, symbol(context, "flame", calories > limit ? OVER_TINT : CAL_TINT, 16));
        compact.setTextViewText(R.id.compact_cal_value, value(PausaUi.number(calories), " / " + PausaUi.number(limit)));
        compact.setImageViewBitmap(R.id.compact_protein_icon, symbol(context, "protein", PROTEIN_TINT, 16));
        compact.setTextViewText(R.id.compact_protein_value, value(protein + " g", ""));
        compact.setImageViewBitmap(R.id.compact_cig_icon, symbol(context, "cigarette", cigarettes > cigaretteGoal ? OVER_TINT : CIG_TINT, 16));
        compact.setTextViewText(R.id.compact_cig_value, value(String.valueOf(cigarettes), "/" + cigaretteGoal));

        String summary = PausaUi.number(calories) + " / " + PausaUi.number(limit) + " kcal · "
                + protein + " g · " + cigarettes + "/" + cigaretteGoal + " cigarros";
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, PANEL_CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Hoy")
                .setContentText(summary)
                .setContentIntent(appPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setColor(PausaUi.skyColor(now.get(Calendar.HOUR_OF_DAY)))
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(compact)
                .setCustomBigContentView(panel);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.build();
    }

    private static void counterRow(Context context, RemoteViews views, int iconId, String symbol, int tint,
                                   int valueId, CharSequence value, int barId, int overId,
                                   int amount, int goal, boolean overIsBad) {
        boolean over = overIsBad && amount > goal;
        views.setImageViewBitmap(iconId, symbol(context, symbol, over ? OVER_TINT : tint, 20));
        views.setTextViewText(valueId, value);
        int progress = goal <= 0 ? 0 : (int) Math.min(1000, Math.max(0, amount * 1000L / goal));
        views.setViewVisibility(barId, over ? View.GONE : View.VISIBLE);
        views.setViewVisibility(overId, over ? View.VISIBLE : View.GONE);
        views.setProgressBar(over ? overId : barId, 1000, progress, false);
    }

    private static void button(RemoteViews views, int id, String label, String spoken, PendingIntent action) {
        views.setTextViewText(id, label);
        views.setContentDescription(id, spoken);
        views.setOnClickPendingIntent(id, action);
    }

    /** "1.240" in bold, then " / 2.450 kcal" smaller and muted. */
    private static CharSequence value(String main, String rest) {
        SpannableStringBuilder text = new SpannableStringBuilder(main);
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, main.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!rest.isEmpty()) {
            int start = text.length();
            text.append(rest);
            text.setSpan(new ForegroundColorSpan(ON_SKY_MUTED), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new RelativeSizeSpan(.82f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private static Bitmap symbol(Context context, String name, int color, int sizeDp) {
        PausaUi.Symbol symbol = new PausaUi.Symbol(context, name, color, sizeDp).stroke(2f);
        Bitmap bitmap = Bitmap.createBitmap(symbol.getIntrinsicWidth(), symbol.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        symbol.draw(new Canvas(bitmap));
        return bitmap;
    }

    /**
     * The sky at about the size it is shown (screen width minus the shade's margins), drawn at half
     * resolution: it is all soft gradients, and a small bitmap keeps the notification parcel light.
     */
    private static Bitmap sky(Context context, Calendar now, int horizontalMarginDp, int heightRes) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = Math.max(PausaUi.dp(context, 200), metrics.widthPixels - PausaUi.dp(context, horizontalMarginDp));
        int height = context.getResources().getDimensionPixelSize(heightRes);
        return NotificationSky.render(width / 2, height / 2, PausaUi.dp(context, 16) / 2f, now);
    }

    private static void scheduleRefresh(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        Calendar next = Calendar.getInstance();
        next.add(Calendar.HOUR_OF_DAY, 1);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 5);
        next.set(Calendar.MILLISECOND, 0);
        // Inexact and without wake-up: the shade only needs the new light when the phone is in use.
        alarms.set(AlarmManager.RTC, next.getTimeInMillis(), refreshPendingIntent(context));
    }

    private static void cancelRefresh(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(refreshPendingIntent(context));
    }

    private static PendingIntent refreshPendingIntent(Context context) {
        Intent intent = new Intent(context, CalorieActionReceiver.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, REFRESH_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification buildFoodNotification(Context context, PendingIntent appPendingIntent) {
        int total = CalorieStore.get(context);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Calorías")
                .setContentText(total + " / " + CalorieStore.getCalorieLimit(context) + " kcal")
                .setContentIntent(appPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(0xFF2F6F64)
                .addAction(R.drawable.ic_notification, "+100", addPendingIntent(context, 100, 1))
                .addAction(R.drawable.ic_notification, "Coffee +200", addPendingIntent(context, 200, 2))
                .addAction(R.drawable.ic_notification, "Shake +500", addPendingIntent(context, 500, 3));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        return builder.build();
    }

    private static Notification buildCigaretteNotification(Context context, PendingIntent appPendingIntent) {
        String progress = CalorieStore.getCigaretteCount(context)
                + "/" + CalorieStore.getCigaretteGoal(context);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Cigarettes")
                .setContentText(progress)
                .setContentIntent(appPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(0xFF2F6F64)
                .addAction(R.drawable.ic_notification, "-1", cigarettePendingIntent(context, -1, 4))
                .addAction(R.drawable.ic_notification, "+1", cigarettePendingIntent(context, 1, 5));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        return builder.build();
    }

    private static Notification buildProteinNotification(Context context, PendingIntent appPendingIntent) {
        String grams = CalorieStore.getProtein(context) + " g";
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Protein")
                .setContentText(grams)
                .setContentIntent(appPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(0xFF3B6FB0)
                .addAction(R.drawable.ic_notification, "+10", proteinPendingIntent(context, 10, 6))
                .addAction(R.drawable.ic_notification, "+25", proteinPendingIntent(context, 25, 7))
                .addAction(R.drawable.ic_notification, "+50", proteinPendingIntent(context, 50, 8));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        return builder.build();
    }

    static void cancel(Context context) {
        NotificationManager manager = context.getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(FOOD_NOTIFICATION_ID);
            manager.cancel(CIGARETTE_NOTIFICATION_ID);
            manager.cancel(PROTEIN_NOTIFICATION_ID);
            manager.cancel(PANEL_NOTIFICATION_ID);
        }
        cancelRefresh(context.getApplicationContext());
    }

    private static boolean canPostNotifications(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void ensureChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Counter",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Sticky counter notification");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);

        // Default importance without sound or vibration: the panel is never silenced into the collapsed
        // "Silent" group, and updating it never makes a noise.
        NotificationChannel panel = new NotificationChannel(PANEL_CHANNEL_ID, "Panel de Hoy", NotificationManager.IMPORTANCE_DEFAULT);
        panel.setDescription("Calorías, proteína y cigarros en un solo panel");
        panel.setSound(null, null);
        panel.enableVibration(false);
        panel.enableLights(false);
        panel.setShowBadge(false);
        manager.createNotificationChannel(panel);
    }

    private static PendingIntent addPendingIntent(Context context, int amount, int requestCode) {
        Intent intent = new Intent(context, CalorieActionReceiver.class);
        intent.setAction(ACTION_ADD);
        intent.putExtra(EXTRA_AMOUNT, amount);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent cigarettePendingIntent(Context context, int delta, int requestCode) {
        Intent intent = new Intent(context, CalorieActionReceiver.class);
        intent.setAction(ACTION_CIGARETTE_CHANGE);
        intent.putExtra(EXTRA_DELTA, delta);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent proteinPendingIntent(Context context, int grams, int requestCode) {
        Intent intent = new Intent(context, CalorieActionReceiver.class);
        intent.setAction(ACTION_PROTEIN_ADD);
        intent.putExtra(EXTRA_GRAMS, grams);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
