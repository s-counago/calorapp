package com.sejio.calorapp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class NotificationHelper {
    static final String ACTION_ADD = "com.sejio.calorapp.ADD";
    static final String ACTION_CIGARETTE_CHANGE = "com.sejio.calorapp.CIGARETTE_CHANGE";
    static final String ACTION_PROTEIN_ADD = "com.sejio.calorapp.PROTEIN_ADD";
    static final String EXTRA_AMOUNT = "amount";
    static final String EXTRA_DELTA = "delta";
    static final String EXTRA_GRAMS = "grams";

    private static final String CHANNEL_ID = "counter";
    private static final int FOOD_NOTIFICATION_ID = 1001;
    private static final int CIGARETTE_NOTIFICATION_ID = 1002;
    private static final int PROTEIN_NOTIFICATION_ID = 1003;

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

        manager.notify(FOOD_NOTIFICATION_ID, buildFoodNotification(appContext, appPendingIntent));
        manager.notify(CIGARETTE_NOTIFICATION_ID, buildCigaretteNotification(appContext, appPendingIntent));
        manager.notify(PROTEIN_NOTIFICATION_ID, buildProteinNotification(appContext, appPendingIntent));
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
        }
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
