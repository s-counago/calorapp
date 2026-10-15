-- One model for every task (see propuestas/pausa-rediseno-tareas-y-flujos.md):
--   lane         what the task is to Sejio. It is not a column: it is read from `board` and `following`.
--                board NULL and not following = inbox (bandeja); following = waiting (esperando);
--                otherwise the board column (stack, priority, vault, failed).
--   appointment  when it gets done: plan_date, plan_slot and now an optional time and length.
--                They replace the half-hour week grid that only lived on the phone.
--   habit_id     a task made from a habit with one tap. Completing it checks the habit that day.
-- All three are optional, so phones on older versions keep syncing.
ALTER TABLE tasks ADD COLUMN plan_time INTEGER;
ALTER TABLE tasks ADD COLUMN plan_minutes INTEGER;
ALTER TABLE tasks ADD COLUMN habit_id TEXT;
CREATE INDEX tasks_habit ON tasks (habit_id, plan_date);
