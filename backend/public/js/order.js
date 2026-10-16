// Order of a board column or lane, kept apart from the DOM so the tests can load it.

/**
 * Newest first. With datedLast, tasks without a date stay on top (newest first) and those with a day
 * or a due date go to the bottom, soonest first. Same rule as TaskStore.listOrder on the phone.
 */
export const laneOrder = (datedLast) => (a, b) => {
  if (datedLast) {
    const da = a.plan_date ?? a.due_date ?? null;
    const db = b.plan_date ?? b.due_date ?? null;
    if (!da !== !db) return da ? 1 : -1;
    if (da && db && da !== db) return da < db ? -1 : 1;
  }
  return b.created_at - a.created_at;
};
