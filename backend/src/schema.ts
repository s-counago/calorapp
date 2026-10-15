// The synced tables and the fields a client may write. Everything that crosses the
// wire (sync, REST, MCP) is validated against this registry before it reaches SQL.

export type FieldType = 'text' | 'int' | 'real' | 'bool' | 'day';

export interface FieldSpec {
  type: FieldType;
  required?: boolean;
  values?: readonly string[];
  max?: number;
  /** Used when a client leaves the field out, e.g. an older app version. */
  default?: string | number | boolean;
}

export interface TableSpec {
  fields: Record<string, FieldSpec>;
}

export const TABLES = {
  tasks: {
    fields: {
      title: { type: 'text', required: true, max: 500 },
      notes: { type: 'text', max: 20000 },
      status: { type: 'text', required: true, values: ['todo', 'done', 'cancelled'] },
      project: { type: 'text', max: 200 },
      area: { type: 'text', max: 200 },
      priority: { type: 'int' },
      due_date: { type: 'day' },
      plan_date: { type: 'day' },
      plan_slot: { type: 'text', values: ['morning', 'afternoon', 'evening'] },
      position: { type: 'real' },
      completed_at: { type: 'int' },
      source: { type: 'text', max: 50 },
      source_id: { type: 'text', max: 200 },
      created_at: { type: 'int', required: true },
      following: { type: 'bool', default: false },
      waiting_for: { type: 'text', max: 500 },
      expected_date: { type: 'day' },
      review_date: { type: 'day' },
      board: { type: 'text', values: ['stack', 'priority', 'vault', 'failed'] },
      /** Minutes after local midnight on plan_date (0–1439), for a task with a set time. */
      plan_time: { type: 'int' },
      /** How long the task takes, in minutes (1–1440). */
      plan_minutes: { type: 'int' },
      /** The habit this task was made from; completing the task checks the habit on plan_date. */
      habit_id: { type: 'text', max: 100 },
    },
  },
  habits: {
    fields: {
      name: { type: 'text', required: true, max: 200 },
      weekly_goal: { type: 'int', required: true },
      created_day: { type: 'day', required: true },
      archived: { type: 'bool', default: false },
      position: { type: 'real' },
    },
  },
  habit_checks: {
    fields: {
      habit_id: { type: 'text', required: true, max: 100 },
      day: { type: 'day', required: true },
    },
  },
  metric_entries: {
    fields: {
      day: { type: 'day', required: true },
      kind: { type: 'text', required: true, values: ['calories', 'protein', 'cigarettes'] },
      amount: { type: 'real', required: true },
      at: { type: 'int', required: true },
    },
  },
  settings: {
    fields: {
      value: { type: 'text', required: true, max: 5000 },
    },
  },
  /** One row per day, id = the day. Written by the phone from Health Connect. */
  health_days: {
    fields: {
      day: { type: 'day', required: true },
      sleep_minutes: { type: 'int' },
      sleep_start: { type: 'int' },
      sleep_end: { type: 'int' },
      steps: { type: 'int' },
      exercise_minutes: { type: 'int' },
      resting_hr: { type: 'int' },
      source: { type: 'text', max: 100 },
    },
  },
  /** Money summary of one payday-to-payday cycle, id = its first day. Written by the phone; no movements. */
  budget_cycles: {
    fields: {
      start: { type: 'day', required: true },
      end: { type: 'day', required: true },
      current: { type: 'bool', default: false },
      income: { type: 'int' },
      fixed: { type: 'int' },
      saved: { type: 'int' },
      savings_target: { type: 'int' },
      free_budget: { type: 'int' },
      free_spent: { type: 'int' },
      available: { type: 'int' },
      daily_allowance: { type: 'int' },
      projection: { type: 'int' },
      savings_at_close: { type: 'int' },
      unexpected: { type: 'int' },
      covered: { type: 'int' },
      cushion_left: { type: 'int' },
      lines: { type: 'text', max: 50000 },
      categories: { type: 'text', max: 20000 },
      computed_at: { type: 'int' },
    },
  },
} as const satisfies Record<string, TableSpec>;

export type TableName = keyof typeof TABLES;
export const TABLE_NAMES = Object.keys(TABLES) as TableName[];

export function isTableName(value: unknown): value is TableName {
  return typeof value === 'string' && Object.hasOwn(TABLES, value);
}

export type Row = Record<string, string | number | null>;

export class ValidationError extends Error {}

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Checks one field value and converts it to what SQLite stores. */
function coerce(table: string, name: string, spec: FieldSpec, value: unknown): string | number | null {
  if ((value === null || value === undefined) && spec.default !== undefined) value = spec.default;
  if (value === null || value === undefined) {
    if (spec.required) throw new ValidationError(`${table}.${name} is required`);
    return null;
  }
  switch (spec.type) {
    case 'text':
      if (typeof value !== 'string') throw new ValidationError(`${table}.${name} must be text`);
      if (spec.max !== undefined && value.length > spec.max) throw new ValidationError(`${table}.${name} is too long`);
      if (spec.values && !spec.values.includes(value)) {
        throw new ValidationError(`${table}.${name} must be one of ${spec.values.join(', ')}`);
      }
      return value;
    case 'day':
      if (typeof value !== 'string' || !DAY.test(value)) throw new ValidationError(`${table}.${name} must be YYYY-MM-DD`);
      return value;
    case 'int':
      if (typeof value !== 'number' || !Number.isInteger(value)) throw new ValidationError(`${table}.${name} must be an integer`);
      return value;
    case 'real':
      if (typeof value !== 'number' || !Number.isFinite(value)) throw new ValidationError(`${table}.${name} must be a number`);
      return value;
    case 'bool':
      if (typeof value !== 'boolean') throw new ValidationError(`${table}.${name} must be true or false`);
      return value ? 1 : 0;
  }
}

/** Validates a full record for a table, dropping unknown keys. */
export function validateRecord(table: TableName, data: Record<string, unknown>): Row {
  const spec: TableSpec = TABLES[table];
  const row: Row = {};
  for (const [name, field] of Object.entries(spec.fields)) {
    row[name] = coerce(table, name, field, data[name]);
  }
  if (table === 'habits' && (Number(row.weekly_goal) < 1 || Number(row.weekly_goal) > 7)) {
    throw new ValidationError('habits.weekly_goal must be between 1 and 7');
  }
  if (table === 'tasks' && row.plan_time !== null && (Number(row.plan_time) < 0 || Number(row.plan_time) > 1439)) {
    throw new ValidationError('tasks.plan_time must be minutes between 0 and 1439');
  }
  if (table === 'tasks' && row.plan_minutes !== null && (Number(row.plan_minutes) < 1 || Number(row.plan_minutes) > 1440)) {
    throw new ValidationError('tasks.plan_minutes must be between 1 and 1440');
  }
  return row;
}

/** Turns a stored row back into the JSON shape clients see. */
export function toRecord(table: TableName, row: Row): Record<string, unknown> {
  const spec: TableSpec = TABLES[table];
  const data: Record<string, unknown> = {};
  for (const [name, field] of Object.entries(spec.fields)) {
    const value = row[name];
    data[name] = field.type === 'bool' ? value === 1 : (value ?? null);
  }
  return data;
}
