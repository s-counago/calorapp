import { describe, expect, it } from 'vitest';
import { deviceToken, json } from './helpers';

const cycle = (start: string, end: string, overrides: Record<string, unknown> = {}) => ({
  start,
  end,
  current: false,
  income: 180000,
  fixed: 60000,
  saved: 30000,
  savings_target: 30000,
  free_budget: 90000,
  free_spent: 40000,
  available: 50000,
  daily_allowance: 2500,
  projection: 10000,
  savings_at_close: 40000,
  unexpected: 0,
  covered: 0,
  cushion_left: 100000,
  lines: JSON.stringify([
    { id: 'tabaco', name: 'Tabaco', cap: true, expected: 9500, paid: 6200, logged: 0, status: 'partial', due: null, frequency: 1 },
    { id: 'google', name: 'Spotify', cap: false, expected: 649, paid: 649, logged: 0, status: 'paid', due: '2026-10-24', frequency: 1 },
  ]),
  categories: JSON.stringify([{ key: 'super', label: 'Súper', cents: 21000 }]),
  computed_at: 1,
  ...overrides,
});

describe('budget', () => {
  it('stores the cycle summaries the phone uploads and serves them without movements', async () => {
    const token = await deviceToken();
    const push = (changes: unknown[]) => json('/api/sync', token, { method: 'POST', body: JSON.stringify({ changes }) });
    expect((await push([
      { table: 'budget_cycles', id: '2026-09-26', updated_at: 1, deleted: false, data: cycle('2026-09-26', '2026-10-25', { current: true }) },
      { table: 'budget_cycles', id: '2026-08-27', updated_at: 1, deleted: false, data: cycle('2026-08-27', '2026-09-25', { lines: 'not json' }) },
    ])).status).toBe(200);
    expect((await push([{ table: 'budget_cycles', id: 'x', updated_at: 1, deleted: false, data: cycle('2026-07-27', '2026-08-26') }])).status).toBe(400);

    const latest = (await (await json('/api/budget', token)).json()) as any;
    expect(latest.cycle).toMatchObject({ start: '2026-09-26', current: true, available: 50000 });
    expect(latest.cycle.caps).toEqual([expect.objectContaining({ name: 'Tabaco', expected: 9500, paid: 6200, status: 'partial' })]);
    expect(latest.cycle.categories).toEqual([{ key: 'super', label: 'Súper', cents: 21000 }]);
    expect(latest.cycles.map((item: any) => item.start)).toEqual(['2026-09-26', '2026-08-27']);

    const older = (await (await json('/api/budget?day=2026-09-01', token)).json()) as any;
    expect(older.cycle.start).toBe('2026-08-27');
    expect(older.cycle.lines).toEqual([]);
    expect((await json('/api/budget?day=1-1', token)).status).toBe(400);
  });
});
