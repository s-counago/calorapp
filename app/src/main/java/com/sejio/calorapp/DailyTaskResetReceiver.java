package com.sejio.calorapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class DailyTaskResetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TaskStore.resetDailyNotionReview(context);
        Intent refreshIntent = new Intent(TaskListView.ACTION_NOTION_REVIEW_RESET);
        refreshIntent.setPackage(context.getPackageName());
        context.sendBroadcast(refreshIntent);
        TaskResetScheduler.scheduleNext(context);
    }
}
