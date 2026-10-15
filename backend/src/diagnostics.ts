// What a debugging session needs without the phone at hand: the logs the apps send
// and where each device is in the sync.

import { ValidationError } from './schema';
import { currentSeq } from './sync';
import { log, requestId, type Level } from './log';

const LEVELS: Level[] = ['debug', 'info', 'warn', 'error'];
const MAX_ENTRIES = 200;
const RETENTION_DAYS = 30;

export interface ClientLog {
  at: number;
  level: Level;
  tag: string;
  message: string;
  data?: unknown;
}

export function parseClientLogs(body: unknown): { entries: ClientLog[]; appVersion: string | null } {
  const raw = (body as { entries?: unknown })?.entries;
  if (!Array.isArray(raw)) throw new ValidationError('Expected { entries: [...] }');
  if (raw.length > MAX_ENTRIES) throw new ValidationError(`At most ${MAX_ENTRIES} entries per request`);
  const version = (body as { app_version?: unknown }).app_version;
  const entries = raw.map((item, index) => {
    const entry = (item ?? {}) as Record<string, unknown>;
    if (typeof entry.at !== 'number' || !Number.isFinite(entry.at)) throw new ValidationError(`entries[${index}].at must be epoch milliseconds`);
    if (!LEVELS.includes(entry.level as Level)) throw new ValidationError(`entries[${index}].level must be one of ${LEVELS.join(', ')}`);
    if (typeof entry.message !== 'string') throw new ValidationError(`entries[${index}].message is required`);
    return {
      at: Math.trunc(entry.at),
      level: entry.level as Level,
      tag: typeof entry.tag === 'string' ? entry.tag.slice(0, 100) : 'app',
      message: entry.message.slice(0, 4000),
      data: entry.data,
    };
  });
  return { entries, appVersion: typeof version === 'string' ? version.slice(0, 50) : null };
}

export async function storeClientLogs(db: D1Database, deviceId: string | null, entries: ClientLog[], appVersion: string | null) {
  const now = Date.now();
  const rid = requestId() ?? null;
  const statements = entries.map((entry) =>
    db
      .prepare(`INSERT INTO client_logs (device_id, at, level, tag, message, data, app_version, request_id, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .bind(deviceId, entry.at, entry.level, entry.tag, entry.message, entry.data === undefined ? null : JSON.stringify(entry.data).slice(0, 20000), appVersion, rid, now),
  );
  statements.push(db.prepare(`DELETE FROM client_logs WHERE received_at < ?`).bind(now - RETENTION_DAYS * 86400_000));
  if (deviceId) {
    const errors = entries.filter((entry) => entry.level === 'error');
    statements.push(
      db.prepare(`UPDATE devices SET app_version = COALESCE(?, app_version), last_error_at = COALESCE(?, last_error_at) WHERE id = ?`)
        .bind(appVersion, errors.length ? Math.max(...errors.map((entry) => entry.at)) : null, deviceId),
    );
  }
  await db.batch(statements);
  // Mirror client errors into Workers Logs so they show up next to the server's own.
  for (const entry of entries) {
    if (entry.level === 'error' || entry.level === 'warn') {
      log(entry.level, 'client_log', { device_id: deviceId, tag: entry.tag, message: entry.message, at: entry.at, app_version: appVersion });
    }
  }
}

export interface LogFilter {
  since?: number;
  level?: string;
  tag?: string;
  device_id?: string;
  search?: string;
  limit?: number;
}

export async function queryClientLogs(db: D1Database, filter: LogFilter = {}) {
  const where: string[] = [];
  const params: (string | number)[] = [];
  if (filter.since !== undefined) { where.push('at >= ?'); params.push(filter.since); }
  if (filter.level) {
    if (!LEVELS.includes(filter.level as Level)) throw new ValidationError(`level must be one of ${LEVELS.join(', ')}`);
    // A level includes everything more severe.
    const allowed = LEVELS.slice(LEVELS.indexOf(filter.level as Level));
    where.push(`level IN (${allowed.map(() => '?').join(', ')})`);
    params.push(...allowed);
  }
  if (filter.tag) { where.push('tag = ?'); params.push(filter.tag); }
  if (filter.device_id) { where.push('device_id = ?'); params.push(filter.device_id); }
  if (filter.search) { where.push(`message LIKE ? ESCAPE '\\'`); params.push(`%${filter.search.replace(/[\\%_]/g, '\\$&')}%`); }
  const limit = Math.min(Math.max(filter.limit ?? 100, 1), 1000);
  const { results } = await db
    .prepare(`SELECT * FROM client_logs ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY at DESC, id DESC LIMIT ${limit}`)
    .bind(...params)
    .all<Record<string, unknown>>();
  return results.map((row) => ({ ...row, data: typeof row.data === 'string' ? safeJson(row.data) : null }));
}

function safeJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

export async function recordPull(db: D1Database, deviceId: string, cursor: number) {
  await db.prepare(`UPDATE devices SET last_pull_at = ?, last_pull_cursor = ? WHERE id = ?`).bind(Date.now(), cursor, deviceId).run();
}

export async function recordPush(db: D1Database, deviceId: string, count: number) {
  await db.prepare(`UPDATE devices SET last_push_at = ?, last_push_count = ? WHERE id = ?`).bind(Date.now(), count, deviceId).run();
}

/** Server head, each device's position and recent errors: the first thing to check when sync misbehaves. */
export async function syncStatus(db: D1Database) {
  const [head, devices, counts, errors] = await Promise.all([
    currentSeq(db),
    db.prepare(`SELECT id, name, app_version, created_at, last_seen_at, last_pull_at, last_pull_cursor, last_push_at, last_push_count, last_error_at, revoked_at FROM devices ORDER BY created_at DESC`).all<Record<string, number | string | null>>(),
    db.batch<{ name: string; live: number; deleted: number }>(
      ['tasks', 'habits', 'habit_checks', 'metric_entries', 'settings', 'health_days', 'captures'].map((table) =>
        db.prepare(`SELECT '${table}' AS name, SUM(deleted = 0) AS live, SUM(deleted = 1) AS deleted FROM ${table}`),
      ),
    ),
    db.prepare(`SELECT COUNT(*) AS n FROM client_logs WHERE level = 'error' AND at >= ?`).bind(Date.now() - 86400_000).first<{ n: number }>(),
  ]);
  return {
    server_seq: head,
    devices: devices.results.map((device) => ({
      ...device,
      behind_by: typeof device.last_pull_cursor === 'number' ? head - device.last_pull_cursor : null,
    })),
    records: Object.fromEntries(counts.map((result) => [result.results[0].name, { live: result.results[0].live ?? 0, deleted: result.results[0].deleted ?? 0 }])),
    client_errors_last_24h: errors?.n ?? 0,
  };
}
