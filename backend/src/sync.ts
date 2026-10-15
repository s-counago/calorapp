// Offline-first sync. Devices keep their own copy and an outbox of changes. They push
// full records with the time they were edited; the newest edit of a record wins.
// Every accepted write gets a new server `seq`, and devices pull everything above
// the cursor they last saw.

import { actor, log, requestId } from './log';
import { TABLES, TABLE_NAMES, ValidationError, isTableName, toRecord, validateRecord, type Row, type TableName } from './schema';

export interface Change {
  table: TableName;
  id: string;
  updated_at: number;
  deleted: boolean;
  data: Record<string, unknown>;
}

export interface PullResult {
  cursor: number;
  more: boolean;
  changes: Change[];
}

const MAX_PUSH = 500;
const PULL_PAGE = 1000;
const ID = /^[A-Za-z0-9_:.-]{1,120}$/;

export function parseChanges(body: unknown): Change[] {
  if (!body || typeof body !== 'object' || !Array.isArray((body as { changes?: unknown }).changes)) {
    throw new ValidationError('Expected { changes: [...] }');
  }
  const raw = (body as { changes: unknown[] }).changes;
  if (raw.length > MAX_PUSH) throw new ValidationError(`At most ${MAX_PUSH} changes per request`);
  return raw.map((item, index) => {
    if (!item || typeof item !== 'object') throw new ValidationError(`changes[${index}] must be an object`);
    const change = item as Record<string, unknown>;
    if (!isTableName(change.table)) throw new ValidationError(`changes[${index}].table is unknown`);
    if (typeof change.id !== 'string' || !ID.test(change.id)) throw new ValidationError(`changes[${index}].id is invalid`);
    if (typeof change.updated_at !== 'number' || !Number.isInteger(change.updated_at) || change.updated_at < 0) {
      throw new ValidationError(`changes[${index}].updated_at must be epoch milliseconds`);
    }
    const data = change.data;
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new ValidationError(`changes[${index}].data must be an object`);
    return {
      table: change.table,
      id: change.id,
      updated_at: change.updated_at,
      deleted: change.deleted === true,
      data: data as Record<string, unknown>,
    };
  });
}

const SEQ = `(SELECT value FROM meta WHERE key = 'seq')`;

/** Copies the row into change_log only if the upsert before it won (it then carries this seq). */
function auditStatement(db: D1Database, change: Change, offset: number, who: string, rid: string | null): D1PreparedStatement {
  const fields = Object.keys(TABLES[change.table].fields);
  const json = `json_object(${fields.map((field) => `'${field}', ${field}`).join(', ')})`;
  return db
    .prepare(
      `INSERT INTO change_log (seq, table_name, record_id, deleted, updated_at, data, actor, request_id, logged_at) ` +
        `SELECT seq, ?, id, deleted, updated_at, ${json}, ?, ?, ? FROM ${change.table} WHERE id = ? AND seq = ${SEQ} + ${offset}`,
    )
    .bind(change.table, who, rid, Date.now(), change.id);
}

function upsertStatement(db: D1Database, change: Change, offset: number): D1PreparedStatement {
  const row = validateRecord(change.table, change.data);
  if (change.table === 'habit_checks' && change.id !== `${row.habit_id}:${row.day}`) {
    throw new ValidationError('habit_checks.id must be "<habit_id>:<day>"');
  }
  if (change.table === 'health_days' && change.id !== row.day) {
    throw new ValidationError('health_days.id must be the day (YYYY-MM-DD)');
  }
  if (change.table === 'budget_cycles' && change.id !== row.start) {
    throw new ValidationError('budget_cycles.id must be the first day of the cycle (YYYY-MM-DD)');
  }
  const fields = Object.keys(TABLES[change.table].fields);
  const columns = ['id', ...fields, 'updated_at', 'deleted', 'seq'];
  const placeholders = [...columns.slice(0, -1).map(() => '?'), `${SEQ} + ${offset}`];
  const updates = columns.slice(1).map((column) => `${column} = excluded.${column}`);
  const sql =
    `INSERT INTO ${change.table} (${columns.join(', ')}) VALUES (${placeholders.join(', ')}) ` +
    `ON CONFLICT(id) DO UPDATE SET ${updates.join(', ')} ` +
    `WHERE excluded.updated_at >= ${change.table}.updated_at RETURNING id`;
  return db.prepare(sql).bind(change.id, ...fields.map((field) => row[field]), change.updated_at, change.deleted ? 1 : 0);
}

/**
 * Applies changes in one transaction. Returns the ids that were written; the others
 * lost to a newer edit already on the server and will come back on the next pull.
 */
export async function applyChanges(db: D1Database, changes: Change[]): Promise<{ applied: string[]; cursor: number }> {
  if (changes.length === 0) return { applied: [], cursor: await currentSeq(db) };
  const who = actor();
  const rid = requestId() ?? null;
  const statements = changes.flatMap((change, index) => [upsertStatement(db, change, index + 1), auditStatement(db, change, index + 1, who, rid)]);
  statements.push(db.prepare(`UPDATE meta SET value = value + ? WHERE key = 'seq' RETURNING value`).bind(changes.length));
  const results = await db.batch<{ id?: string; value?: number }>(statements);
  const applied = results.filter((_, index) => index % 2 === 0 && index < results.length - 1).flatMap((result) => result.results.map((row) => String(row.id)));
  const cursor = Number(results[results.length - 1].results[0].value);
  const rejected = changes.length - applied.length;
  log('info', 'sync_apply', { received: changes.length, applied: applied.length, stale: rejected, cursor, tables: [...new Set(changes.map((change) => change.table))] });
  return { applied, cursor };
}

export async function currentSeq(db: D1Database): Promise<number> {
  const row = await db.prepare(`SELECT value FROM meta WHERE key = 'seq'`).first<{ value: number }>();
  return Number(row?.value ?? 0);
}

export function rowToChange(table: TableName, row: Row): Change {
  return {
    table,
    id: String(row.id),
    updated_at: Number(row.updated_at),
    deleted: row.deleted === 1,
    data: toRecord(table, row),
  };
}

/** Everything that changed after `since`, oldest first, in pages. */
export async function pullChanges(db: D1Database, since: number): Promise<PullResult> {
  const head = await currentSeq(db);
  const pages = await db.batch<Row>(
    TABLE_NAMES.map((table) =>
      db.prepare(`SELECT * FROM ${table} WHERE seq > ? AND seq <= ? ORDER BY seq LIMIT ${PULL_PAGE}`).bind(since, head),
    ),
  );
  // When a table fills its page, stop the cursor at its last row so nothing is skipped.
  let cursor = head;
  let more = false;
  pages.forEach((page) => {
    if (page.results.length === PULL_PAGE) {
      more = true;
      cursor = Math.min(cursor, Number(page.results[page.results.length - 1].seq));
    }
  });
  const changes = pages
    .flatMap((page, index) =>
      page.results.filter((row) => Number(row.seq) <= cursor).map((row) => ({ seq: Number(row.seq), change: rowToChange(TABLE_NAMES[index], row) })),
    )
    .sort((a, b) => a.seq - b.seq)
    .map((entry) => entry.change);
  return { cursor, more, changes };
}

/** All recorded versions of a record, oldest first, with who wrote each one. */
export async function recordHistory(db: D1Database, table: TableName, id: string) {
  const { results } = await db
    .prepare(`SELECT seq, deleted, updated_at, data, actor, request_id, logged_at FROM change_log WHERE table_name = ? AND record_id = ? ORDER BY seq`)
    .bind(table, id)
    .all<{ seq: number; deleted: number; updated_at: number; data: string; actor: string; request_id: string | null; logged_at: number }>();
  return results.map((row) => ({ ...row, deleted: row.deleted === 1, data: JSON.parse(row.data) as unknown }));
}
