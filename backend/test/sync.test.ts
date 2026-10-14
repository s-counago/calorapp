import { describe, expect, it } from 'vitest';
import { deviceToken, json } from './helpers';

const task = (overrides: Record<string, unknown> = {}) => ({
  title: 'Comprar pan',
  status: 'todo',
  created_at: 1_700_000_000_000,
  ...overrides,
});

async function push(token: string, changes: unknown[]) {
  const response = await json('/api/sync', token, { method: 'POST', body: JSON.stringify({ changes }) });
  return { status: response.status, body: (await response.json()) as any };
}

async function pull(token: string, since = 0) {
  return (await (await json(`/api/sync?since=${since}`, token)).json()) as any;
}

describe('sync', () => {
  it('pushes records and pulls them back after the cursor', async () => {
    const token = await deviceToken();
    const first = await push(token, [
      { table: 'tasks', id: 't1', updated_at: 100, deleted: false, data: task() },
      { table: 'metric_entries', id: 'm1', updated_at: 100, deleted: false, data: { day: '2026-10-14', kind: 'calories', amount: 300, at: 100 } },
    ]);
    expect(first.status).toBe(200);
    expect(first.body.applied).toEqual(['t1', 'm1']);
    expect(first.body.cursor).toBe(2);

    const all = await pull(token);
    expect(all.cursor).toBe(2);
    expect(all.changes.map((change: any) => change.id)).toEqual(['t1', 'm1']);
    expect(all.changes[0].data).toMatchObject({ title: 'Comprar pan', status: 'todo', plan_date: null });

    const nothing = await pull(token, 2);
    expect(nothing.changes).toEqual([]);
  });

  it('keeps the newest edit when two devices change the same record', async () => {
    const token = await deviceToken();
    await push(token, [{ table: 'tasks', id: 't1', updated_at: 200, deleted: false, data: task({ title: 'Nuevo' }) }]);
    const stale = await push(token, [{ table: 'tasks', id: 't1', updated_at: 150, deleted: false, data: task({ title: 'Viejo' }) }]);
    expect(stale.body.applied).toEqual([]);

    const { changes } = await pull(token);
    expect(changes).toHaveLength(1);
    expect(changes[0].data.title).toBe('Nuevo');
  });

  it('spreads deletions as tombstones', async () => {
    const token = await deviceToken();
    await push(token, [{ table: 'tasks', id: 't1', updated_at: 100, deleted: false, data: task() }]);
    const { cursor } = await pull(token);
    await push(token, [{ table: 'tasks', id: 't1', updated_at: 300, deleted: true, data: task() }]);

    const { changes } = await pull(token, cursor);
    expect(changes).toEqual([expect.objectContaining({ id: 't1', deleted: true })]);
    const visible = (await (await json('/api/tasks', token)).json()) as unknown[];
    expect(visible).toEqual([]);
  });

  it('merges the same habit day checked on two devices into one row', async () => {
    const token = await deviceToken();
    await push(token, [{ table: 'habits', id: 'h1', updated_at: 1, deleted: false, data: { name: 'Leer', weekly_goal: 3, created_day: '2026-10-01' } }]);
    const check = { table: 'habit_checks', id: 'h1:2026-10-14', deleted: false, data: { habit_id: 'h1', day: '2026-10-14' } };
    await push(token, [{ ...check, updated_at: 10 }]);
    await push(token, [{ ...check, updated_at: 11 }]);
    const { changes } = await pull(token);
    expect(changes.filter((change: any) => change.table === 'habit_checks')).toHaveLength(1);

    const wrongId = await push(token, [{ ...check, id: 'something-else', updated_at: 12 }]);
    expect(wrongId.status).toBe(400);
  });

  it('rejects invalid records without writing any of the batch', async () => {
    const token = await deviceToken();
    const result = await push(token, [
      { table: 'tasks', id: 'ok', updated_at: 1, deleted: false, data: task() },
      { table: 'tasks', id: 'bad', updated_at: 1, deleted: false, data: task({ status: 'maybe' }) },
    ]);
    expect(result.status).toBe(400);
    expect(result.body.error).toContain('status');
    expect((await pull(token)).changes).toEqual([]);

    expect((await push(token, [{ table: 'bank', id: 'x', updated_at: 1, deleted: false, data: {} }])).status).toBe(400);
  });
});
