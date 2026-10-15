-- El cajón: everything Sejio dumps from the phone (shared links, ideas, notes, half tasks) before
-- deciding what it is. It replaces his WhatsApp group. A capture stays raw until it is filed:
--   new       just captured, not looked at
--   kept      kept as a note, idea or link in the library (with kind and tags)
--   filed     turned into a task (task_id)
--   archived  read, done with or not worth keeping
-- `suggestion` is JSON written by an agent ({ kind, lane, title, tags, day, reason }); it never
-- changes anything by itself, Sejio accepts it with one tap.
CREATE TABLE captures (
  id TEXT PRIMARY KEY,
  text TEXT NOT NULL,
  url TEXT,
  title TEXT,
  source TEXT,
  status TEXT NOT NULL DEFAULT 'new',
  kind TEXT,
  tags TEXT,
  task_id TEXT,
  suggestion TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL
);
CREATE INDEX captures_seq ON captures (seq);
CREATE INDEX captures_status ON captures (status, created_at);
