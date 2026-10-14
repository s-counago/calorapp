-- Every synced table shares the same bookkeeping columns:
--   id          client-generated UUID (or a deterministic key, see habit_checks)
--   updated_at  milliseconds since epoch set by whoever made the change; last write wins
--   deleted     tombstone, so deletions reach devices that were offline
--   seq         server change number, used by devices to pull what changed since their cursor

CREATE TABLE meta (
  key TEXT PRIMARY KEY,
  value INTEGER NOT NULL
);
INSERT INTO meta (key, value) VALUES ('seq', 0);

CREATE TABLE tasks (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  notes TEXT,
  status TEXT NOT NULL DEFAULT 'todo' CHECK (status IN ('todo', 'done', 'cancelled')),
  project TEXT,
  area TEXT,
  priority INTEGER,
  due_date TEXT,
  plan_date TEXT,
  plan_slot TEXT CHECK (plan_slot IS NULL OR plan_slot IN ('morning', 'afternoon', 'evening')),
  position REAL,
  completed_at INTEGER,
  source TEXT,
  source_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL
);
CREATE INDEX tasks_seq ON tasks (seq);
CREATE INDEX tasks_plan ON tasks (plan_date, plan_slot, position);
CREATE INDEX tasks_completed ON tasks (completed_at);
CREATE INDEX tasks_source ON tasks (source, source_id);

CREATE TABLE habits (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  weekly_goal INTEGER NOT NULL DEFAULT 1 CHECK (weekly_goal BETWEEN 1 AND 7),
  created_day TEXT NOT NULL,
  archived INTEGER NOT NULL DEFAULT 0,
  position REAL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL
);
CREATE INDEX habits_seq ON habits (seq);

-- One row per habit and day, keyed "<habit_id>:<day>" so two devices checking
-- the same day offline converge on one row. Unchecking sets deleted = 1.
CREATE TABLE habit_checks (
  id TEXT PRIMARY KEY,
  habit_id TEXT NOT NULL,
  day TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL
);
CREATE INDEX habit_checks_seq ON habit_checks (seq);
CREATE INDEX habit_checks_day ON habit_checks (day);

-- Counters are stored as entries ("+300 kcal at 14:02") instead of a daily total,
-- so additions made offline on the phone and on the web never overwrite each other.
CREATE TABLE metric_entries (
  id TEXT PRIMARY KEY,
  day TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('calories', 'protein', 'cigarettes')),
  amount REAL NOT NULL,
  at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL
);
CREATE INDEX metric_entries_seq ON metric_entries (seq);
CREATE INDEX metric_entries_day ON metric_entries (day, kind);

-- Small key/value settings such as daily goals: calorie_goal, protein_goal, cigarette_goal.
CREATE TABLE settings (
  id TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL
);
CREATE INDEX settings_seq ON settings (seq);

-- Long-lived tokens handed to signed-in devices (the Android app). Only the hash is stored.
CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at INTEGER
);
