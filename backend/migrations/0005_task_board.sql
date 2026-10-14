-- Kanban board: the column a task sits in when it comes from the old Notion stack.
--   stack     stack infinito (Notion "Might do", "RAMIght do", "Obsidian calendar")
--   priority  prioridad (Notion "REAL")
--   vault     baúl
--   failed    fracasos
-- NULL means an ordinary Pausa task that is not on the board. A board task that is done keeps
-- its column and counts towards the done total (settings.tasks_done_base + done board tasks).
ALTER TABLE tasks ADD COLUMN board TEXT;
CREATE INDEX tasks_board ON tasks (board, status);
