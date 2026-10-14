-- Follow-ups: tasks that wait on someone else ("esperando a"). The Android app already
-- had them before the backend existed. A followed task is not planned on a day.
ALTER TABLE tasks ADD COLUMN following INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN waiting_for TEXT;
ALTER TABLE tasks ADD COLUMN expected_date TEXT;
ALTER TABLE tasks ADD COLUMN review_date TEXT;
