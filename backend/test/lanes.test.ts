import { describe, expect, it } from 'vitest';
import { deviceToken, json } from './helpers';

const post = (path: string, token: string, body: unknown) => json(path, token, { method: 'POST', body: JSON.stringify(body) });

describe('lanes and appointments', () => {
  it('reads the lane from board and following, and moves tasks between lanes', async () => {
    const token = await deviceToken();
    const inbox = (await (await post('/api/tasks', token, { title: 'Apuntada' })).json()) as any;
    expect(inbox.lane).toBe('inbox');
    const board = (await (await post('/api/tasks', token, { title: 'Del stack', board: 'stack' })).json()) as any;
    expect(board.lane).toBe('stack');
    const created = (await (await post('/api/tasks', token, { title: 'Factura', lane: 'waiting' })).json()) as any;
    expect(created).toMatchObject({ lane: 'waiting', following: true, board: null });

    const moved = (await (await post(`/api/tasks/${created.id}/move`, token, { lane: 'priority' })).json()) as any;
    expect(moved).toMatchObject({ lane: 'priority', following: false, board: 'priority' });
    const failed = (await (await post(`/api/tasks/${board.id}/move`, token, { lane: 'failed' })).json()) as any;
    expect(failed.lane).toBe('failed');
    expect((await post(`/api/tasks/${board.id}/move`, token, { lane: 'later' })).status).toBe(400);

    const priority = (await (await json('/api/tasks?lane=priority', token)).json()) as any[];
    expect(priority.map((task) => task.title)).toEqual(['Factura']);
    const inboxList = (await (await json('/api/tasks?lane=inbox', token)).json()) as any[];
    expect(inboxList.map((task) => task.title)).toEqual(['Apuntada']);
  });

  it('plans a task with an optional time, slot from the time, and clears it', async () => {
    const token = await deviceToken();
    const task = (await (await post('/api/tasks', token, { title: 'Dentista', lane: 'priority' })).json()) as any;
    const timed = (await (await post(`/api/tasks/${task.id}/plan`, token, { day: '2026-10-20', time: '18:30', minutes: 45 })).json()) as any;
    expect(timed).toMatchObject({ plan_date: '2026-10-20', plan_slot: 'afternoon', plan_time: 1110, plan_minutes: 45, lane: 'priority' });
    const anyTime = (await (await post(`/api/tasks/${task.id}/plan`, token, { day: '2026-10-21' })).json()) as any;
    expect(anyTime).toMatchObject({ plan_date: '2026-10-21', plan_slot: null, plan_time: null, plan_minutes: null });
    const cleared = (await (await post(`/api/tasks/${task.id}/plan`, token, { day: null })).json()) as any;
    expect(cleared).toMatchObject({ plan_date: null, lane: 'priority' });
    expect((await post(`/api/tasks/${task.id}/plan`, token, { day: '2026-10-21', time: '25:00' })).status).toBe(400);

    // Sync rejects times outside the day instead of storing them.
    const push = await json('/api/sync', token, {
      method: 'POST',
      body: JSON.stringify({ changes: [{ table: 'tasks', id: 'bad-time', updated_at: 1, data: { title: 'x', status: 'todo', created_at: 1, plan_time: 2000 } }] }),
    });
    expect(push.status).toBe(400);
  });

  it('turns a habit into a task that checks the habit when done, and the other way round', async () => {
    const token = await deviceToken();
    const { id: habit } = (await (await post('/api/habits', token, { name: 'Gimnasio', weekly_goal: 4 })).json()) as any;
    const task = (await (await post(`/api/habits/${habit}/task`, token, { day: '2026-10-16' })).json()) as any;
    expect(task).toMatchObject({ title: 'Gimnasio', lane: 'priority', plan_date: '2026-10-16', plan_slot: null, habit_id: habit, status: 'todo' });
    const again = (await (await post(`/api/habits/${habit}/task`, token, { day: '2026-10-16' })).json()) as any;
    expect(again.id).toBe(task.id);

    await json(`/api/tasks/${task.id}`, token, { method: 'PATCH', body: JSON.stringify({ status: 'done' }) });
    let habits = (await (await json('/api/habits?day=2026-10-16', token)).json()) as any[];
    expect(habits.find((row) => row.id === habit).done_today).toBe(true);

    await json(`/api/tasks/${task.id}`, token, { method: 'PATCH', body: JSON.stringify({ status: 'todo' }) });
    habits = (await (await json('/api/habits?day=2026-10-16', token)).json()) as any[];
    expect(habits.find((row) => row.id === habit).done_today).toBe(false);

    await post(`/api/habits/${habit}/check`, token, { day: '2026-10-16' });
    expect(((await (await json(`/api/tasks/${task.id}`, token)).json()) as any).status).toBe('done');
    await post(`/api/habits/${habit}/check`, token, { day: '2026-10-16', done: false });
    expect(((await (await json(`/api/tasks/${task.id}`, token)).json()) as any).status).toBe('todo');

    expect((await post('/api/habits/nope/task', token, {})).status).toBe(404);
  });

  it('summarises lanes, the day plan and what was left over from earlier days', async () => {
    const token = await deviceToken();
    await post('/api/tasks', token, { title: 'Bandeja' });
    await post('/api/tasks', token, { title: 'Ayer', lane: 'priority', plan_date: '2026-10-14', plan_slot: 'morning' });
    await post('/api/tasks', token, { title: 'Hoy tarde', lane: 'stack', plan_date: '2026-10-15', plan_slot: 'afternoon' });
    await post('/api/tasks', token, { title: 'Hoy cuando sea', plan_date: '2026-10-15' });
    await post('/api/tasks', token, { title: 'Esperando', lane: 'waiting' });
    const overview = (await (await json('/api/tasks/overview?day=2026-10-15', token)).json()) as any;
    expect(overview.lanes).toEqual({ inbox: 2, priority: 1, stack: 1, waiting: 1, vault: 0, failed: 0 });
    expect(overview.today.afternoon.map((task: any) => task.title)).toEqual(['Hoy tarde']);
    expect(overview.today.any_time.map((task: any) => task.title)).toEqual(['Hoy cuando sea']);
    expect(overview.left_over.map((task: any) => task.title)).toEqual(['Ayer']);
    expect(typeof overview.done_total).toBe('number');
  });
});
