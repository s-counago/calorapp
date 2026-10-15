// Operations shared by the REST API and the MCP server. Every write goes through
// applyChanges, so edits made by the web or an agent reach the phone on its next pull.

import { ValidationError, toRecord, type Row, type TableName } from './schema';
import { applyChanges } from './sync';

export const GOAL_DEFAULTS = { calorie_goal: 2450, protein_goal: 120, cigarette_goal: 12 } as const;
export const METRIC_KINDS = ['calories', 'protein', 'cigarettes'] as const;
export type MetricKind = (typeof METRIC_KINDS)[number];
export const SLOTS = ['morning', 'afternoon', 'evening'] as const;

export class NotFoundError extends Error {}

export function localDay(timeZone: string, at: Date = new Date()): string {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(at);
}

/** The day closes at 03:00: something logged at 01:30 still belongs to the previous day. */
export const DAY_START_HOUR = 3;

/** Today as Sejio counts it, in the given time zone, with the 03:00 cutoff. */
export function currentDay(timeZone: string, at: Date = new Date()): string {
  return localDay(timeZone, new Date(at.getTime() - DAY_START_HOUR * 3600_000));
}

export function addDays(day: string, days: number): string {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** Monday of the week that contains `day`. */
export function weekStart(day: string): string {
  const weekday = new Date(`${day}T00:00:00Z`).getUTCDay();
  return addDays(day, -((weekday + 6) % 7));
}

/** Epoch milliseconds of local midnight at the start of `day`. */
export function dayStartMillis(day: string, timeZone: string): number {
  const utcMidnight = Date.parse(`${day}T00:00:00Z`);
  // Offset of the zone at that instant, e.g. +02:00 in summer for Europe/Madrid.
  const parts = new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'longOffset' }).formatToParts(new Date(utcMidnight));
  const name = parts.find((part) => part.type === 'timeZoneName')?.value ?? 'GMT';
  const match = /GMT([+-])(\d{2}):(\d{2})/.exec(name);
  const offset = match ? (match[1] === '-' ? -1 : 1) * (Number(match[2]) * 60 + Number(match[3])) * 60000 : 0;
  return utcMidnight - offset;
}

async function getRow(db: D1Database, table: TableName, id: string): Promise<Row | null> {
  return db.prepare(`SELECT * FROM ${table} WHERE id = ?`).bind(id).first<Row>();
}

async function save(db: D1Database, table: TableName, id: string, data: Record<string, unknown>, deleted = false) {
  await applyChanges(db, [{ table, id, updated_at: Date.now(), deleted, data }]);
}

// Tasks ---------------------------------------------------------------------------

export interface Task extends Record<string, unknown> {
  id: string;
  title: string;
  status: string;
}

function taskFromRow(row: Row): Task {
  return { id: String(row.id), ...toRecord('tasks', row), updated_at: Number(row.updated_at) } as unknown as Task;
}

export interface TaskFilter {
  status?: string;
  plan_date?: string;
  project?: string;
  due_before?: string;
  following?: boolean;
  search?: string;
  limit?: number;
}

export async function listTasks(db: D1Database, filter: TaskFilter = {}): Promise<Task[]> {
  const where = ['deleted = 0'];
  const params: (string | number)[] = [];
  if (filter.status) { where.push('status = ?'); params.push(filter.status); }
  if (filter.plan_date) { where.push('plan_date = ?'); params.push(filter.plan_date); }
  if (filter.project) { where.push('project = ?'); params.push(filter.project); }
  if (filter.following !== undefined) { where.push('following = ?'); params.push(filter.following ? 1 : 0); }
  if (filter.due_before) { where.push('due_date IS NOT NULL AND due_date <= ?'); params.push(filter.due_before); }
  if (filter.search) { where.push("(title LIKE ? ESCAPE '\\' OR notes LIKE ? ESCAPE '\\')"); const like = `%${filter.search.replace(/[\\%_]/g, '\\$&')}%`; params.push(like, like); }
  const limit = Math.min(Math.max(filter.limit ?? 200, 1), 1000);
  const { results } = await db
    .prepare(
      `SELECT * FROM tasks WHERE ${where.join(' AND ')} ` +
        `ORDER BY status = 'done', plan_date IS NULL, plan_date, plan_slot, position, due_date IS NULL, due_date, created_at LIMIT ${limit}`,
    )
    .bind(...params)
    .all<Row>();
  return results.map(taskFromRow);
}

export async function getTask(db: D1Database, id: string): Promise<Task> {
  const row = await getRow(db, 'tasks', id);
  if (!row || row.deleted === 1) throw new NotFoundError(`No task ${id}`);
  return taskFromRow(row);
}

const TASK_EDITABLE = ['title', 'notes', 'status', 'project', 'area', 'priority', 'due_date', 'plan_date', 'plan_slot', 'position', 'source', 'source_id', 'following', 'waiting_for', 'expected_date', 'review_date'] as const;

export async function createTask(db: D1Database, input: Record<string, unknown>): Promise<Task> {
  const id = typeof input.id === 'string' ? input.id : crypto.randomUUID();
  const now = Date.now();
  const data: Record<string, unknown> = { status: 'todo', created_at: now };
  for (const key of TASK_EDITABLE) if (input[key] !== undefined) data[key] = input[key];
  if (data.status === 'done') data.completed_at = now;
  await save(db, 'tasks', id, data);
  return getTask(db, id);
}

export async function updateTask(db: D1Database, id: string, patch: Record<string, unknown>): Promise<Task> {
  const current = await getTask(db, id);
  const data: Record<string, unknown> = { ...current };
  for (const key of TASK_EDITABLE) if (patch[key] !== undefined) data[key] = patch[key];
  if (data.status === 'done' && current.status !== 'done') data.completed_at = Date.now();
  if (data.status !== 'done') data.completed_at = null;
  await save(db, 'tasks', id, data);
  return getTask(db, id);
}

export async function deleteTask(db: D1Database, id: string): Promise<void> {
  const current = await getTask(db, id);
  await save(db, 'tasks', id, current, true);
}

// Habits --------------------------------------------------------------------------

export interface HabitWeek {
  id: string;
  name: string;
  weekly_goal: number;
  created_day: string;
  archived: boolean;
  position: number | null;
  week_start: string;
  week_days_done: string[];
  done_today: boolean;
}

export async function listHabits(db: D1Database, today: string): Promise<HabitWeek[]> {
  const start = weekStart(today);
  const { results: habits } = await db.prepare(`SELECT * FROM habits WHERE deleted = 0 AND archived = 0 ORDER BY position, name`).all<Row>();
  const { results: checks } = await db
    .prepare(`SELECT habit_id, day FROM habit_checks WHERE deleted = 0 AND day BETWEEN ? AND ?`)
    .bind(start, addDays(start, 6))
    .all<{ habit_id: string; day: string }>();
  return habits.map((row) => {
    const days = checks.filter((check) => check.habit_id === row.id).map((check) => check.day).sort();
    return {
      id: String(row.id),
      ...(toRecord('habits', row) as Omit<HabitWeek, 'id' | 'week_start' | 'week_days_done' | 'done_today'>),
      week_start: start,
      week_days_done: days,
      done_today: days.includes(today),
    };
  });
}

export async function createHabit(db: D1Database, input: { name: string; weekly_goal?: number; id?: string }, today: string) {
  const id = input.id ?? crypto.randomUUID();
  await save(db, 'habits', id, { name: input.name, weekly_goal: input.weekly_goal ?? 1, created_day: today, archived: false });
  return id;
}

export async function setHabitCheck(db: D1Database, habitId: string, day: string, done: boolean) {
  const habit = await getRow(db, 'habits', habitId);
  if (!habit || habit.deleted === 1) throw new NotFoundError(`No habit ${habitId}`);
  await save(db, 'habit_checks', `${habitId}:${day}`, { habit_id: habitId, day }, !done);
}

// Counters ------------------------------------------------------------------------

export async function logMetric(db: D1Database, kind: MetricKind, amount: number, day: string) {
  if (!METRIC_KINDS.includes(kind)) throw new ValidationError(`kind must be one of ${METRIC_KINDS.join(', ')}`);
  const id = crypto.randomUUID();
  await save(db, 'metric_entries', id, { day, kind, amount, at: Date.now() });
  return id;
}

/**
 * Sets the total of a counter for a day by adding the difference as one correction entry, so
 * entries made elsewhere (another device, offline) still add up. Used to fix forgotten days.
 */
export async function setMetricTotal(db: D1Database, kind: MetricKind, total: number, day: string) {
  if (!METRIC_KINDS.includes(kind)) throw new ValidationError(`kind must be one of ${METRIC_KINDS.join(', ')}`);
  if (typeof total !== 'number' || !Number.isFinite(total) || total < 0) throw new ValidationError('total must be a number >= 0');
  const current = (await metricTotals(db, day, day)).find((row) => row.kind === kind)?.total ?? 0;
  const difference = total - current;
  if (difference === 0) return { id: null, previous: current, total };
  const id = await logMetric(db, kind, difference, day);
  return { id, previous: current, total };
}

export async function goals(db: D1Database): Promise<Record<keyof typeof GOAL_DEFAULTS, number>> {
  const { results } = await db.prepare(`SELECT id, value FROM settings WHERE deleted = 0 AND id IN ('calorie_goal', 'protein_goal', 'cigarette_goal')`).all<{ id: string; value: string }>();
  const out: Record<string, number> = { ...GOAL_DEFAULTS };
  for (const row of results) {
    const value = Number(row.value);
    if (Number.isFinite(value)) out[row.id] = value;
  }
  return out as Record<keyof typeof GOAL_DEFAULTS, number>;
}

async function metricTotals(db: D1Database, from: string, to: string) {
  const { results } = await db
    .prepare(`SELECT day, kind, SUM(amount) AS total FROM metric_entries WHERE deleted = 0 AND day BETWEEN ? AND ? GROUP BY day, kind`)
    .bind(from, to)
    .all<{ day: string; kind: MetricKind; total: number }>();
  return results;
}

// Summaries -----------------------------------------------------------------------

export async function daySummary(db: D1Database, day: string) {
  const [totals, goal, plan, habits] = await Promise.all([
    metricTotals(db, day, day),
    goals(db),
    listTasks(db, { plan_date: day }),
    listHabits(db, day),
  ]);
  const total = (kind: MetricKind) => totals.find((row) => row.kind === kind)?.total ?? 0;
  return {
    day,
    counters: {
      calories: { total: total('calories'), goal: goal.calorie_goal },
      protein: { total: total('protein'), goal: goal.protein_goal },
      cigarettes: { total: total('cigarettes'), goal: goal.cigarette_goal },
    },
    plan: Object.fromEntries([...SLOTS, null].map((slot) => [slot ?? 'unslotted', plan.filter((task) => (task.plan_slot ?? null) === slot)])),
    habits: habits.map((habit) => ({ id: habit.id, name: habit.name, done: habit.done_today })),
  };
}

export const REVIEW_PERIODS = ['week', 'month'] as const;
export type ReviewPeriod = (typeof REVIEW_PERIODS)[number];

/** First and last day of the Monday-to-Sunday week or the calendar month that contains `day`. */
export function periodRange(day: string, period: ReviewPeriod): { start: string; end: string } {
  if (period === 'week') {
    const start = weekStart(day);
    return { start, end: addDays(start, 6) };
  }
  const start = `${day.slice(0, 7)}-01`;
  const next = new Date(`${start}T00:00:00Z`);
  next.setUTCMonth(next.getUTCMonth() + 1);
  return { start, end: addDays(next.toISOString().slice(0, 10), -1) };
}

export async function weeklyReview(db: D1Database, anyDay: string, timeZone: string) {
  return review(db, anyDay, timeZone, 'week');
}

/**
 * Review of the week or month that contains `anyDay`: tasks completed and still pending,
 * habits against their weekly goal (scaled to the length of the period) and counter averages.
 */
export async function review(db: D1Database, anyDay: string, timeZone: string, period: ReviewPeriod = 'week') {
  if (!REVIEW_PERIODS.includes(period)) throw new ValidationError(`period must be one of ${REVIEW_PERIODS.join(', ')}`);
  const { start, end } = periodRange(anyDay, period);
  // Days close at 03:00, so the period runs from 03:00 on its first day to 03:00 after its last.
  const from = dayStartMillis(start, timeZone) + DAY_START_HOUR * 3600_000;
  const to = dayStartMillis(addDays(end, 1), timeZone) + DAY_START_HOUR * 3600_000;
  const [done, open, totals, goal, habits, checks] = await Promise.all([
    db.prepare(`SELECT * FROM tasks WHERE deleted = 0 AND status = 'done' AND completed_at >= ? AND completed_at < ? ORDER BY completed_at`).bind(from, to).all<Row>(),
    db.prepare(`SELECT * FROM tasks WHERE deleted = 0 AND status = 'todo' AND ((due_date IS NOT NULL AND due_date <= ?) OR (plan_date IS NOT NULL AND plan_date <= ?)) ORDER BY due_date, plan_date`).bind(end, end).all<Row>(),
    metricTotals(db, start, end),
    goals(db),
    db.prepare(`SELECT * FROM habits WHERE deleted = 0 AND archived = 0 ORDER BY position, name`).all<Row>(),
    db.prepare(`SELECT habit_id, day FROM habit_checks WHERE deleted = 0 AND day BETWEEN ? AND ?`).bind(start, end).all<{ habit_id: string; day: string }>(),
  ]);
  const length = Math.round((Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / 86_400_000) + 1;
  const days = Array.from({ length }, (_, index) => addDays(start, index));
  const perDay = days.map((day) => {
    const value = (kind: MetricKind) => totals.find((row) => row.day === day && row.kind === kind)?.total ?? 0;
    return { day, calories: value('calories'), protein: value('protein'), cigarettes: value('cigarettes') };
  });
  const logged = perDay.filter((row) => row.calories > 0 || row.protein > 0 || row.cigarettes > 0);
  const average = (key: 'calories' | 'protein' | 'cigarettes') =>
    logged.length ? Math.round(logged.reduce((sum, row) => sum + row[key], 0) / logged.length) : null;
  return {
    period,
    ...(period === 'week' ? { week_start: start, week_end: end } : {}),
    start,
    end,
    tasks: {
      completed: done.results.map(taskFromRow),
      pending_due_or_planned: open.results.map(taskFromRow),
    },
    habits: habits.results.map((row) => {
      const weeklyGoal = Number(row.weekly_goal);
      const target = Math.max(1, Math.round((weeklyGoal * length) / 7));
      const daysDone = new Set(checks.results.filter((check) => check.habit_id === row.id).map((check) => check.day)).size;
      return { id: String(row.id), name: String(row.name), weekly_goal: weeklyGoal, target, days_done: daysDone, met: daysDone >= target };
    }),
    counters: {
      days_logged: logged.length,
      average: { calories: average('calories'), protein: average('protein'), cigarettes: average('cigarettes') },
      goals: goal,
      days_within_calorie_goal: logged.filter((row) => row.calories > 0 && row.calories <= goal.calorie_goal).length,
      days_reaching_protein_goal: logged.filter((row) => row.protein >= goal.protein_goal).length,
      per_day: perDay,
    },
  };
}

// Features ------------------------------------------------------------------------

/**
 * Parts of the apps that Sejio can switch on or off from Ajustes → Funciones (or an agent with
 * set_feature). Each one is stored as the synced setting `feature:<key>` with value "on" or "off",
 * so the phone sees the change on its next pull. Android keeps the same list in `Features.java`:
 * add a feature to both places, with a default that keeps the app as it was.
 */
export const FEATURES = [
  {
    key: 'review_zoom',
    title: 'Resumen Día · Semana · Mes',
    description: 'Un selector encima de Hoy para alejarte del día y ver la semana o el mes: salud, hábitos, tareas y dinero.',
    default: false,
  },
] as const;
export type FeatureKey = (typeof FEATURES)[number]['key'];

export function featureSettingId(key: string) {
  return `feature:${key}`;
}

export async function listFeatures(db: D1Database) {
  const { results } = await db.prepare(`SELECT id, value FROM settings WHERE deleted = 0 AND id LIKE 'feature:%'`).all<{ id: string; value: string }>();
  return FEATURES.map((feature) => {
    const stored = results.find((row) => row.id === featureSettingId(feature.key))?.value;
    return { ...feature, enabled: stored === 'on' ? true : stored === 'off' ? false : feature.default };
  });
}

export async function setFeature(db: D1Database, key: string, enabled: boolean) {
  if (!FEATURES.some((feature) => feature.key === key)) throw new ValidationError(`Unknown feature. Use one of ${FEATURES.map((feature) => feature.key).join(', ')}`);
  if (typeof enabled !== 'boolean') throw new ValidationError('enabled must be true or false');
  await save(db, 'settings', featureSettingId(key), { value: enabled ? 'on' : 'off' });
  return (await listFeatures(db)).find((feature) => feature.key === key)!;
}
