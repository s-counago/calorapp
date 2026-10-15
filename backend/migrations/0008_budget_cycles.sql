-- The money summary the phone works out for each payday-to-payday cycle, so the web and agents can
-- see the budget without the bank data ever leaving the phone. No movements, merchants, accounts
-- or credentials: only totals per cycle, per budget line and per category. The id is the first day
-- of the cycle (YYYY-MM-DD). Only the phone writes it, and only with the budget_cloud feature on.
-- Amounts are euro cents.
--   income, fixed, saved, savings_target       income of the cycle, fixed lines, put aside and the target
--   free_budget, free_spent, available          free money: theoretical, spent and still left today
--   daily_allowance, projection, savings_at_close  per day left, left at close at this pace, savings at close
--   unexpected, covered, cushion_left           unexpected obligations, what the cushion took, cushion left
--   lines        JSON [{ id, name, cap, expected, paid, logged, status, due, frequency }] (caps = topes)
--   categories   JSON [{ key, label, cents }] free spending per category
CREATE TABLE budget_cycles (
  id TEXT PRIMARY KEY,
  start TEXT NOT NULL,
  end TEXT NOT NULL,
  current INTEGER NOT NULL DEFAULT 0,
  income INTEGER,
  fixed INTEGER,
  saved INTEGER,
  savings_target INTEGER,
  free_budget INTEGER,
  free_spent INTEGER,
  available INTEGER,
  daily_allowance INTEGER,
  projection INTEGER,
  savings_at_close INTEGER,
  unexpected INTEGER,
  covered INTEGER,
  cushion_left INTEGER,
  lines TEXT,
  categories TEXT,
  computed_at INTEGER,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL
);
CREATE INDEX budget_cycles_seq ON budget_cycles (seq);
CREATE INDEX budget_cycles_start ON budget_cycles (start);
