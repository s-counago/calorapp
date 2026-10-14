package com.sejio.calorapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Run only on a disposable emulator: wipes Pausa data. Checks the local half of the sync: the
 * import from SharedPreferences, the outbox, counters as entries, the day plan on the task and
 * remote changes applied with last write wins.
 */
public final class SyncStoreSmokeTest extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            PausaDb db = PausaDb.get(context);
            PausaDb.Storage storage = db.new Storage();

            // 1. Import from the old preferences.
            PausaTestData.reset(context);
            long oldTask = 1_700_000_000_000L, oldFollowup = oldTask + 5;
            context.getSharedPreferences("daily_tasks", 0).edit().putString("tasks", new JSONArray()
                    .put(new JSONObject().put("id", oldTask).put("text", "Tarea vieja").put("done", true))
                    .put(new JSONObject().put("id", oldFollowup).put("text", "Esperar a Ana").put("following", true)
                            .put("waitingFor", "Ana").put("reviewDate", "2026-10-20")).toString()).commit();
            String today = DayPlanStore.day(0);
            context.getSharedPreferences("day_boards", 0).edit()
                    .putString("2026-01-01", "[[" + oldTask + "],[],[]]")
                    .putString(today, "[[],[],[" + oldTask + "]]").commit();
            context.getSharedPreferences("weekly_habits", 0).edit().putString("habits_v1", new JSONArray()
                    .put(new JSONObject().put("id", 42L).put("name", "Leer").put("weeklyGoal", 2).put("createdDay", "2026-01-05")
                            .put("completedDays", new JSONArray().put(today))).toString()).commit();
            context.getSharedPreferences("calories", 0).edit().putInt("total", 900).putInt("protein_total", 40)
                    .putInt("cigarette_count", 3).putInt("calorie_limit", 2200).commit();
            PausaTestData.reimport(context);

            List<TaskStore.Task> tasks = TaskStore.getAll(context);
            check(tasks.size() == 2 && tasks.get(0).id == oldTask && tasks.get(0).done, "tasks keep their ids, order and state");
            TaskStore.Task followup = TaskStore.find(context, oldFollowup);
            check(followup.following && followup.waitingFor.equals("Ana") && followup.reviewDate.equals("2026-10-20"), "follow-ups imported");
            check(DayPlanStore.read(context, today).get(2).contains(oldTask), "the latest plan of a task wins");
            check(!DayPlanStore.contains(DayPlanStore.read(context, "2026-01-01"), oldTask), "older plans of the same task are dropped");
            List<HabitStore.Habit> habits = HabitStore.getAll(context);
            check(habits.size() == 1 && habits.get(0).id == 42L && HabitStore.isDoneToday(habits.get(0)), "habit and check imported");
            check(CalorieStore.get(context) == 900 && CalorieStore.getProtein(context) == 40 && CalorieStore.getCigaretteCount(context) == 3, "counters imported as entries");
            check(CalorieStore.getCalorieLimit(context) == 2200 && CalorieStore.getProteinGoal(context) == 120, "goals imported, defaults otherwise");
            int queued = db.count("SELECT COUNT(*) FROM outbox");
            check(queued == 2 + 1 + 1 + 3 + 1, "everything imported is queued: " + queued);
            check(context.getSharedPreferences("daily_tasks", 0).contains("tasks"), "old preferences stay as a backup");

            // 2. Local edits: full records in the outbox, one entry per record.
            PausaTestData.reset(context);
            long id = TaskStore.add(context, "Comprar pan");
            TaskStore.setText(context, id, "Comprar pan integral");
            TaskStore.setDone(context, id, true);
            List<SyncEngine.Pending> pending = storage.pending(100);
            check(pending.size() == 1, "one outbox entry per record");
            SyncEngine.Pending sent = pending.get(0);
            check(sent.data.getString("title").equals("Comprar pan integral") && sent.data.getString("status").equals("done")
                    && !sent.data.isNull("completed_at"), "the outbox sends the whole current record");
            TaskStore.setText(context, id, "Comprar pan de centeno");
            storage.acknowledge(pending);
            check(storage.pending(100).size() == 1, "an edit made while sending stays queued");
            storage.acknowledge(storage.pending(100));
            check(storage.pending(100).isEmpty(), "acknowledged changes leave the outbox");

            TaskStore.Task task = TaskStore.find(context, id);
            int index = TaskStore.indexOf(context, id);
            TaskStore.delete(context, id);
            List<SyncEngine.Pending> deletion = storage.pending(100);
            check(deletion.size() == 1 && deletion.get(0).deleted, "deletions travel as tombstones");
            TaskStore.restore(context, task, index);
            check(TaskStore.find(context, id) != null && !storage.pending(100).get(0).deleted, "undo brings the same record back");
            storage.acknowledge(storage.pending(100));

            // 3. Counters are entries per day.
            CalorieStore.add(context, 300);
            CalorieStore.add(context, 200);
            CalorieStore.adjustCigaretteCount(context, -5);
            check(CalorieStore.get(context) == 500 && CalorieStore.getCigaretteCount(context) == 0, "sums of today's entries, never below zero");
            CalorieStore.reset(context);
            check(CalorieStore.get(context) == 0, "reset is one negative entry");
            check(db.count("SELECT COUNT(*) FROM metric_entries WHERE kind = 'calories'") == 3, "three calorie entries");
            check(db.count("SELECT COUNT(*) FROM metric_entries WHERE kind = 'cigarettes'") == 0, "no entry when nothing changes");
            String yesterday = DayPlanStore.day(-1);
            CalorieStore.setTotal(context, "calories", yesterday, 2100);
            CalorieStore.setTotal(context, "calories", yesterday, 1800);
            CalorieStore.setTotal(context, "calories", yesterday, 1800);
            check(CalorieStore.total(context, "calories", yesterday) == 1800 && CalorieStore.get(context) == 0, "a past day is fixed without touching today");
            check(db.count("SELECT COUNT(*) FROM metric_entries WHERE day = ?", yesterday) == 2, "one correction entry per change, none when equal");

            // 4. Day plan lives on the task.
            long a = TaskStore.add(context, "A"), b = TaskStore.add(context, "B");
            List<List<Long>> plan = DayPlanStore.empty();
            plan.get(1).add(b);
            plan.get(1).add(a);
            DayPlanStore.save(context, today, plan);
            Map<String, Object> rowA = db.row("tasks", TaskStore.uid(context, a));
            check(today.equals(rowA.get("plan_date")) && "afternoon".equals(rowA.get("plan_slot")) && ((Number) rowA.get("position")).doubleValue() == 2,
                    "plan date, slot and order on the task");
            check(DayPlanStore.read(context, today).get(1).equals(List.of(b, a)), "plan read back in order");
            storage.acknowledge(storage.pending(500));
            DayPlanStore.save(context, today, DayPlanStore.read(context, today));
            check(storage.pending(100).isEmpty(), "saving an unchanged plan queues nothing");

            // 5. Remote changes: last write wins, unknown ids become new local rows.
            String uidA = TaskStore.uid(context, a);
            long localAt = (Long) db.row("tasks", uidA).get("updated_at");
            List<SyncEngine.Change> page = new ArrayList<>();
            page.add(new SyncEngine.Change("tasks", uidA, localAt - 1, false, new JSONObject().put("title", "Vieja").put("status", "todo").put("created_at", 1)));
            page.add(new SyncEngine.Change("tasks", "web-1", localAt, false, new JSONObject().put("title", "Desde la web").put("status", "todo")
                    .put("created_at", localAt).put("plan_date", today).put("plan_slot", "evening").put("position", 1)));
            int applied = storage.applyPage(page, 77);
            check(applied == 1 && TaskStore.find(context, a).text.equals("A"), "an older remote version does not overwrite");
            check(storage.cursor() == 77, "the cursor moves with the page");
            TaskStore.Task web = null;
            for (TaskStore.Task item : TaskStore.getAll(context)) if (item.text.equals("Desde la web")) web = item;
            check(web != null && DayPlanStore.read(context, today).get(2).contains(web.id), "a task from the web appears in the list and the plan");
            TaskStore.setText(context, web.id, "Editada en el móvil");
            check(storage.pending(10).size() == 1 && storage.pending(10).get(0).id.equals("web-1"), "remote rows edited locally go back with their id");
            storage.applyPage(Collections.singletonList(new SyncEngine.Change("tasks", "web-1", System.currentTimeMillis() + 60_000, true,
                    new JSONObject().put("title", "Borrada").put("status", "todo").put("created_at", 1))), 78);
            check(TaskStore.find(context, web.id) == null && storage.pending(10).isEmpty(), "a newer remote deletion wins and clears the queued edit");

            Map<String, Object> echo = db.row("tasks", uidA);
            check(storage.applyPage(Collections.singletonList(new SyncEngine.Change("tasks", uidA, (Long) echo.get("updated_at"), false,
                    SyncSchema.toWire("tasks", echo))), 79) == 0, "our own push coming back is not applied again");

            // 6. Habit checks from another device are scored on the phone.
            long habit = HabitStore.add(context, "Meditar", 1);
            String habitUid = HabitStore.getAll(context).get(0).uid;
            storage.applyPage(Collections.singletonList(new SyncEngine.Change("habit_checks", habitUid + ":" + today, System.currentTimeMillis(), false,
                    new JSONObject().put("habit_id", habitUid).put("day", today))), 79);
            HabitStore.Habit meditar = HabitStore.getAll(context).get(0);
            check(meditar.id == habit && HabitStore.isDoneToday(meditar) && HabitStore.totalPoints(meditar) == HabitStore.MINIMUM_FIRST_REWARD,
                    "a remote check counts and earns the weekly reward");

            result.putString("stream", "PASS: " + checks + " sync store checks.");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
