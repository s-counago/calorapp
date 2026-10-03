package com.sejio.calorapp;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

final class TaskResetScheduler {
    private static final int REQUEST_CODE = 30403;

    private TaskResetScheduler() {
    }

    static void scheduleNext(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        Calendar nextReset = Calendar.getInstance();
        nextReset.set(Calendar.HOUR_OF_DAY, 3);
        nextReset.set(Calendar.MINUTE, 0);
        nextReset.set(Calendar.SECOND, 0);
        nextReset.set(Calendar.MILLISECOND, 0);
        if (!nextReset.after(Calendar.getInstance())) {
            nextReset.add(Calendar.DAY_OF_YEAR, 1);
        }

        alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextReset.getTimeInMillis(),
                pendingIntent(context)
        );
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, DailyTaskResetReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
