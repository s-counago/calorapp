// Operations shared by the REST API and the MCP server. Every write goes through
// applyChanges, so edits made by the web or an agent reach the phone on its next pull.

import { ValidationError, toRecord, type Row, type TableName } from './schema';
import { applyChanges } from './sync';

export const GOAL_DEFAULTS = { calorie_goal: 2450, protein_goal: 120, cigarette_goal: 12 } as const;
export const METRIC_KINDS = ['calories', 'protein', 'cigarettes'] as const;
export type MetricKind = (typeof METRIC_KINDS)[number];
export const SLOTS = ['morning', 'afternoon', 'evening'] as const;
/** Kanban columns, in board order: stack infinito, prioridad, baúl, fracasos. */
export const BOARD_COLUMNS = ['stack', 'priority', 'vault', 'failed'] as const;
export type BoardColumn = (typeof BOARD_COLUMNS)[number];

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
  /** A column, or 'none' for tasks that are not on the board. */
  board?: string;
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
  if (filter.board === 'none') where.push('board IS NULL');
  else if (filter.board) { where.push('board = ?'); params.push(filter.board); }
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

const TASK_EDITABLE = ['title', 'notes', 'status', 'project', 'area', 'priority', 'due_date', 'plan_date', 'plan_slot', 'position', 'source', 'source_id', 'following', 'waiting_for', 'expected_date', 'review_date', 'board'] as const;

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

// Board ---------------------------------------------------------------------------

/** Tasks done before Pausa had a board (the old Notion count) live in this setting. */
export const DONE_BASE_SETTING = 'tasks_done_base';

async function doneBase(db: D1Database): Promise<number> {
  const row = await db.prepare(`SELECT value FROM settings WHERE id = ? AND deleted = 0`).bind(DONE_BASE_SETTING).first<{ value: string }>();
  const value = Number(row?.value);
  return Number.isFinite(value) ? value : 0;
}

async function doneOnBoard(db: D1Database): Promise<number> {
  const row = await db.prepare(`SELECT COUNT(*) AS n FROM tasks WHERE deleted = 0 AND board IS NOT NULL AND status = 'done'`).first<{ n: number }>();
  return row?.n ?? 0;
}

/** Open tasks per column and the running total of tasks done. */
export async function boardSummary(db: D1Database) {
  const { results } = await db
    .prepare(`SELECT board, COUNT(*) AS n FROM tasks WHERE deleted = 0 AND status = 'todo' AND board IS NOT NULL GROUP BY board`)
    .all<{ board: BoardColumn; n: number }>();
  const [base, done] = await Promise.all([doneBase(db), doneOnBoard(db)]);
  return {
    columns: Object.fromEntries(BOARD_COLUMNS.map((column) => [column, results.find((row) => row.board === column)?.n ?? 0])),
    done_total: base + done,
  };
}

/** Sets the total of tasks done by moving the base, so board tasks completed elsewhere still add up. */
export async function setDoneTotal(db: D1Database, total: number) {
  if (typeof total !== 'number' || !Number.isInteger(total) || total < 0) throw new ValidationError('total must be an integer >= 0');
  const [base, done] = await Promise.all([doneBase(db), doneOnBoard(db)]);
  await save(db, 'settings', DONE_BASE_SETTING, { value: String(total - done) });
  const previous = base + done;
  return { previous, done_total: total };
}

export interface ImportItem {
  title: string;
  notes?: string | null;
  board?: BoardColumn | null;
  status?: 'todo' | 'done' | 'cancelled';
  source: string;
  source_id: string;
  created_at?: number;
}

export const MAX_IMPORT = 200;

/**
 * Creates tasks from another tool (e.g. Notion). Items whose source and source_id already exist
 * are skipped, so running the same import twice changes nothing.
 */
export async function importTasks(db: D1Database, items: ImportItem[]) {
  if (!Array.isArray(items) || items.length === 0) throw new ValidationError('items must be a non-empty array');
  if (items.length > MAX_IMPORT) throw new ValidationError(`At most ${MAX_IMPORT} items per call`);
  const existing = new Set<string>();
  for (const source of new Set(items.map((item) => item?.source))) {
    if (typeof source !== 'string') continue;
    const { results } = await db.prepare(`SELECT source_id FROM tasks WHERE source = ? AND deleted = 0`).bind(source).all<{ source_id: string }>();
    for (const row of results) existing.add(`${source}\u0000${row.source_id}`);
  }
  const now = Date.now();
  const changes = [];
  const skipped: string[] = [];
  for (const [index, item] of items.entries()) {
    if (!item || typeof item.source !== 'string' || !item.source || typeof item.source_id !== 'string' || !item.source_id) {
      throw new ValidationError(`items[${index}] needs source and source_id`);
    }
    const key = `${item.source}\u0000${item.source_id}`;
    if (existing.has(key)) { skipped.push(item.source_id); continue; }
    existing.add(key);
    const status = item.status ?? 'todo';
    changes.push({
      table: 'tasks' as const,
      id: crypto.randomUUID(),
      updated_at: now,
      deleted: false,
      data: {
        title: item.title,
        notes: item.notes ?? null,
        status,
        board: item.board ?? null,
        source: item.source,
        source_id: item.source_id,
        created_at: typeof item.created_at === 'number' ? item.created_at : now,
        completed_at: status === 'done' ? now : null,
      },
    });
  }
  const { applied } = await applyChanges(db, changes);
  return { created: applied.length, skipped: skipped.length, ids: applied };
}

// Habits --------------------------------------------------------------------------

export interface HabitWeek extends HabitScore {
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

/** Points a habit has earned, worked out from its checks the same way the phone does. */
export interface HabitScore {
  /** Total points, never below zero. */
  points: number;
  /** Weeks in a row with the minimum met, counting this week only once it is met. */
  streak: number;
  /** Days done in the week of the day asked for. */
  week_count: number;
  minimum_met: boolean;
  /** Points the next check would add this week: the base if the minimum is not met yet, else the bonus for an extra day. */
  next_reward: number;
  /** Points lost for missing the minimum the week before. */
  last_week_penalty: number;
  /** Points at the end of each of the last `weeks` weeks, oldest first, ending with the current week. */
  sparkline: number[];
}

/** First week to meet the minimum is worth this much; each week in a row after that is worth 15 % more. */
export const HABIT_FIRST_REWARD = 5;
export const HABIT_CHART_WEEKS = { min: 10, max: 104 } as const;

const ceilPercent = (value: number, percent: number) => Math.ceil((value * percent) / 100);
/** An extra day beyond the minimum adds 20 % of the week's base, at least one point. */
const habitBonus = (base: number) => Math.max(1, ceilPercent(base, 20));

/**
 * Scores a habit from the days it was done, oldest week first. A week that meets the minimum earns
 * its base (5 the first time, then 15 % more for each week in a row) plus a bonus per extra day;
 * a finished week that misses it loses 15 % of the points so far. Uses the current weekly goal for
 * every week, so it can differ by a few points from the phone after a goal change.
 */
export function scoreHabit(days: Iterable<string>, weeklyGoal: number, createdDay: string, today: string, weeks: number = HABIT_CHART_WEEKS.min): HabitScore {
  const perWeek = new Map<string, number>();
  for (const day of new Set(days)) perWeek.set(weekStart(day), (perWeek.get(weekStart(day)) ?? 0) + 1);
  const current = weekStart(today);
  const first = [weekStart(createdDay), ...perWeek.keys()].sort()[0];
  const chartWeeks = Math.max(HABIT_CHART_WEEKS.min, Math.min(HABIT_CHART_WEEKS.max, Math.trunc(weeks) || HABIT_CHART_WEEKS.min));
  const chartStart = addDays(current, -(chartWeeks - 1) * 7);
  const bases = new Map<string, number>();
  const penalties = new Map<string, number>();
  const totals = new Map<string, number>();
  let score = 0;
  for (let week = first < current ? first : current; week <= current; week = addDays(week, 7)) {
    const count = perWeek.get(week) ?? 0;
    if (count >= weeklyGoal) {
      const previous = bases.get(addDays(week, -7));
      const base = previous === undefined ? HABIT_FIRST_REWARD : ceilPercent(previous, 115);
      bases.set(week, base);
      score += base + (count - weeklyGoal) * habitBonus(base);
    } else if (week < current) {
      const penalty = ceilPercent(score, 15);
      penalties.set(week, penalty);
      score = Math.max(0, score - penalty);
    }
    totals.set(week, score);
  }
  let streak = 0;
  for (let week = bases.has(current) ? current : addDays(current, -7); bases.has(week); week = addDays(week, -7)) streak++;
  const base = bases.get(current);
  const previousBase = bases.get(addDays(current, -7));
  const sparkline: number[] = [];
  let carried = 0;
  for (const [week, total] of totals) if (week < chartStart) carried = total;
  for (let index = 0; index < chartWeeks; index++) {
    const week = addDays(chartStart, index * 7);
    carried = totals.get(week) ?? carried;
    sparkline.push(carried);
  }
  return {
    points: score,
    streak,
    week_count: perWeek.get(current) ?? 0,
    minimum_met: base !== undefined,
    next_reward: base !== undefined ? habitBonus(base) : previousBase !== undefined ? ceilPercent(previousBase, 115) : HABIT_FIRST_REWARD,
    last_week_penalty: penalties.get(addDays(current, -7)) ?? 0,
    sparkline,
  };
}

/** Active habits with the days done in the week of `today`, their points and a chart of the last `weeks` weeks. */
export async function listHabits(db: D1Database, today: string, weeks?: number): Promise<HabitWeek[]> {
  const start = weekStart(today);
  const { results: habits } = await db.prepare(`SELECT * FROM habits WHERE deleted = 0 AND archived = 0 ORDER BY position, name`).all<Row>();
  const { results: checks } = await db
    .prepare(`SELECT habit_id, day FROM habit_checks WHERE deleted = 0 AND day <= ?`)
    .bind(addDays(start, 6))
    .all<{ habit_id: string; day: string }>();
  return habits.map((row) => {
    const all = checks.filter((check) => check.habit_id === row.id).map((check) => check.day);
    const days = all.filter((day) => day >= start).sort();
    const record = toRecord('habits', row) as Omit<HabitWeek, 'id' | 'week_start' | 'week_days_done' | 'done_today' | keyof HabitScore>;
    return {
      id: String(row.id),
      ...record,
      week_start: start,
      week_days_done: days,
      done_today: days.includes(today),
      ...scoreHabit(all, record.weekly_goal, record.created_day, today, weeks),
    };
  });
}

export async function createHabit(db: D1Database, input: { name: string; weekly_goal?: number; id?: string }, today: string) {
  const id = input.id ?? crypto.randomUUID();
  await save(db, 'habits', id, { name: input.name, weekly_goal: input.weekly_goal ?? 1, created_day: today, archived: false });
  return id;
}

async function getHabitRow(db: D1Database, id: string): Promise<Row> {
  const habit = await getRow(db, 'habits', id);
  if (!habit || habit.deleted === 1) throw new NotFoundError(`No habit ${id}`);
  return habit;
}

const HABIT_EDITABLE = ['name', 'weekly_goal', 'archived', 'position'] as const;

/** Renames a habit, changes its weekly goal (1 to 7), archives it or moves it. */
export async function updateHabit(db: D1Database, id: string, patch: Record<string, unknown>) {
  const data: Record<string, unknown> = toRecord('habits', await getHabitRow(db, id));
  for (const key of HABIT_EDITABLE) if (patch[key] !== undefined) data[key] = patch[key];
  if (typeof data.name === 'string') data.name = data.name.trim();
  if (!data.name) throw new ValidationError('name is required');
  await save(db, 'habits', id, data);
  return { id, ...toRecord('habits', await getHabitRow(db, id)) };
}

/** Deletes a habit on every device. Its checks stay in the history but no longer count. */
export async function deleteHabit(db: D1Database, id: string) {
  await save(db, 'habits', id, toRecord('habits', await getHabitRow(db, id)), true);
}

export async function setHabitCheck(db: D1Database, habitId: string, day: string, done: boolean) {
  await getHabitRow(db, habitId);
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

/** Every entry of one day, in the order they were logged. Corrections show up as negative amounts. */
export async function counterEntries(db: D1Database, day: string, kind?: string) {
  if (kind !== undefined && !METRIC_KINDS.includes(kind as MetricKind)) throw new ValidationError(`kind must be one of ${METRIC_KINDS.join(', ')}`);
  const { results } = await db
    .prepare(`SELECT id, day, kind, amount, at FROM metric_entries WHERE deleted = 0 AND day = ?${kind ? ' AND kind = ?' : ''} ORDER BY at`)
    .bind(...(kind ? [day, kind] : [day]))
    .all<{ id: string; day: string; kind: MetricKind; amount: number; at: number }>();
  return results;
}

/** Changes any of the daily goals: calorie limit (kcal), protein goal (g) and cigarette limit. */
export async function setGoals(db: D1Database, input: Partial<Record<keyof typeof GOAL_DEFAULTS, unknown>>) {
  const changes = [];
  for (const key of Object.keys(GOAL_DEFAULTS) as (keyof typeof GOAL_DEFAULTS)[]) {
    const value = input[key];
    if (value === undefined) continue;
    if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > 100000) throw new ValidationError(`${key} must be an integer between 0 and 100000`);
    changes.push({ table: 'settings' as const, id: key, updated_at: Date.now(), deleted: false, data: { value: String(value) } });
  }
  if (!changes.length) throw new ValidationError(`Give at least one of ${Object.keys(GOAL_DEFAULTS).join(', ')}`);
  await applyChanges(db, changes);
  return goals(db);
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
  const [totals, goal, plan, habits, health] = await Promise.all([
    metricTotals(db, day, day),
    goals(db),
    listTasks(db, { plan_date: day }),
    listHabits(db, day),
    healthDays(db, day, day),
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
    health: health[0] ?? null,
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
  const [done, open, totals, goal, habits, checks, health] = await Promise.all([
    db.prepare(`SELECT * FROM tasks WHERE deleted = 0 AND status = 'done' AND completed_at >= ? AND completed_at < ? ORDER BY completed_at`).bind(from, to).all<Row>(),
    db.prepare(`SELECT * FROM tasks WHERE deleted = 0 AND status = 'todo' AND ((due_date IS NOT NULL AND due_date <= ?) OR (plan_date IS NOT NULL AND plan_date <= ?)) ORDER BY due_date, plan_date`).bind(end, end).all<Row>(),
    metricTotals(db, start, end),
    goals(db),
    db.prepare(`SELECT * FROM habits WHERE deleted = 0 AND archived = 0 ORDER BY position, name`).all<Row>(),
    db.prepare(`SELECT habit_id, day FROM habit_checks WHERE deleted = 0 AND day BETWEEN ? AND ?`).bind(start, end).all<{ habit_id: string; day: string }>(),
    healthDays(db, start, end),
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
    health: healthAverages(health),
  };
}

// Health --------------------------------------------------------------------------

/** What the phone read from Health Connect for one day. Any value can be null when the watch had no data. */
export interface HealthDay {
  day: string;
  sleep_minutes: number | null;
  sleep_start: number | null;
  sleep_end: number | null;
  steps: number | null;
  exercise_minutes: number | null;
  resting_hr: number | null;
  source: string | null;
  updated_at: number;
}

/** Health days between `from` and `to` (inclusive), oldest first. Days without a row are left out. */
export async function healthDays(db: D1Database, from: string, to: string): Promise<HealthDay[]> {
  const { results } = await db
    .prepare(`SELECT * FROM health_days WHERE deleted = 0 AND day BETWEEN ? AND ? ORDER BY day`)
    .bind(from, to)
    .all<Row>();
  return results.map((row) => ({ ...(toRecord('health_days', row) as Omit<HealthDay, 'updated_at'>), updated_at: Number(row.updated_at) }));
}

export const MAX_HEALTH_DAYS = 366;

/**
 * Health per day between `from` and `to` with their averages. Both default to the last 7 days
 * ending today; the range can be at most a year.
 */
export async function healthSummary(db: D1Database, today: string, from?: unknown, to?: unknown) {
  const DAY = /^\d{4}-\d{2}-\d{2}$/;
  for (const value of [from, to]) {
    if (value !== undefined && (typeof value !== 'string' || !DAY.test(value))) throw new ValidationError('from and to must be YYYY-MM-DD');
  }
  const end = (to as string | undefined) ?? today;
  const start = (from as string | undefined) ?? addDays(end, -6);
  const length = Math.round((Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / 86_400_000) + 1;
  if (length < 1) throw new ValidationError('from must be on or before to');
  if (length > MAX_HEALTH_DAYS) throw new ValidationError(`At most ${MAX_HEALTH_DAYS} days at a time`);
  return { from: start, to: end, ...healthAverages(await healthDays(db, start, end)) };
}

/** Averages over the days that have each value, so a night without the watch does not pull them down. */
export function healthAverages(days: HealthDay[]) {
  const average = (key: 'sleep_minutes' | 'steps' | 'resting_hr') => {
    const values = days.map((row) => row[key]).filter((value): value is number => typeof value === 'number');
    return values.length ? Math.round(values.reduce((sum, value) => sum + value, 0) / values.length) : null;
  };
  return {
    days_with_data: days.length,
    average: { sleep_minutes: average('sleep_minutes'), steps: average('steps'), resting_hr: average('resting_hr') },
    exercise_minutes_total: days.reduce((sum, row) => sum + (row.exercise_minutes ?? 0), 0),
    per_day: days.map(({ updated_at: _, ...row }) => row),
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
  {
    key: 'task_kanban',
    title: 'Tablero Kanban en Tareas',
    description: 'Un segmento Kanban en Tareas con stack infinito, prioridad, baúl y fracasos, y el contador de tareas hechas.',
    default: false,
  },
  {
    key: 'health_connect',
    title: 'Salud del reloj',
    description: 'Sueño, pasos, ejercicio y pulso en reposo del Pixel Watch, leídos de Health Connect y mostrados en Hoy.',
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
