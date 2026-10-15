import { describe, expect, it } from 'vitest';
import { deviceToken, json } from './helpers';

const night = (day: string, overrides: Record<string, unknown> = {}) => ({
  day,
  sleep_minutes: 420,
  sleep_start: 1_760_480_000_000,
  sleep_end: 1_760_506_000_000,
  steps: 8000,
  exercise_minutes: 30,
  resting_hr: 58,
  source: 'health_connect',
  ...overrides,
});

async function push(token: string, changes: unknown[]) {
  const response = await json('/api/sync', token, { method: 'POST', body: JSON.stringify({ changes }) });
  return { status: response.status, body: (await response.json()) as any };
}

describe('health', () => {
  it('stores one row per day from the phone and summarises it', async () => {
    const token = await deviceToken();
    const pushed = await push(token, [
      { table: 'health_days', id: '2026-10-13', updated_at: 100, deleted: false, data: night('2026-10-13', { sleep_minutes: 400, steps: 6000 }) },
      { table: 'health_days', id: '2026-10-14', updated_at: 100, deleted: false, data: night('2026-10-14', { resting_hr: null, exercise_minutes: 0 }) },
    ]);
    expect(pushed.status).toBe(200);
    expect(pushed.body.applied).toEqual(['2026-10-13', '2026-10-14']);

    // The phone reads the same day again later and overwrites it.
    await push(token, [{ table: 'health_days', id: '2026-10-14', updated_at: 200, deleted: false, data: night('2026-10-14', { steps: 10000, resting_hr: null, exercise_minutes: 0 }) }]);

    const health = (await (await json('/api/health?from=2026-10-12&to=2026-10-14', token)).json()) as any;
    expect(health).toMatchObject({
      from: '2026-10-12',
      to: '2026-10-14',
      days_with_data: 2,
      average: { sleep_minutes: 410, steps: 8000, resting_hr: 58 },
      exercise_minutes_total: 30,
    });
    expect(health.per_day.map((row: any) => row.day)).toEqual(['2026-10-13', '2026-10-14']);

    const day = (await (await json('/api/day?day=2026-10-14', token)).json()) as any;
    expect(day.health).toMatchObject({ day: '2026-10-14', steps: 10000, sleep_minutes: 420, resting_hr: null });
    const empty = (await (await json('/api/day?day=2026-10-10', token)).json()) as any;
    expect(empty.health).toBeNull();

    const week = (await (await json('/api/review?day=2026-10-14', token)).json()) as any;
    expect(week.health).toMatchObject({ days_with_data: 2, average: { steps: 8000 } });
  });

  it('rejects a row whose id is not its day and bad ranges', async () => {
    const token = await deviceToken();
    const wrong = await push(token, [{ table: 'health_days', id: 'x1', updated_at: 100, deleted: false, data: night('2026-10-14') }]);
    expect(wrong.status).toBe(400);
    expect((await json('/api/health?from=2026-10-14&to=2026-10-01', token)).status).toBe(400);
    expect((await json('/api/health?from=2024-01-01&to=2026-10-01', token)).status).toBe(400);
    expect((await json('/api/health?from=14-10-2026', token)).status).toBe(400);
  });
});
