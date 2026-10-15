import { describe, expect, it } from 'vitest';
import { deviceToken, json } from './helpers';

describe('board', () => {
  it('imports Notion tasks into columns once and counts completed board tasks', async () => {
    const token = await deviceToken();
    const items = [
      { title: 'Leer paper', board: 'stack', source: 'notion', source_id: 'n1', created_at: 1_700_000_000_000 },
      { title: 'Arreglar bici', notes: 'cadena', board: 'priority', source: 'notion', source_id: 'n2' },
      { title: 'gym 03-10', board: 'failed', source: 'notion', source_id: 'n3' },
    ];
    const first = (await (await json('/api/tasks/import', token, { method: 'POST', body: JSON.stringify({ items }) })).json()) as any;
    expect(first).toMatchObject({ created: 3, skipped: 0 });
    const again = (await (await json('/api/tasks/import', token, { method: 'POST', body: JSON.stringify({ items }) })).json()) as any;
    expect(again).toMatchObject({ created: 0, skipped: 3 });

    const stack = (await (await json('/api/tasks?board=stack', token)).json()) as any[];
    expect(stack).toEqual([expect.objectContaining({ title: 'Leer paper', board: 'stack', created_at: 1_700_000_000_000 })]);
    await json('/api/tasks', token, { method: 'POST', body: JSON.stringify({ title: 'Tarea normal' }) });
    const off = (await (await json('/api/tasks?board=none', token)).json()) as any[];
    expect(off.map((task) => task.title)).toEqual(['Tarea normal']);

    const set = (await (await json('/api/board/done', token, { method: 'PUT', body: JSON.stringify({ total: 1861 }) })).json()) as any;
    expect(set).toEqual({ previous: 0, done_total: 1861 });

    const priority = (await (await json('/api/tasks?board=priority', token)).json()) as any[];
    await json(`/api/tasks/${priority[0].id}`, token, { method: 'PATCH', body: JSON.stringify({ status: 'done' }) });
    const board = (await (await json('/api/board', token)).json()) as any;
    expect(board).toEqual({ columns: { stack: 1, priority: 0, vault: 0, failed: 1 }, done_total: 1862 });

    // Fixing the total keeps counting later completions on top of it.
    await json('/api/board/done', token, { method: 'PUT', body: JSON.stringify({ total: 1900 }) });
    await json(`/api/tasks/${stack[0].id}`, token, { method: 'PATCH', body: JSON.stringify({ status: 'done' }) });
    expect(((await (await json('/api/board', token)).json()) as any).done_total).toBe(1901);

    const bad = await json('/api/tasks/import', token, { method: 'POST', body: JSON.stringify({ items: [{ title: 'x', board: 'later', source: 'notion', source_id: 'n9' }] }) });
    expect(bad.status).toBe(400);
  });
});
