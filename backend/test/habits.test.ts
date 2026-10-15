import { describe, expect, it } from 'vitest';
import { scoreHabit } from '../src/domain';
import { deviceToken, json } from './helpers';

describe('habit points', () => {
  it('rewards weeks that meet the goal, grows 15 % a week in a row and adds a bonus per extra day', () => {
    // Weeks of 2026-09-28, 10-05 and 10-12; goal 2.
    const days = ['2026-09-28', '2026-09-29', '2026-10-05', '2026-10-06', '2026-10-07', '2026-10-12', '2026-10-13'];
    const score = scoreHabit(days, 2, '2026-09-28', '2026-10-15');
    // 5, then ceil(5 * 1.15) = 6 plus one extra day worth ceil(6 * 20 %) = 2, then ceil(6 * 1.15) = 7.
    expect(score.points).toBe(5 + 6 + 2 + 7);
    expect(score.streak).toBe(3);
    expect(score.minimum_met).toBe(true);
    expect(score.week_count).toBe(2);
    expect(score.next_reward).toBe(2);
    expect(score.sparkline.slice(-3)).toEqual([5, 13, 20]);
    expect(score.sparkline).toHaveLength(10);
  });

  it('takes 15 % off for a finished week that misses the goal and resets the base', () => {
    const days = ['2026-09-28', '2026-09-29', '2026-10-12'];
    const score = scoreHabit(days, 2, '2026-09-28', '2026-10-15');
    expect(score.points).toBe(4); // 5, then -ceil(5 * 15 %) = -1; this week is not over yet.
    expect(score.last_week_penalty).toBe(1);
    expect(score.streak).toBe(0);
    expect(score.next_reward).toBe(5);
    expect(score.minimum_met).toBe(false);
  });

  it('edits, archives and deletes habits through the API', async () => {
    const token = await deviceToken();
    const { id } = (await (await json('/api/habits', token, { method: 'POST', body: JSON.stringify({ name: 'Leer', weekly_goal: 3 }) })).json()) as any;
    const edited = (await (await json(`/api/habits/${id}`, token, { method: 'PATCH', body: JSON.stringify({ name: ' Leer 20 min ', weekly_goal: 4 }) })).json()) as any;
    expect(edited).toMatchObject({ id, name: 'Leer 20 min', weekly_goal: 4 });
    expect((await json(`/api/habits/${id}`, token, { method: 'PATCH', body: JSON.stringify({ weekly_goal: 9 }) })).status).toBe(400);

    await json(`/api/habits/${id}/check`, token, { method: 'POST', body: JSON.stringify({ day: '2026-10-13' }) });
    const listed = (await (await json('/api/habits?day=2026-10-15&weeks=12', token)).json()) as any[];
    const habit = listed.find((item) => item.id === id);
    expect(habit).toMatchObject({ week_count: 1, points: 0, next_reward: 5 });
    expect(habit.sparkline).toHaveLength(12);

    await json(`/api/habits/${id}`, token, { method: 'PATCH', body: JSON.stringify({ archived: true }) });
    expect(((await (await json('/api/habits', token)).json()) as any[]).some((item) => item.id === id)).toBe(false);
    expect((await json(`/api/habits/${id}`, token, { method: 'DELETE' })).status).toBe(204);
    expect((await json(`/api/habits/${id}`, token, { method: 'PATCH', body: JSON.stringify({ name: 'x' }) })).status).toBe(404);
  });
});

describe('goals and counter entries', () => {
  it('changes goals and lists the entries of a day', async () => {
    const token = await deviceToken();
    const goals = (await (await json('/api/goals', token, { method: 'PUT', body: JSON.stringify({ calorie_goal: 2300 }) })).json()) as any;
    expect(goals).toMatchObject({ calorie_goal: 2300, protein_goal: 120 });
    expect((await json('/api/goals', token, { method: 'PUT', body: JSON.stringify({ protein_goal: -1 }) })).status).toBe(400);
    expect((await json('/api/goals', token, { method: 'PUT', body: JSON.stringify({}) })).status).toBe(400);
    const day = (await (await json('/api/day?day=2026-10-20', token)).json()) as any;
    expect(day.counters.calories.goal).toBe(2300);

    await json('/api/counters', token, { method: 'POST', body: JSON.stringify({ kind: 'protein', amount: 25, day: '2026-10-20' }) });
    await json('/api/counters', token, { method: 'POST', body: JSON.stringify({ kind: 'calories', amount: 500, day: '2026-10-20' }) });
    await json('/api/counters', token, { method: 'POST', body: JSON.stringify({ kind: 'calories', amount: -500, day: '2026-10-20' }) });
    const all = (await (await json('/api/counters?day=2026-10-20', token)).json()) as any;
    expect(all.entries.map((entry: any) => entry.amount)).toEqual([25, 500, -500]);
    const calories = (await (await json('/api/counters?day=2026-10-20&kind=calories', token)).json()) as any;
    expect(calories.entries).toHaveLength(2);
    await json('/api/goals', token, { method: 'PUT', body: JSON.stringify({ calorie_goal: 2450 }) });
  });
});

describe('plan range', () => {
  it('lists tasks planned between two days', async () => {
    const token = await deviceToken();
    for (const [title, plan_date] of [['a', '2027-01-04'], ['b', '2027-01-06'], ['c', '2027-01-12']]) {
      await json('/api/tasks', token, { method: 'POST', body: JSON.stringify({ title, plan_date }) });
    }
    const week = (await (await json('/api/tasks?plan_from=2027-01-04&plan_to=2027-01-10', token)).json()) as any[];
    expect(week.map((task) => task.title)).toEqual(['a', 'b']);
  });
});

describe('web', () => {
  it('sends visitors without a session to Google sign-in', async () => {
    const { call } = await import('./helpers');
    const response = await call('/js/app.js');
    expect(response.status).toBe(302);
    expect(response.headers.get('Location')).toContain('/auth/login?next=');
  });
});
