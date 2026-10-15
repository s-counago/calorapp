// A stateless MCP server over Streamable HTTP: every POST carries one JSON-RPC message
// and gets one JSON response. Tools call the same domain functions as the REST API.

import { TABLE_NAMES, ValidationError, isTableName } from './schema';
import { queryClientLogs, syncStatus } from './diagnostics';
import { errorFields, log } from './log';
import { recordHistory } from './sync';
import type { Env } from './auth';
import {
  BOARD_COLUMNS,
  MAX_IMPORT,
  METRIC_KINDS,
  NotFoundError,
  SLOTS,
  boardSummary,
  createHabit,
  createTask,
  daySummary,
  deleteTask,
  getTask,
  importTasks,
  listHabits,
  listTasks,
  currentDay,
  logMetric,
  setDoneTotal,
  setMetricTotal,
  setHabitCheck,
  updateTask,
  LANES,
  moveTask,
  planTask,
  habitToTask,
  tasksOverview,
  addDays,
  type ImportItem,
  FEATURES,
  REVIEW_PERIODS,
  listFeatures,
  review,
  healthSummary,
  setFeature,
  type ReviewPeriod,
  type MetricKind,
} from './domain';

const PROTOCOL_VERSIONS = ['2026-07-28', '2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];
const SERVER_INFO = { name: 'pausa', title: 'Pausa', version: '1.0.0' };

const day = { type: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$', description: 'Date as YYYY-MM-DD. Defaults to today in Europe/Madrid; the day closes at 03:00, so 01:30 still counts as the previous day.' };
const taskFields = {
  title: { type: 'string' },
  notes: { type: 'string' },
  status: { type: 'string', enum: ['todo', 'done', 'cancelled'] },
  project: { type: 'string' },
  area: { type: 'string', description: 'Life area, e.g. salud, trabajo, casa, dinero.' },
  priority: { type: 'integer', description: '1 is highest.' },
  due_date: day,
  plan_date: { ...day, description: 'Day the task is planned for (YYYY-MM-DD).' },
  plan_slot: { type: 'string', enum: [...SLOTS], description: 'Part of the planned day.' },
  position: { type: 'number', description: 'Order inside the slot.' },
  following: { type: 'boolean', description: 'True for a follow-up: the task waits on someone else and stays out of the day plan.' },
  waiting_for: { type: 'string', description: 'Follow-ups only: who or what the task is waiting for.' },
  expected_date: { ...day, description: 'Follow-ups only: when the answer is expected (YYYY-MM-DD).' },
  review_date: { ...day, description: 'Follow-ups only: when to check on it again (YYYY-MM-DD).' },
  lane: {
    type: 'string',
    enum: [...LANES],
    description:
      'What the task is to Sejio, one per task: inbox (bandeja, captured and not yet sorted), priority (prioridad real), ' +
      'stack (stack infinito, someday/maybe), waiting (esperando a otra persona), vault (baúl) or failed (fracasos, given up). ' +
      'Setting it also sets board and following. A lane is independent of the day the task is planned on.',
  },
  plan_time: { type: ['string', 'null'], description: 'Set time on plan_date as HH:MM (Europe/Madrid), or null for no set time.' },
  plan_minutes: { type: ['integer', 'null'], minimum: 1, maximum: 1440, description: 'How long the task takes, in minutes.' },
  habit_id: { type: ['string', 'null'], description: 'Habit this task comes from. Completing the task checks the habit on plan_date.' },
  board: {
    type: ['string', 'null'],
    enum: [...BOARD_COLUMNS, null],
    description:
      'Kanban column: stack (stack infinito, ideas and maybe-tasks), priority (prioridad real), vault (baúl) or failed (fracasos). ' +
      'null for an ordinary task that is not on the board. Completing a board task adds one to the done total.',
  },
};

interface Tool {
  name: string;
  title: string;
  description: string;
  inputSchema: Record<string, unknown>;
  readOnly: boolean;
  run(env: Env, args: Record<string, unknown>): Promise<unknown>;
}

function today(env: Env) {
  return currentDay(env.TIME_ZONE);
}

function dayArg(env: Env, value: unknown): string {
  if (value === undefined) return today(env);
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new ValidationError('Dates must be YYYY-MM-DD');
  return value;
}

function stringArg(value: unknown, name: string): string {
  if (typeof value !== 'string' || !value) throw new ValidationError(`${name} is required`);
  return value;
}

const TOOLS: Tool[] = [
  {
    name: 'list_tasks',
    title: 'List tasks',
    description: 'Lists tasks, open ones first. Filter by status, lane, planned day, project, due date, follow-up or text. Each task carries its lane.',
    readOnly: true,
    inputSchema: {
      type: 'object',
      properties: {
        status: taskFields.status,
        plan_date: taskFields.plan_date,
        project: { type: 'string' },
        due_before: { ...day, description: 'Only tasks due on or before this day.' },
        following: { type: 'boolean', description: 'true for follow-ups only, false to leave them out.' },
        board: { type: 'string', enum: [...BOARD_COLUMNS, 'none'], description: 'Only tasks in this Kanban column, or none for tasks off the board.' },
        lane: { ...taskFields.lane, description: 'Only tasks in this lane (inbox, priority, stack, waiting, vault, failed).' },
        search: { type: 'string', description: 'Text to find in the title or notes.' },
        limit: { type: 'integer', minimum: 1, maximum: 1000 },
      },
    },
    run: (env, args) => listTasks(env.DB, args as never),
  },
  {
    name: 'get_task',
    title: 'Get a task',
    description: 'Returns one task by id.',
    readOnly: true,
    inputSchema: { type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
    run: (env, args) => getTask(env.DB, stringArg(args.id, 'id')),
  },
  {
    name: 'create_task',
    title: 'Create a task',
    description: 'Creates a task. Set plan_date and plan_slot to put it on a day plan.',
    readOnly: false,
    inputSchema: { type: 'object', properties: { ...taskFields, source: { type: 'string' }, source_id: { type: 'string' } }, required: ['title'] },
    run: (env, args) => createTask(env.DB, args),
  },
  {
    name: 'update_task',
    title: 'Update a task',
    description: 'Changes the given fields of a task. Pass null to clear a field. Setting status to done records the completion time.',
    readOnly: false,
    inputSchema: { type: 'object', properties: { id: { type: 'string' }, ...taskFields }, required: ['id'] },
    run: (env, args) => {
      const { id, ...patch } = args;
      return updateTask(env.DB, stringArg(id, 'id'), patch);
    },
  },
  {
    name: 'complete_task',
    title: 'Complete a task',
    description: 'Marks a task as done.',
    readOnly: false,
    inputSchema: { type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
    run: (env, args) => updateTask(env.DB, stringArg(args.id, 'id'), { status: 'done' }),
  },
  {
    name: 'delete_task',
    title: 'Delete a task',
    description: 'Deletes a task on every device.',
    readOnly: false,
    inputSchema: { type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
    run: async (env, args) => {
      await deleteTask(env.DB, stringArg(args.id, 'id'));
      return { deleted: args.id };
    },
  },
  {
    name: 'tasks_overview',
    title: 'Tasks overview',
    description:
      'Open tasks per lane (inbox, priority, stack, waiting, vault, failed), the plan of a day grouped as any_time, morning, afternoon ' +
      'and evening, left_over (open tasks planned on an earlier day) and done_total, the running count of completed tasks.',
    readOnly: true,
    inputSchema: { type: 'object', properties: { day } },
    run: (env, args) => tasksOverview(env.DB, dayArg(env, args.day)),
  },
  {
    name: 'move_task',
    title: 'Move a task to a lane',
    description: 'Moves a task to another lane: inbox, priority, stack, waiting, vault or failed (giving it up). Its planned day, if any, stays.',
    readOnly: false,
    inputSchema: { type: 'object', properties: { id: { type: 'string' }, lane: taskFields.lane }, required: ['id', 'lane'] },
    run: (env, args) => moveTask(env.DB, stringArg(args.id, 'id'), args.lane),
  },
  {
    name: 'plan_task',
    title: 'Plan a task on a day',
    description:
      'Puts a task on a day. slot (morning, afternoon, evening) and time (HH:MM, Europe/Madrid) are optional: with only a time, the ' +
      'slot follows it (before 12:00 morning, before 20:00 afternoon, then evening); with neither, the task is for any time that day. ' +
      'minutes is how long it takes. Pass day null to take the task off its day.',
    readOnly: false,
    inputSchema: {
      type: 'object',
      properties: {
        id: { type: 'string' },
        day: { type: ['string', 'null'], pattern: '^\\d{4}-\\d{2}-\\d{2}$', description: 'YYYY-MM-DD, or null to unplan.' },
        slot: { type: ['string', 'null'], enum: [...SLOTS, null] },
        time: { type: ['string', 'null'], pattern: '^\\d{1,2}:\\d{2}$', description: 'HH:MM, e.g. 18:30.' },
        minutes: { type: ['integer', 'null'], minimum: 1, maximum: 1440 },
      },
      required: ['id', 'day'],
    },
    run: (env, args) => planTask(env.DB, stringArg(args.id, 'id'), args as never),
  },
  {
    name: 'board_summary',
    title: 'Board summary',
    description: 'Open tasks in each Kanban column (stack, priority, vault, failed) and done_total, the running count of tasks Sejio has completed (including the old Notion count).',
    readOnly: true,
    inputSchema: { type: 'object', properties: {} },
    run: (env) => boardSummary(env.DB),
  },
  {
    name: 'set_done_total',
    title: 'Set the done total',
    description: 'Fixes the running count of completed tasks shown on the board. Later completions keep adding to it.',
    readOnly: false,
    inputSchema: { type: 'object', properties: { total: { type: 'integer', minimum: 0 } }, required: ['total'] },
    run: (env, args) => setDoneTotal(env.DB, args.total as number),
  },
  {
    name: 'import_tasks',
    title: 'Import tasks',
    description:
      `Creates up to ${MAX_IMPORT} tasks at once from another tool, e.g. Notion. Each item needs source (e.g. notion) and source_id ` +
      '(the id there); items already imported with the same source and source_id are skipped, so repeating an import is safe.',
    readOnly: false,
    inputSchema: {
      type: 'object',
      properties: {
        items: {
          type: 'array',
          maxItems: MAX_IMPORT,
          items: {
            type: 'object',
            properties: {
              title: { type: 'string', maxLength: 500 },
              notes: { type: 'string' },
              board: taskFields.board,
              status: taskFields.status,
              source: { type: 'string' },
              source_id: { type: 'string' },
              created_at: { type: 'integer', description: 'Creation time in epoch milliseconds.' },
            },
            required: ['title', 'source', 'source_id'],
          },
        },
      },
      required: ['items'],
    },
    run: (env, args) => importTasks(env.DB, args.items as ImportItem[]),
  },
  {
    name: 'list_habits',
    title: 'List habits',
    description: 'Lists active habits with their weekly goal and the days done this week.',
    readOnly: true,
    inputSchema: { type: 'object', properties: { day } },
    run: (env, args) => listHabits(env.DB, dayArg(env, args.day)),
  },
  {
    name: 'create_habit',
    title: 'Create a habit',
    description: 'Creates a recurring habit with a weekly goal of 1 to 7 days.',
    readOnly: false,
    inputSchema: {
      type: 'object',
      properties: { name: { type: 'string' }, weekly_goal: { type: 'integer', minimum: 1, maximum: 7 } },
      required: ['name'],
    },
    run: async (env, args) => ({
      id: await createHabit(env.DB, { name: stringArg(args.name, 'name'), weekly_goal: args.weekly_goal as number | undefined }, today(env)),
    }),
  },
  {
    name: 'check_habit',
    title: 'Mark a habit',
    description: 'Marks a habit as done (or not done) on a day.',
    readOnly: false,
    inputSchema: {
      type: 'object',
      properties: { habit_id: { type: 'string' }, day, done: { type: 'boolean', default: true } },
      required: ['habit_id'],
    },
    run: async (env, args) => {
      const target = dayArg(env, args.day);
      await setHabitCheck(env.DB, stringArg(args.habit_id, 'habit_id'), target, args.done !== false);
      return { habit_id: args.habit_id, day: target, done: args.done !== false };
    },
  },
  {
    name: 'habit_to_task',
    title: 'Turn a habit into a task',
    description:
      'Makes a task from a habit for today or tomorrow (or a given YYYY-MM-DD), in the priority lane, for any time that day. ' +
      'Completing the task checks the habit that day and checking the habit completes the task. Asking again for the same habit ' +
      'and day returns the existing task. The phone also adds it to Google Calendar as an all-day event.',
    readOnly: false,
    inputSchema: {
      type: 'object',
      properties: {
        habit_id: { type: 'string' },
        day: { type: 'string', description: 'today (default), tomorrow, or a date as YYYY-MM-DD. Days close at 03:00 Europe/Madrid.' },
      },
      required: ['habit_id'],
    },
    run: (env, args) => {
      const day = args.day === undefined || args.day === 'today' ? today(env) : args.day === 'tomorrow' ? addDays(today(env), 1) : dayArg(env, args.day);
      return habitToTask(env.DB, stringArg(args.habit_id, 'habit_id'), day);
    },
  },
  {
    name: 'log_counter',
    title: 'Add to a daily counter',
    description: 'Adds an amount to calories (kcal), protein (g) or cigarettes for a day. Use a negative amount to correct.',
    readOnly: false,
    inputSchema: {
      type: 'object',
      properties: { kind: { type: 'string', enum: [...METRIC_KINDS] }, amount: { type: 'number' }, day },
      required: ['kind', 'amount'],
    },
    run: async (env, args) => {
      if (typeof args.amount !== 'number' || !Number.isFinite(args.amount)) throw new ValidationError('amount must be a number');
      const target = dayArg(env, args.day);
      const id = await logMetric(env.DB, args.kind as MetricKind, args.amount, target);
      return { id, ...(await daySummary(env.DB, target)).counters };
    },
  },
  {
    name: 'set_counter',
    title: 'Set a daily counter total',
    description:
      'Sets the total of calories (kcal), protein (g) or cigarettes for a day, e.g. to fix a past day the user forgot to log. ' +
      'Adds the difference as a correction entry. Returns the previous total and the day counters.',
    readOnly: false,
    inputSchema: {
      type: 'object',
      properties: { kind: { type: 'string', enum: [...METRIC_KINDS] }, total: { type: 'number', minimum: 0 }, day },
      required: ['kind', 'total'],
    },
    run: async (env, args) => {
      const target = dayArg(env, args.day);
      const result = await setMetricTotal(env.DB, args.kind as MetricKind, args.total as number, target);
      return { day: target, previous: result.previous, ...(await daySummary(env.DB, target)).counters };
    },
  },
  {
    name: 'day_summary',
    title: 'Day summary',
    description: 'Counters against their goals, the day plan by slot and which habits are done.',
    readOnly: true,
    inputSchema: { type: 'object', properties: { day } },
    run: (env, args) => daySummary(env.DB, dayArg(env, args.day)),
  },
  {
    name: 'weekly_review',
    title: 'Weekly or monthly review',
    description:
      'Review of the Monday-to-Sunday week (default) or the calendar month containing the given day: tasks completed and pending, ' +
      'habits against their weekly goal scaled to the period (target), counter averages and per-day totals. Days close at 03:00.',
    readOnly: true,
    inputSchema: {
      type: 'object',
      properties: { day, period: { type: 'string', enum: [...REVIEW_PERIODS], description: 'week (default) or month.' } },
    },
    run: (env, args) => review(env.DB, dayArg(env, args.day), env.TIME_ZONE, (args.period ?? 'week') as ReviewPeriod),
  },
  {
    name: 'health_days',
    title: 'Sleep, steps and exercise',
    description:
      'What the phone read from Health Connect (Pixel Watch) per day: sleep_minutes (time asleep in the night that ends on that day, ' +
      'awake stages left out; sleep_start and sleep_end are epoch ms), steps, exercise_minutes and resting_hr (beats per minute), ' +
      'plus averages over the days that have each value. Steps and exercise count from 03:00 to 03:00. ' +
      'Defaults to the 7 days ending today; at most a year. Days without data are left out. Read only: the phone writes these.',
    readOnly: true,
    inputSchema: {
      type: 'object',
      properties: {
        from: { ...day, description: 'First day (YYYY-MM-DD). Defaults to 6 days before `to`.' },
        to: { ...day, description: 'Last day (YYYY-MM-DD). Defaults to today.' },
      },
    },
    run: (env, args) => healthSummary(env.DB, today(env), args.from, args.to),
  },
  {
    name: 'list_features',
    title: 'List switchable features',
    description: 'Parts of the Pausa apps that can be switched on or off (Ajustes → Funciones on the phone), with whether each is enabled now.',
    readOnly: true,
    inputSchema: { type: 'object', properties: {} },
    run: async (env) => ({ items: await listFeatures(env.DB) }),
  },
  {
    name: 'set_feature',
    title: 'Switch a feature on or off',
    description: 'Turns one feature of the Pausa apps on or off. The phone applies it on its next sync. Use list_features to see the keys.',
    readOnly: false,
    inputSchema: {
      type: 'object',
      properties: { key: { type: 'string', enum: FEATURES.map((feature) => feature.key) }, enabled: { type: 'boolean' } },
      required: ['key', 'enabled'],
    },
    run: (env, args) => setFeature(env.DB, stringArg(args.key, 'key'), args.enabled as boolean),
  },
  // Diagnostics: read-only tools for debugging the apps and the sync.
  {
    name: 'get_client_logs',
    title: 'Read app logs',
    description: 'Recent log lines sent by the Android app (and other clients), newest first. Use it to debug problems on the phone.',
    readOnly: true,
    inputSchema: {
      type: 'object',
      properties: {
        level: { type: 'string', enum: ['debug', 'info', 'warn', 'error'], description: 'Minimum level.' },
        tag: { type: 'string' },
        device_id: { type: 'string' },
        search: { type: 'string', description: 'Text to find in the message.' },
        since: { type: 'integer', description: 'Epoch milliseconds.' },
        limit: { type: 'integer', minimum: 1, maximum: 1000 },
      },
    },
    run: (env, args) => queryClientLogs(env.DB, args as never),
  },
  {
    name: 'sync_status',
    title: 'Sync status',
    description: 'Server change number, each device with its last pull and push and how far behind it is, record counts and client errors in the last 24 hours.',
    readOnly: true,
    inputSchema: { type: 'object', properties: {} },
    run: (env) => syncStatus(env.DB),
  },
  {
    name: 'record_history',
    title: 'Record history',
    description: 'Every saved version of one record (task, habit, check, counter entry or setting) with who wrote it: phone, web or agent.',
    readOnly: true,
    inputSchema: {
      type: 'object',
      properties: { table: { type: 'string', enum: TABLE_NAMES }, id: { type: 'string' } },
      required: ['table', 'id'],
    },
    run: (env, args) => {
      if (!isTableName(args.table)) throw new ValidationError('Unknown table');
      return recordHistory(env.DB, args.table, stringArg(args.id, 'id'));
    },
  },
];

/** Every tool, exposed for tests that keep the MCP surface and its documentation in step. */
export const MCP_TOOLS = TOOLS.map(({ name, title, description, inputSchema, readOnly }) => ({ name, title, description, inputSchema, readOnly }));

type JsonRpcId = string | number | null;

function result(id: JsonRpcId, value: unknown) {
  return Response.json({ jsonrpc: '2.0', id, result: value });
}

function error(id: JsonRpcId, code: number, message: string, status = 200) {
  return Response.json({ jsonrpc: '2.0', id, error: { code, message } }, { status });
}

export async function handleMcp(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'POST') {
    return new Response('Method Not Allowed', { status: 405, headers: { Allow: 'POST' } });
  }
  let message: { jsonrpc?: string; id?: JsonRpcId; method?: string; params?: Record<string, unknown> };
  try {
    message = await request.json();
  } catch {
    return error(null, -32700, 'Parse error', 400);
  }
  if (!message || typeof message !== 'object' || Array.isArray(message) || message.jsonrpc !== '2.0' || typeof message.method !== 'string') {
    return error(null, -32600, 'Invalid Request', 400);
  }
  // Notifications (no id) need no answer.
  if (message.id === undefined) return new Response(null, { status: 202 });
  const id = message.id;
  const params = message.params ?? {};

  switch (message.method) {
    case 'initialize': {
      const requested = String(params.protocolVersion ?? '');
      return result(id, {
        protocolVersion: PROTOCOL_VERSIONS.includes(requested) ? requested : PROTOCOL_VERSIONS[0],
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions:
          'Pausa is Sejio\'s personal life organizer: tasks with day plans (morning, afternoon, evening), weekly habits, ' +
          'and daily counters for calories, protein and cigarettes. Dates are local to Europe/Madrid. ' +
          'Changes made here sync to the Android app and the web.',
      });
    }
    case 'ping':
      return result(id, {});
    case 'tools/list':
      return result(id, {
        tools: TOOLS.map((tool) => ({
          name: tool.name,
          title: tool.title,
          description: tool.description,
          inputSchema: tool.inputSchema,
          annotations: { readOnlyHint: tool.readOnly, destructiveHint: tool.name === 'delete_task', openWorldHint: false },
        })),
      });
    case 'tools/call': {
      const tool = TOOLS.find((candidate) => candidate.name === params.name);
      if (!tool) return error(id, -32602, `Unknown tool: ${String(params.name)}`);
      const args = (params.arguments ?? {}) as Record<string, unknown>;
      const started = Date.now();
      try {
        const value = await tool.run(env, args);
        log('info', 'mcp_tool', { tool: tool.name, ok: true, ms: Date.now() - started });
        const structured = Array.isArray(value) ? { items: value } : (value as Record<string, unknown>);
        return result(id, { content: [{ type: 'text', text: JSON.stringify(value) }], structuredContent: structured, isError: false });
      } catch (caught) {
        if (caught instanceof ValidationError || caught instanceof NotFoundError) {
          log('warn', 'mcp_tool', { tool: tool.name, ok: false, ms: Date.now() - started, error: caught.message });
          return result(id, { content: [{ type: 'text', text: caught.message }], isError: true });
        }
        log('error', 'mcp_tool', { tool: tool.name, ok: false, ms: Date.now() - started, ...errorFields(caught) });
        throw caught;
      }
    }
    default:
      return error(id, -32601, `Method not found: ${message.method}`);
  }
}
