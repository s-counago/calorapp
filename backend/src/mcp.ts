// A stateless MCP server over Streamable HTTP: every POST carries one JSON-RPC message
// and gets one JSON response. Tools call the same domain functions as the REST API.

import { ValidationError } from './schema';
import type { Env } from './auth';
import {
  METRIC_KINDS,
  NotFoundError,
  SLOTS,
  createHabit,
  createTask,
  daySummary,
  deleteTask,
  getTask,
  listHabits,
  listTasks,
  localDay,
  logMetric,
  setHabitCheck,
  updateTask,
  weeklyReview,
  type MetricKind,
} from './domain';

const PROTOCOL_VERSIONS = ['2026-07-28', '2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];
const SERVER_INFO = { name: 'pausa', title: 'Pausa', version: '1.0.0' };

const day = { type: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$', description: 'Date as YYYY-MM-DD. Defaults to today in Europe/Madrid.' };
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
  return localDay(env.TIME_ZONE);
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
    description: 'Lists tasks, open ones first. Filter by status, planned day, project, due date or text.',
    readOnly: true,
    inputSchema: {
      type: 'object',
      properties: {
        status: taskFields.status,
        plan_date: taskFields.plan_date,
        project: { type: 'string' },
        due_before: { ...day, description: 'Only tasks due on or before this day.' },
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
    name: 'day_summary',
    title: 'Day summary',
    description: 'Counters against their goals, the day plan by slot and which habits are done.',
    readOnly: true,
    inputSchema: { type: 'object', properties: { day } },
    run: (env, args) => daySummary(env.DB, dayArg(env, args.day)),
  },
  {
    name: 'weekly_review',
    title: 'Weekly review',
    description: 'Review of the Monday-to-Sunday week containing the given day: tasks completed and pending, habits against their goals, counter averages.',
    readOnly: true,
    inputSchema: { type: 'object', properties: { day } },
    run: (env, args) => weeklyReview(env.DB, dayArg(env, args.day), env.TIME_ZONE),
  },
];

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
      try {
        const value = await tool.run(env, args);
        const structured = Array.isArray(value) ? { items: value } : (value as Record<string, unknown>);
        return result(id, { content: [{ type: 'text', text: JSON.stringify(value) }], structuredContent: structured, isError: false });
      } catch (caught) {
        if (caught instanceof ValidationError || caught instanceof NotFoundError) {
          return result(id, { content: [{ type: 'text', text: caught.message }], isError: true });
        }
        throw caught;
      }
    }
    default:
      return error(id, -32601, `Method not found: ${message.method}`);
  }
}
