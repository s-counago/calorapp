import { describe, expect, it } from 'vitest';
import { deviceToken, json } from './helpers';

const post = (path: string, token: string, body: unknown) => json(path, token, { method: 'POST', body: JSON.stringify(body) });

describe('cajón', () => {
  it('captures, suggests and files into tasks, the library or the archive', async () => {
    const token = await deviceToken();
    const link = (await (await post('/api/captures', token, { text: 'mirar esto https://example.com/articulo luego' })).json()) as any;
    expect(link).toMatchObject({ url: 'https://example.com/articulo', status: 'new', source: 'web', suggestion: null });
    const bare = (await (await post('/api/captures', token, { url: 'https://example.com/x' })).json()) as any;
    expect(bare).toMatchObject({ text: 'https://example.com/x', kind: 'link' });
    const idea = (await (await post('/api/captures', token, { text: 'Llamar al seguro por la ventana rota' })).json()) as any;
    expect((await post('/api/captures', token, { text: '  ' })).status).toBe(400);

    let waiting = (await (await json('/api/captures', token)).json()) as any;
    expect(waiting.waiting).toBe(3);
    expect(waiting.items.map((item: any) => item.id)).toEqual([idea.id, bare.id, link.id]);

    const suggested = (await (await post(`/api/captures/${idea.id}/suggest`, token, { kind: 'task', title: 'Llamar al seguro', lane: 'priority', tags: ['Casa', 'casa '], reason: 'Es una gestión' })).json()) as any;
    expect(suggested.suggestion).toEqual({ kind: 'task', title: 'Llamar al seguro', lane: 'priority', day: null, tags: ['casa'], reason: 'Es una gestión' });
    expect(suggested.status).toBe('new');
    expect((await post(`/api/captures/${idea.id}/suggest`, token, { kind: 'banana' })).status).toBe(400);
    const unsuggested = (await (await json('/api/captures?unsuggested=true', token)).json()) as any;
    expect(unsuggested.items).toHaveLength(2);

    const filed = (await (await post(`/api/captures/${idea.id}/file`, token, { as: 'task', title: 'Llamar al seguro', lane: 'priority', day: '2026-10-16' })).json()) as any;
    expect(filed.capture).toMatchObject({ status: 'filed', task_id: filed.task.id, kind: 'task' });
    expect(filed.task).toMatchObject({ title: 'Llamar al seguro', notes: 'Llamar al seguro por la ventana rota', lane: 'priority', plan_date: '2026-10-16', source: 'capture', source_id: idea.id });

    const kept = (await (await post(`/api/captures/${link.id}/file`, token, { as: 'keep', kind: 'link', tags: ['lectura'] })).json()) as any;
    expect(kept.capture).toMatchObject({ status: 'kept', kind: 'link', tags: 'lectura' });
    await post(`/api/captures/${bare.id}/file`, token, { as: 'archive' });

    waiting = (await (await json('/api/captures', token)).json()) as any;
    expect(waiting).toEqual({ waiting: 0, items: [] });
    const library = (await (await json('/api/captures?status=kept&tag=lectura', token)).json()) as any;
    expect(library.items.map((item: any) => item.id)).toEqual([link.id]);
    expect((await post(`/api/captures/${link.id}/file`, token, { as: 'later' })).status).toBe(400);

    // Captures sync like any other table, so the phone gets them on its next pull.
    const { changes } = (await (await json('/api/sync?since=0', token)).json()) as any;
    expect(changes.filter((change: any) => change.table === 'captures')).toHaveLength(3);
  });
});
