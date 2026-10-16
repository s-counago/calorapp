import { describe, expect, it } from 'vitest';
import { call, deviceToken, json, sessionHeader } from './helpers';

describe('api', () => {
  it('requires a session or a device token', async () => {
    expect((await call('/api/tasks')).status).toBe(401);
    expect((await json('/api/tasks', 'pd_not-a-real-token')).status).toBe(401);
    expect((await call('/api/me', { headers: { Cookie: await sessionHeader() } })).status).toBe(200);
    expect((await call('/api/me', { headers: { Cookie: await sessionHeader('someone@else.com') } })).status).toBe(401);
  });

  it('creates, plans, completes and deletes tasks', async () => {
    const token = await deviceToken();
    const created = (await (await json('/api/tasks', token, { method: 'POST', body: JSON.stringify({ title: 'Llamar al banco', plan_date: '2026-10-14', plan_slot: 'morning' }) })).json()) as any;
    expect(created).toMatchObject({ title: 'Llamar al banco', status: 'todo', plan_slot: 'morning' });

    const done = (await (await json(`/api/tasks/${created.id}`, token, { method: 'PATCH', body: JSON.stringify({ status: 'done' }) })).json()) as any;
    expect(done.status).toBe('done');
    expect(done.completed_at).toBeTypeOf('number');

    const day = (await (await json('/api/day?day=2026-10-14', token)).json()) as any;
    expect(day.plan.morning.map((task: any) => task.id)).toEqual([created.id]);

    expect((await json(`/api/tasks/${created.id}`, token, { method: 'DELETE' })).status).toBe(204);
    expect((await json(`/api/tasks/${created.id}`, token)).status).toBe(404);
    expect((await json('/api/tasks', token, { method: 'POST', body: JSON.stringify({ title: 'x', plan_slot: 'night' }) })).status).toBe(400);
  });

  it('adds counter entries and builds the weekly review', async () => {
    const token = await deviceToken();
    const add = (kind: string, amount: number, day: string) =>
      json('/api/counters', token, { method: 'POST', body: JSON.stringify({ kind, amount, day }) });
    await add('calories', 800, '2026-10-12');
    await add('calories', 900, '2026-10-12');
    await add('protein', 130, '2026-10-12');
    await add('calories', 2600, '2026-10-13');
    await add('cigarettes', 1, '2026-10-13');

    const habit = (await (await json('/api/habits', token, { method: 'POST', body: JSON.stringify({ name: 'Correr', weekly_goal: 2 }) })).json()) as any;
    await json(`/api/habits/${habit.id}/check`, token, { method: 'POST', body: JSON.stringify({ day: '2026-10-12' }) });
    await json(`/api/habits/${habit.id}/check`, token, { method: 'POST', body: JSON.stringify({ day: '2026-10-14' }) });

    const day = (await (await json('/api/day?day=2026-10-12', token)).json()) as any;
    expect(day.counters.calories).toEqual({ total: 1700, goal: 2450 });

    const review = (await (await json('/api/review?day=2026-10-15', token)).json()) as any;
    expect(review.week_start).toBe('2026-10-12');
    expect(review.week_end).toBe('2026-10-18');
    expect(review.counters.days_logged).toBe(2);
    expect(review.counters.average.calories).toBe(2150);
    expect(review.counters.days_within_calorie_goal).toBe(1);
    expect(review.counters.days_reaching_protein_goal).toBe(1);
    expect(review.habits).toEqual([expect.objectContaining({ name: 'Correr', days_done: 2, met: true })]);
  });

  it('builds the monthly review with habit targets scaled to the month', async () => {
    const token = await deviceToken();
    await json('/api/counters', token, { method: 'POST', body: JSON.stringify({ kind: 'calories', amount: 2000, day: '2026-10-01' }) });
    await json('/api/counters', token, { method: 'POST', body: JSON.stringify({ kind: 'calories', amount: 3000, day: '2026-10-31' }) });
    await json('/api/counters', token, { method: 'POST', body: JSON.stringify({ kind: 'calories', amount: 9999, day: '2026-11-01' }) });
    const habit = (await (await json('/api/habits', token, { method: 'POST', body: JSON.stringify({ name: 'Gym', weekly_goal: 4 }) })).json()) as any;
    await json(`/api/habits/${habit.id}/check`, token, { method: 'POST', body: JSON.stringify({ day: '2026-10-05' }) });

    const month = (await (await json('/api/review?day=2026-10-15&period=month', token)).json()) as any;
    expect(month).toMatchObject({ period: 'month', start: '2026-10-01', end: '2026-10-31' });
    expect(month.week_start).toBeUndefined();
    expect(month.counters.per_day).toHaveLength(31);
    expect(month.counters.average.calories).toBe(2500);
    expect(month.habits).toEqual([expect.objectContaining({ name: 'Gym', target: 18, days_done: 1, met: false })]);
    expect((await json('/api/review?period=year', token)).status).toBe(400);
  });

  it('switches features on and off as synced settings', async () => {
    const token = await deviceToken();
    const list = async () => ((await (await json('/api/features', token)).json()) as any).items;
    expect(await list()).toEqual([
      expect.objectContaining({ key: 'pausa_v7', enabled: false, default: false }),
      expect.objectContaining({ key: 'review_zoom', enabled: false, default: false }),
      expect.objectContaining({ key: 'task_kanban', enabled: false, default: false }),
      expect.objectContaining({ key: 'habit_tasks', enabled: false, default: false }),
      expect.objectContaining({ key: 'captures', enabled: false, default: false }),
      expect.objectContaining({ key: 'focus_note', enabled: false, default: false }),
      expect.objectContaining({ key: 'budget_cloud', enabled: false, default: false }),
      expect.objectContaining({ key: 'health_connect', enabled: false, default: false }),
    ]);

    const on = (await (await json('/api/features/review_zoom', token, { method: 'PUT', body: JSON.stringify({ enabled: true }) })).json()) as any;
    expect(on).toMatchObject({ key: 'review_zoom', enabled: true });
    expect(await list()).toEqual(expect.arrayContaining([expect.objectContaining({ key: 'review_zoom', enabled: true })]));
    const { changes } = (await (await json('/api/sync?since=0', token)).json()) as any;
    expect(changes).toEqual([expect.objectContaining({ table: 'settings', id: 'feature:review_zoom', data: { value: 'on' } })]);

    expect((await json('/api/features/nope', token, { method: 'PUT', body: JSON.stringify({ enabled: true }) })).status).toBe(400);
    expect((await json('/api/features/review_zoom', token, { method: 'PUT', body: JSON.stringify({ enabled: 'yes' }) })).status).toBe(400);
  });

  it('sets the total of a forgotten day with one correction entry', async () => {
    const token = await deviceToken();
    await json('/api/counters', token, { method: 'POST', body: JSON.stringify({ kind: 'calories', amount: 500, day: '2026-10-10' }) });
    const set = (total: number) =>
      json('/api/counters', token, { method: 'PUT', body: JSON.stringify({ kind: 'calories', total, day: '2026-10-10' }) });
    expect(await (await set(2100)).json()).toMatchObject({ previous: 500, total: 2100 });
    expect(await (await set(2100)).json()).toMatchObject({ id: null, previous: 2100 });
    await set(1800);
    const day = (await (await json('/api/day?day=2026-10-10', token)).json()) as any;
    expect(day.counters.calories.total).toBe(1800);
    const { changes } = (await (await json('/api/sync?since=0', token)).json()) as any;
    expect(changes.map((change: any) => change.data.amount)).toEqual([500, 1600, -300]);
    expect((await set(-1)).status).toBe(400);
  });
});
