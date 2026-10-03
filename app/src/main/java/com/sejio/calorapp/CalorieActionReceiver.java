package com.sejio.calorapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CalorieActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (NotificationHelper.ACTION_ADD.equals(intent.getAction())) {
            int amount = intent.getIntExtra(NotificationHelper.EXTRA_AMOUNT, 100);
            CalorieStore.add(context, amount);
            NotificationHelper.show(context);
        } else if (NotificationHelper.ACTION_CIGARETTE_CHANGE.equals(intent.getAction())) {
            int delta = intent.getIntExtra(NotificationHelper.EXTRA_DELTA, 0);
            CalorieStore.adjustCigaretteCount(context, delta);
            NotificationHelper.show(context);
        } else if (NotificationHelper.ACTION_PROTEIN_ADD.equals(intent.getAction())) {
            int grams = intent.getIntExtra(NotificationHelper.EXTRA_GRAMS, 0);
            CalorieStore.addProtein(context, grams);
            NotificationHelper.show(context);
        }
    }
}
