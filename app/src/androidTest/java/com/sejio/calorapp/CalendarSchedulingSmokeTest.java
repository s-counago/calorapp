package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.Bundle;

public final class CalendarSchedulingSmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Context context = getTargetContext();
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        Bundle result = new Bundle();
        int status = Activity.RESULT_OK;
        long task = -1;
        try {
            CalendarSync.prefs(context).edit().clear().putBoolean("enabled", true).commit();
            CalendarSync.request(context);
            assertScheduled(scheduler);
            scheduler.cancel(CalendarSync.JOB);
            CalendarSync.resume(context);
            assertScheduled(scheduler);
            scheduler.cancel(CalendarSync.JOB);
            task = TaskStore.add(context, "Calendar scheduling regression fixture");
            TaskSheets.Plan plan = new TaskSheets.Plan(1, 0);
            plan.minute = 9 * 60 + 15; plan.minutes = 45;
            TaskSheets.applyPlan(context, task, plan);
            assertScheduled(scheduler);
            scheduler.cancel(CalendarSync.JOB);
            TaskScheduleStore.Schedule saved = TaskScheduleStore.first(context, task, DayPlanStore.day(1));
            if (saved == null || saved.minute() != 555 || saved.minutes != 45) throw new AssertionError("task time lost");
            CalendarSync.prefs(context).edit().putBoolean("enabled", false).commit();
            CalendarSync.request(context);
            if (!scheduler.getAllPendingJobs().isEmpty()) throw new AssertionError("disabled calendar scheduled work");
            result.putString("stream", "PASS: connected Calendar queues work; timed task persists; disconnected Calendar schedules nothing\n");
        } catch (Throwable failure) {
            status = Activity.RESULT_CANCELED;
            result.putString("stream", android.util.Log.getStackTraceString(failure));
        } finally {
            CalendarSync.prefs(context).edit().clear().commit();
            scheduler.cancel(CalendarSync.JOB);
            if (task >= 0) {
                TaskScheduleStore.removeDay(context, task, DayPlanStore.day(1));
                TaskStore.delete(context, task);
            }
        }
        finish(status, result);
    }

    private void assertScheduled(JobScheduler scheduler) {
        for (JobInfo job : scheduler.getAllPendingJobs()) if (job.getId() == CalendarSync.JOB) {
            if (!job.isPersisted() || job.getNetworkType() != JobInfo.NETWORK_TYPE_ANY) throw new AssertionError("incorrect job constraints");
            return;
        }
        throw new AssertionError("Calendar job missing");
    }
}
