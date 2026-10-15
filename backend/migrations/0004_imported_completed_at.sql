-- One-off repair. When the phone first moved its old data into the synced database
-- (2026-10-14 21:43), every task already done got that moment as completed_at, so the
-- weekly review counted 191 tasks as done that week. The old app never stored when a
-- task was finished, so the honest value is "unknown" (NULL).
-- The rows get a fresh updated_at and seq, so phones pull the repair, and each one is
-- written to change_log like any other change. Matches no rows on a fresh database.
CREATE TABLE repair_0004 AS
  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS n
  FROM tasks WHERE status = 'done' AND completed_at = 1792014234927;

UPDATE tasks SET
  completed_at = NULL,
  updated_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000,
  seq = (SELECT value FROM meta WHERE key = 'seq') + (SELECT n FROM repair_0004 WHERE repair_0004.id = tasks.id)
WHERE id IN (SELECT id FROM repair_0004);

INSERT INTO change_log (seq, table_name, record_id, deleted, updated_at, data, actor, request_id, logged_at)
  SELECT seq, 'tasks', id, deleted, updated_at,
    json_object('title', title, 'notes', notes, 'status', status, 'project', project, 'area', area,
      'priority', priority, 'due_date', due_date, 'plan_date', plan_date, 'plan_slot', plan_slot,
      'position', position, 'completed_at', completed_at, 'source', source, 'source_id', source_id,
      'created_at', created_at, 'following', following, 'waiting_for', waiting_for,
      'expected_date', expected_date, 'review_date', review_date),
    'migration:0004', NULL, updated_at
  FROM tasks WHERE id IN (SELECT id FROM repair_0004);

UPDATE meta SET value = value + (SELECT COUNT(*) FROM repair_0004) WHERE key = 'seq';

DROP TABLE repair_0004;
