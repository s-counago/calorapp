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
/**
 * What a task is to Sejio, exactly one per task: bandeja (inbox), prioridad, stack, esperando
 * (waiting), baúl (vault) or fracasos (failed). Read from `board` and `following`, so older phones
 * that only know those two fields keep agreeing with the backend.
 */
export const LANES = ['inbox', 'priority', 'stack', 'waiting', 'vault', 'failed'] as const;
export type Lane = (typeof LANES)[number];

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

export function laneOf(task: { board?: unknown; following?: unknown }): Lane {
  if (task.following === true || task.following === 1) return 'waiting';
  return typeof task.board === 'string' && (BOARD_COLUMNS as readonly string[]).includes(task.board) ? (task.board as Lane) : 'inbox';
}

/** The `board` and `following` that put a task in `lane`. */
function laneFields(lane: unknown): { board: BoardColumn | null; following: boolean } {
  if (typeof lane !== 'string' || !(LANES as readonly string[]).includes(lane)) throw new ValidationError(`lane must be one of ${LANES.join(', ')}`);
  return { board: (BOARD_COLUMNS as readonly string[]).includes(lane) ? (lane as BoardColumn) : null, following: lane === 'waiting' };
}

function taskFromRow(row: Row): Task {
  const record = toRecord('tasks', row);
  return { id: String(row.id), ...record, lane: laneOf(record), updated_at: Number(row.updated_at) } as unknown as Task;
}

export interface TaskFilter {
  status?: string;
  plan_date?: string;
  /** Planned on or after this day. */
  plan_from?: string;
  /** Planned on or before this day. */
  plan_to?: string;
  project?: string;
  due_before?: string;
  following?: boolean;
  /** A column, or 'none' for tasks that are not on the board. */
  board?: string;
  lane?: string;
  search?: string;
  limit?: number;
}

export async function listTasks(db: D1Database, filter: TaskFilter = {}): Promise<Task[]> {
  const where = ['deleted = 0'];
  const params: (string | number)[] = [];
  if (filter.status) { where.push('status = ?'); params.push(filter.status); }
  if (filter.plan_date) { where.push('plan_date = ?'); params.push(filter.plan_date); }
  if (filter.plan_from) { where.push('plan_date >= ?'); params.push(filter.plan_from); }
  if (filter.plan_to) { where.push('plan_date <= ?'); params.push(filter.plan_to); }
  if (filter.project) { where.push('project = ?'); params.push(filter.project); }
  if (filter.following !== undefined) { where.push('following = ?'); params.push(filter.following ? 1 : 0); }
  if (filter.board === 'none') where.push('board IS NULL');
  else if (filter.board) { where.push('board = ?'); params.push(filter.board); }
  if (filter.lane !== undefined) {
    const { board, following } = laneFields(filter.lane);
    if (following) where.push('following = 1');
    else {
      where.push('following = 0');
      if (board) { where.push('board = ?'); params.push(board); } else where.push('board IS NULL');
    }
  }
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

const TASK_EDITABLE = ['title', 'notes', 'status', 'project', 'area', 'priority', 'due_date', 'plan_date', 'plan_slot', 'position', 'source', 'source_id', 'following', 'waiting_for', 'expected_date', 'review_date', 'board', 'plan_time', 'plan_minutes', 'habit_id'] as const;

/** Copies the editable fields of `input` into `data`; `lane`, when given, sets board and following. */
function applyTaskInput(data: Record<string, unknown>, input: Record<string, unknown>) {
  for (const key of TASK_EDITABLE) if (input[key] !== undefined) data[key] = input[key];
  if (input.lane !== undefined) Object.assign(data, laneFields(input.lane));
  if (typeof data.plan_time === 'string') data.plan_time = parseTime(data.plan_time);
}

/** "HH:MM" to minutes after midnight. */
export function parseTime(value: string): number {
  const match = /^(\d{1,2}):(\d{2})$/.exec(value);
  const minutes = match ? Number(match[1]) * 60 + Number(match[2]) : NaN;
  if (!match || Number(match[1]) > 23 || Number(match[2]) > 59) throw new ValidationError('time must be HH:MM, e.g. 09:30');
  return minutes;
}

/** The part of the day an hour falls in, as on the phone: before 12:00, before 20:00, or later. */
export function slotForTime(minutes: number): (typeof SLOTS)[number] {
  return minutes < 12 * 60 ? 'morning' : minutes < 20 * 60 ? 'afternoon' : 'evening';
}

export async function createTask(db: D1Database, input: Record<string, unknown>): Promise<Task> {
  const id = typeof input.id === 'string' ? input.id : crypto.randomUUID();
  const now = Date.now();
  const data: Record<string, unknown> = { status: 'todo', created_at: now };
  applyTaskInput(data, input);
  if (data.status === 'done') data.completed_at = now;
  await save(db, 'tasks', id, data);
  if (data.status === 'done') await syncHabitWithTask(db, data, true);
  return getTask(db, id);
}

export async function updateTask(db: D1Database, id: string, patch: Record<string, unknown>): Promise<Task> {
  const current = await getTask(db, id);
  const data: Record<string, unknown> = { ...current };
  applyTaskInput(data, patch);
  if (data.status === 'done' && current.status !== 'done') data.completed_at = Date.now();
  if (data.status !== 'done') data.completed_at = null;
  await save(db, 'tasks', id, data);
  if ((data.status === 'done') !== (current.status === 'done')) await syncHabitWithTask(db, data, data.status === 'done');
  return getTask(db, id);
}

/** Moves a task to another lane. Its appointment, if any, stays. */
export async function moveTask(db: D1Database, id: string, lane: unknown): Promise<Task> {
  return updateTask(db, id, laneFields(lane));
}

export interface Appointment {
  /** YYYY-MM-DD, or null to take the task off every day. */
  day: string | null;
  slot?: string | null;
  /** HH:MM, or null for no set time. */
  time?: string | null;
  minutes?: number | null;
}

/**
 * Plans a task on a day. With a time and no slot, the slot follows the time. A day without slot
 * or time means "any time that day". day null clears the whole appointment.
 */
export async function planTask(db: D1Database, id: string, appointment: Appointment): Promise<Task> {
  if (!appointment || typeof appointment !== 'object') throw new ValidationError('Expected { day, slot?, time?, minutes? }');
  const { day, slot = null, time = null, minutes = null } = appointment;
  if (day === null) return updateTask(db, id, { plan_date: null, plan_slot: null, plan_time: null, plan_minutes: null, position: null });
  if (typeof day !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new ValidationError('day must be YYYY-MM-DD or null');
  if (time !== null && typeof time !== 'string') throw new ValidationError('time must be HH:MM or null');
  const planTime = time === null ? null : parseTime(time);
  return updateTask(db, id, {
    plan_date: day,
    plan_slot: slot ?? (planTime === null ? null : slotForTime(planTime)),
    plan_time: planTime,
    plan_minutes: minutes,
  });
}

/** A linked habit is checked on the task's day when the task is done, and unchecked when it is reopened. */
async function syncHabitWithTask(db: D1Database, task: Record<string, unknown>, done: boolean) {
  if (typeof task.habit_id !== 'string' || typeof task.plan_date !== 'string') return;
  const habit = await getRow(db, 'habits', task.habit_id);
  if (!habit || habit.deleted === 1) return;
  await save(db, 'habit_checks', `${task.habit_id}:${task.plan_date}`, { habit_id: task.habit_id, day: task.plan_date }, !done);
}

/**
 * Turns a habit into a task for one day ("any time that day", lane prioridad) in one step. Asking
 * twice for the same habit and day returns the task already there instead of making another.
 */
export async function habitToTask(db: D1Database, habitId: string, day: string): Promise<Task> {
  const habit = await getRow(db, 'habits', habitId);
  if (!habit || habit.deleted === 1) throw new NotFoundError(`No habit ${habitId}`);
  const existing = await db
    .prepare(`SELECT * FROM tasks WHERE deleted = 0 AND habit_id = ? AND plan_date = ? AND status != 'cancelled' LIMIT 1`)
    .bind(habitId, day)
    .first<Row>();
  if (existing) return taskFromRow(existing);
  return createTask(db, { title: String(habit.name), lane: 'priority', plan_date: day, habit_id: habitId });
}

/**
 * Everything about tasks at a glance: open tasks per lane, today's plan, what was planned on an
 * earlier day and is still open (left over), and the running total of tasks done.
 */
export async function tasksOverview(db: D1Database, today: string) {
  const [lanes, planned, leftOver, board] = await Promise.all([
    db.prepare(`SELECT board, following, COUNT(*) AS n FROM tasks WHERE deleted = 0 AND status = 'todo' GROUP BY board, following`).all<{ board: string | null; following: number; n: number }>(),
    listTasks(db, { plan_date: today }),
    db.prepare(`SELECT * FROM tasks WHERE deleted = 0 AND status = 'todo' AND plan_date IS NOT NULL AND plan_date < ? ORDER BY plan_date, plan_slot, position LIMIT 200`).bind(today).all<Row>(),
    boardSummary(db),
  ]);
  const counts = Object.fromEntries(LANES.map((lane) => [lane, 0])) as Record<Lane, number>;
  for (const row of lanes.results) counts[laneOf(row)] += row.n;
  return {
    day: today,
    lanes: counts,
    today: Object.fromEntries([null, ...SLOTS].map((slot) => [slot ?? 'any_time', planned.filter((task) => (task.plan_slot ?? null) === slot)])),
    left_over: leftOver.results.map(taskFromRow),
    done_total: board.done_total,
  };
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
  // A task made from this habit for that day follows the check, so the same thing is never marked twice.
  const linked = await db
    .prepare(`SELECT * FROM tasks WHERE deleted = 0 AND habit_id = ? AND plan_date = ? AND status = ?`)
    .bind(habitId, day, done ? 'todo' : 'done')
    .all<Row>();
  for (const row of linked.results) {
    const task = taskFromRow(row);
    await save(db, 'tasks', task.id, { ...task, status: done ? 'done' : 'todo', completed_at: done ? Date.now() : null });
  }
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
  const [totals, goal, plan, habits, health, note] = await Promise.all([
    metricTotals(db, day, day),
    goals(db),
    listTasks(db, { plan_date: day }),
    listHabits(db, day),
    healthDays(db, day, day),
    getFocusNote(db, day),
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
    note: note?.active ? { text: note.text, until: note.until } : null,
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

// Money -----------------------------------------------------------------------------

const BUDGET_STATUSES = ['late', 'soon', 'partial', 'upcoming', 'reserved', 'paid', 'skipped', 'missed'] as const;

function parseList(value: unknown): unknown[] {
  if (typeof value !== 'string') return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function budgetFromRow(row: Row) {
  const record = toRecord('budget_cycles', row) as Record<string, unknown>;
  const lines = parseList(record.lines).map((line) => {
    const item = (line ?? {}) as Record<string, unknown>;
    return {
      id: String(item.id ?? ''),
      name: String(item.name ?? ''),
      cap: item.cap === true,
      expected: Number(item.expected ?? 0),
      paid: Number(item.paid ?? 0),
      logged: Number(item.logged ?? 0),
      status: (BUDGET_STATUSES as readonly string[]).includes(String(item.status)) ? String(item.status) : 'upcoming',
      due: typeof item.due === 'string' ? item.due : null,
      frequency: Number(item.frequency ?? 1),
    };
  });
  const categories = parseList(record.categories).map((category) => {
    const item = (category ?? {}) as Record<string, unknown>;
    return { key: String(item.key ?? ''), label: String(item.label ?? item.key ?? ''), cents: Number(item.cents ?? 0) };
  });
  return { ...record, lines, caps: lines.filter((line) => line.cap), categories, updated_at: Number(row.updated_at) } as unknown as Record<string, unknown> & { start: string; end: string };
}

/**
 * The money summary the phone uploads per payday-to-payday cycle: the cycle containing `day` (default
 * the latest one) and the list of cycles available. Amounts are cents. Nothing here comes from the
 * bank directly; movements stay on the phone.
 */
export async function budgetSummary(db: D1Database, day?: string) {
  const { results } = await db.prepare(`SELECT * FROM budget_cycles WHERE deleted = 0 ORDER BY start DESC LIMIT 36`).all<Row>();
  const cycles = results.map(budgetFromRow);
  const cycle = day ? cycles.find((item) => item.start <= day && day <= item.end) ?? null : cycles[0] ?? null;
  return { cycle, cycles: cycles.map((item: Record<string, unknown>) => ({ start: item.start, end: item.end, current: item.current, available: item.available, saved: item.saved, savings_at_close: item.savings_at_close })) };
}

// Captures (el cajón) -------------------------------------------------------------

export const CAPTURE_STATUSES = ['new', 'kept', 'filed', 'archived'] as const;
export const CAPTURE_KINDS = ['note', 'idea', 'link', 'task', 'other'] as const;
const URL_IN_TEXT = /https?:\/\/[^\s<>"]+/i;

export interface Capture extends Record<string, unknown> {
  id: string;
  text: string;
  status: string;
}

function captureFromRow(row: Row): Capture {
  const record = toRecord('captures', row);
  let suggestion: unknown = null;
  try { suggestion = typeof record.suggestion === 'string' ? JSON.parse(record.suggestion) : null; } catch { suggestion = null; }
  return { id: String(row.id), ...record, suggestion, updated_at: Number(row.updated_at) } as unknown as Capture;
}

export async function getCapture(db: D1Database, id: string): Promise<Capture> {
  const row = await getRow(db, 'captures', id);
  if (!row || row.deleted === 1) throw new NotFoundError(`No capture ${id}`);
  return captureFromRow(row);
}

/** Drops something into the cajón. A link inside the text becomes `url` when none is given. */
export async function createCapture(db: D1Database, input: { text?: unknown; url?: unknown; title?: unknown; source?: unknown; id?: unknown }) {
  const text = typeof input.text === 'string' ? input.text.trim() : '';
  const url = typeof input.url === 'string' && input.url ? input.url : (URL_IN_TEXT.exec(text)?.[0] ?? null);
  if (!text && !url) throw new ValidationError('text or url is required');
  const id = typeof input.id === 'string' ? input.id : crypto.randomUUID();
  await save(db, 'captures', id, {
    text: text || url,
    url,
    title: typeof input.title === 'string' ? input.title : null,
    source: typeof input.source === 'string' ? input.source : null,
    status: 'new',
    kind: url && (!text || text === url) ? 'link' : null,
    created_at: Date.now(),
  });
  return getCapture(db, id);
}

export interface CaptureFilter {
  status?: string;
  kind?: string;
  tag?: string;
  search?: string;
  /** Only captures without an agent suggestion yet. */
  unsuggested?: boolean;
  limit?: number;
}

/** Captures, newest first. Defaults to the ones still waiting (status new). */
export async function listCaptures(db: D1Database, filter: CaptureFilter = {}) {
  const where = ['deleted = 0'];
  const params: (string | number)[] = [];
  const status = filter.status ?? 'new';
  if (status !== 'all') {
    if (!(CAPTURE_STATUSES as readonly string[]).includes(status)) throw new ValidationError(`status must be all or one of ${CAPTURE_STATUSES.join(', ')}`);
    where.push('status = ?'); params.push(status);
  }
  if (filter.kind) { where.push('kind = ?'); params.push(filter.kind); }
  if (filter.tag) { where.push("(',' || tags || ',') LIKE ?"); params.push(`%,${filter.tag.replace(/[%_,]/g, '')},%`); }
  if (filter.unsuggested) where.push('suggestion IS NULL');
  if (filter.search) {
    where.push("(text LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\')");
    const like = `%${filter.search.replace(/[\\%_]/g, '\\$&')}%`;
    params.push(like, like, like);
  }
  const limit = Math.min(Math.max(filter.limit ?? 100, 1), 500);
  const { results } = await db.prepare(`SELECT * FROM captures WHERE ${where.join(' AND ')} ORDER BY created_at DESC LIMIT ${limit}`).bind(...params).all<Row>();
  const waiting = await db.prepare(`SELECT COUNT(*) AS n FROM captures WHERE deleted = 0 AND status = 'new'`).first<{ n: number }>();
  return { waiting: waiting?.n ?? 0, items: results.map(captureFromRow) };
}

function cleanTags(tags: unknown): string | null {
  if (tags === undefined || tags === null) return null;
  const list = (Array.isArray(tags) ? tags : typeof tags === 'string' ? tags.split(',') : null);
  if (!list) throw new ValidationError('tags must be a list of words');
  const clean = [...new Set(list.map((tag) => String(tag).trim().toLowerCase().replace(/,/g, '')).filter(Boolean))];
  return clean.length ? clean.join(',') : null;
}

/**
 * What an agent thinks a capture is. It is only stored next to the capture; nothing moves until
 * Sejio (or an agent he asked) files it. `lane` and `day` describe the task it would become.
 */
export async function suggestCapture(db: D1Database, id: string, suggestion: Record<string, unknown>) {
  const capture = await getCapture(db, id);
  if (!suggestion || typeof suggestion !== 'object') throw new ValidationError('suggestion must be an object');
  const kind = suggestion.kind;
  if (!(CAPTURE_KINDS as readonly string[]).includes(String(kind))) throw new ValidationError(`kind must be one of ${CAPTURE_KINDS.join(', ')}`);
  if (suggestion.lane !== undefined && suggestion.lane !== null && !(LANES as readonly string[]).includes(String(suggestion.lane))) {
    throw new ValidationError(`lane must be one of ${LANES.join(', ')}`);
  }
  const clean = {
    kind,
    title: typeof suggestion.title === 'string' ? suggestion.title.slice(0, 500) : null,
    lane: suggestion.lane ?? null,
    day: typeof suggestion.day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(suggestion.day) ? suggestion.day : null,
    tags: cleanTags(suggestion.tags)?.split(',') ?? [],
    reason: typeof suggestion.reason === 'string' ? suggestion.reason.slice(0, 500) : null,
  };
  await save(db, 'captures', id, { ...capture, suggestion: JSON.stringify(clean) });
  return getCapture(db, id);
}

export interface FileCapture {
  /** task: becomes a task; keep: stays in the library as a note, idea or link; archive: done with it. */
  as: 'task' | 'keep' | 'archive';
  title?: string;
  lane?: string;
  day?: string | null;
  kind?: string;
  tags?: unknown;
}

/** Decides what a capture is. Filing it as a task creates the task (its notes keep the text and link). */
export async function fileCapture(db: D1Database, id: string, decision: FileCapture) {
  const capture = await getCapture(db, id);
  if (!decision || typeof decision !== 'object') throw new ValidationError('Expected { as: task | keep | archive }');
  if (decision.as === 'task') {
    const title = (typeof decision.title === 'string' && decision.title.trim()) || String(capture.title ?? capture.text).split('\n')[0].slice(0, 500);
    const notes = [capture.text !== title ? capture.text : null, capture.url && !String(capture.text).includes(String(capture.url)) ? capture.url : null].filter(Boolean).join('\n\n') || null;
    const task = await createTask(db, {
      title,
      notes,
      lane: decision.lane ?? 'inbox',
      plan_date: decision.day ?? undefined,
      source: 'capture',
      source_id: id,
    });
    await save(db, 'captures', id, { ...capture, suggestion: capture.suggestion ? JSON.stringify(capture.suggestion) : null, status: 'filed', kind: 'task', task_id: task.id, tags: cleanTags(decision.tags) ?? capture.tags ?? null });
    return { capture: await getCapture(db, id), task };
  }
  if (decision.as === 'keep' || decision.as === 'archive') {
    const kind = decision.kind ?? capture.kind ?? null;
    if (kind !== null && !(CAPTURE_KINDS as readonly string[]).includes(String(kind))) throw new ValidationError(`kind must be one of ${CAPTURE_KINDS.join(', ')}`);
    await save(db, 'captures', id, {
      ...capture,
      suggestion: capture.suggestion ? JSON.stringify(capture.suggestion) : null,
      status: decision.as === 'keep' ? 'kept' : 'archived',
      kind,
      title: typeof decision.title === 'string' ? decision.title : capture.title ?? null,
      tags: decision.tags === undefined ? capture.tags ?? null : cleanTags(decision.tags),
    });
    return { capture: await getCapture(db, id), task: null };
  }
  throw new ValidationError('as must be task, keep or archive');
}

export async function deleteCapture(db: D1Database, id: string) {
  const capture = await getCapture(db, id);
  await save(db, 'captures', id, { ...capture, suggestion: capture.suggestion ? JSON.stringify(capture.suggestion) : null }, true);
}

// Focus note ----------------------------------------------------------------------

/**
 * One short note that Sejio wants in sight for the next few days ("esta semana, solo lo de
 * Prioridad"). Stored as the synced setting `focus_note` holding JSON { text, until }, so older
 * phones simply ignore it. Past `until` (inclusive) it stops showing but is kept.
 */
export const FOCUS_NOTE_SETTING = 'focus_note';
export const MAX_NOTE = 280;

export interface FocusNote {
  text: string;
  /** Last day it shows (YYYY-MM-DD), or null to keep it until it is changed. */
  until: string | null;
  updated_at: number;
}

export async function getFocusNote(db: D1Database, today: string): Promise<(FocusNote & { active: boolean }) | null> {
  const row = await db.prepare(`SELECT value, updated_at FROM settings WHERE id = ? AND deleted = 0`).bind(FOCUS_NOTE_SETTING).first<{ value: string; updated_at: number }>();
  if (!row) return null;
  let parsed: { text?: unknown; until?: unknown };
  try { parsed = JSON.parse(row.value); } catch { return null; }
  if (typeof parsed.text !== 'string' || !parsed.text) return null;
  const until = typeof parsed.until === 'string' ? parsed.until : null;
  return { text: parsed.text, until, updated_at: Number(row.updated_at), active: until === null || until >= today };
}

/** Sets the note, or clears it with an empty or null text. */
export async function setFocusNote(db: D1Database, text: unknown, until: unknown, today: string) {
  if (text === null || text === undefined || (typeof text === 'string' && !text.trim())) {
    await save(db, 'settings', FOCUS_NOTE_SETTING, { value: '{}' }, true);
    return null;
  }
  if (typeof text !== 'string') throw new ValidationError('text must be text');
  if (text.trim().length > MAX_NOTE) throw new ValidationError(`text must be at most ${MAX_NOTE} characters`);
  if (until !== undefined && until !== null && (typeof until !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(until))) {
    throw new ValidationError('until must be YYYY-MM-DD or null');
  }
  await save(db, 'settings', FOCUS_NOTE_SETTING, { value: JSON.stringify({ text: text.trim(), until: until ?? null }) });
  return getFocusNote(db, today);
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
    key: 'pausa_v7',
    title: 'Pausa 7: Planificar',
    description: 'Cuatro pestañas (Hoy, Planificar, Hábitos, Dinero). Planificar junta los carriles (Bandeja, Prioridad, Stack, Esperando, Baúl, Fracasos) con la semana, Hoy enseña lo que quedó atrás y el botón + apunta con una sola línea. Viajes pasa a Ajustes.',
    default: false,
  },
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
    key: 'habit_tasks',
    title: 'Hábito → tarea',
    description: 'Un botón en cada hábito para ponerlo como tarea de hoy o de mañana, con un evento de día completo en Google Calendar.',
    default: false,
  },
  {
    key: 'captures',
    title: 'Cajón',
    description: 'Un sitio donde volcar enlaces, ideas y notas en dos toques (también desde Compartir), para ordenarlos luego.',
    default: false,
  },
  {
    key: 'focus_note',
    title: 'Nota en Hoy',
    description: 'Una frase o un plan para los próximos días, en una línea discreta bajo el saludo de Hoy.',
    default: false,
  },
  {
    key: 'budget_cloud',
    title: 'Dinero en la web',
    description: 'El móvil sube el resumen de cada ciclo (presupuesto, topes y gasto por categoría) para verlo en la web. Los movimientos y las credenciales no salen del teléfono.',
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
