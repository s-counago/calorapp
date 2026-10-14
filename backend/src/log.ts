// Structured logging. Every line is one JSON object, which Workers Logs indexes, so
// `wrangler tail --format json` or the dashboard can filter by request_id, event or level.
// The request id also goes back to clients in the X-Request-Id header.

import { AsyncLocalStorage } from 'node:async_hooks';

export type Level = 'debug' | 'info' | 'warn' | 'error';

interface RequestContext {
  requestId: string;
  fields: Record<string, unknown>;
}

const context = new AsyncLocalStorage<RequestContext>();

export function runWithRequest<T>(requestId: string, fn: () => T): T {
  return context.run({ requestId, fields: {} }, fn);
}

export function requestId(): string | undefined {
  return context.getStore()?.requestId;
}

/** Who is making the current request: "web", "device:<id>", "mcp:<client>" or "server". */
export function actor(): string {
  const value = context.getStore()?.fields.actor;
  return typeof value === 'string' ? value : 'server';
}

/** Adds fields (who is calling, which device) to every later line of this request. */
export function annotate(fields: Record<string, unknown>): void {
  const store = context.getStore();
  if (store) Object.assign(store.fields, fields);
}

export function log(level: Level, event: string, fields: Record<string, unknown> = {}): void {
  const store = context.getStore();
  const line = JSON.stringify({ level, event, request_id: store?.requestId, ...store?.fields, ...fields });
  if (level === 'error') console.error(line);
  else if (level === 'warn') console.warn(line);
  else console.log(line);
}

export function errorFields(caught: unknown): Record<string, unknown> {
  if (caught instanceof Error) return { error: caught.message, error_type: caught.name, stack: caught.stack };
  return { error: String(caught) };
}
