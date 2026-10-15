-- One row per day with what the phone reads from Health Connect (Pixel Watch through the Google
-- health app). The id is the day itself (YYYY-MM-DD), so two reads of the same day converge.
--   sleep_minutes     time asleep in the night that ends on this day (awake stages left out)
--   sleep_start/end   epoch ms of the main sleep session
--   steps             steps from 03:00 on this day to 03:00 the next (the day closes at 03:00)
--   exercise_minutes  total time of exercise sessions in that window
--   resting_hr        average resting heart rate, beats per minute
-- The phone overwrites the row as the day goes on; nothing else writes it.
CREATE TABLE health_days (
  id TEXT PRIMARY KEY,
  day TEXT NOT NULL,
  sleep_minutes INTEGER,
  sleep_start INTEGER,
  sleep_end INTEGER,
  steps INTEGER,
  exercise_minutes INTEGER,
  resting_hr INTEGER,
  source TEXT,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL
);
CREATE INDEX health_days_seq ON health_days (seq);
CREATE INDEX health_days_day ON health_days (day);
