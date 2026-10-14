-- Logs sent by the apps (mainly the Android client) so problems on the phone can be
-- read from the web, the API or an agent without a cable. Kept for 30 days.
CREATE TABLE client_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT,
  at INTEGER NOT NULL,
  level TEXT NOT NULL CHECK (level IN ('debug', 'info', 'warn', 'error')),
  tag TEXT NOT NULL,
  message TEXT NOT NULL,
  data TEXT,
  app_version TEXT,
  request_id TEXT,
  received_at INTEGER NOT NULL
);
CREATE INDEX client_logs_at ON client_logs (at);
CREATE INDEX client_logs_level ON client_logs (level, at);

-- Where each device is in the sync, to spot a phone that stopped syncing or fell behind.
ALTER TABLE devices ADD COLUMN app_version TEXT;
ALTER TABLE devices ADD COLUMN last_pull_at INTEGER;
ALTER TABLE devices ADD COLUMN last_pull_cursor INTEGER;
ALTER TABLE devices ADD COLUMN last_push_at INTEGER;
ALTER TABLE devices ADD COLUMN last_push_count INTEGER;
ALTER TABLE devices ADD COLUMN last_error_at INTEGER;

-- Every accepted write, with who made it. The previous version of a record is the
-- entry before it, so "why did this task change or disappear?" has an answer.
CREATE TABLE change_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  seq INTEGER NOT NULL,
  table_name TEXT NOT NULL,
  record_id TEXT NOT NULL,
  deleted INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  data TEXT NOT NULL,
  actor TEXT NOT NULL,
  request_id TEXT,
  logged_at INTEGER NOT NULL
);
CREATE INDEX change_log_record ON change_log (table_name, record_id, seq);
CREATE INDEX change_log_seq ON change_log (seq);
