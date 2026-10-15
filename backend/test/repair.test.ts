import { env } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { currentDay } from '../src/domain';
import { deviceToken, json } from './helpers';

describe('day cutoff', () => {
  it('keeps counting the previous day until 03:00 in Madrid', () => {
    // 2026-10-15 01:30 in Madrid (UTC+2) is still the 14th; 03:00 is the 15th.
    expect(currentDay('Europe/Madrid', new Date('2026-10-14T23:30:00Z'))).toBe('2026-10-14');
    expect(currentDay('Europe/Madrid', new Date('2026-10-15T01:00:00Z'))).toBe('2026-10-15');
  });
});

describe('migration 0004', () => {
  it('clears the completion time stamped on imported done tasks and lets phones pull it', async () => {
    const token = await deviceToken();
    const done = (completed_at: number) => ({ title: 'Hecha', status: 'done', created_at: 1, completed_at });
    await json('/api/sync', token, {
      method: 'POST',
      body: JSON.stringify({
        changes: [
          { table: 'tasks', id: 'a', updated_at: 100, deleted: false, data: done(1792014234927) },
          { table: 'tasks', id: 'b', updated_at: 100, deleted: false, data: done(1792014234927) },
          { table: 'tasks', id: 'c', updated_at: 100, deleted: false, data: done(1792000000000) },
        ],
      }),
    });

    const migration = env.TEST_MIGRATIONS.find((m) => m.name.startsWith('0004'))!;
    for (const query of migration.queries) await env.DB.prepare(query).run();

    const pulled = (await (await json('/api/sync?since=3', token)).json()) as any;
    expect(pulled.changes.map((c: any) => [c.id, c.data.completed_at])).toEqual([
      ['a', null],
      ['b', null],
    ]);
    expect(pulled.cursor).toBe(5);
    const history = await env.DB.prepare(`SELECT actor FROM change_log WHERE record_id = 'a' ORDER BY seq`).all<{ actor: string }>();
    expect(history.results.map((r) => r.actor).at(-1)).toBe('migration:0004');
  });
});
