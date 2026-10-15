import { describe, expect, it } from 'vitest';
import { base64url } from '../src/auth';
import { call, sessionHeader } from './helpers';

const REDIRECT = 'https://agent.example/callback';

/** Registers a client, approves it on the consent page and returns an access token. */
async function authorizeAgent(): Promise<string> {
  const registration = await call('/oauth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ client_name: 'Test agent', redirect_uris: [REDIRECT], token_endpoint_auth_method: 'none' }),
  });
  expect(registration.status).toBe(201);
  const { client_id } = (await registration.json()) as { client_id: string };

  const verifier = 'v'.repeat(64);
  const challenge = base64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  const query = new URLSearchParams({
    response_type: 'code',
    client_id,
    redirect_uri: REDIRECT,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state: 'xyz',
    scope: 'pausa',
    resource: 'https://pausa.test/mcp',
  });
  const session = await sessionHeader();

  const consent = await call(`/authorize?${query}`, { headers: { Cookie: session } });
  expect(consent.status).toBe(200);
  const html = await consent.text();
  expect(html).toContain('Test agent');
  const handle = /name="handle" value="([^"]+)"/.exec(html)![1];
  const binding = consent.headers.getSetCookie().map((value) => value.split(';')[0]);

  const approve = await call('/authorize', {
    method: 'POST',
    headers: { Cookie: [session, ...binding].join('; '), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ handle, decision: 'approve' }),
  });
  expect(approve.status).toBe(302);
  const back = new URL(approve.headers.get('Location')!);
  expect(back.origin + back.pathname).toBe(REDIRECT);
  expect(back.searchParams.get('state')).toBe('xyz');

  const token = await call('/oauth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code: back.searchParams.get('code')!,
      redirect_uri: REDIRECT,
      client_id,
      code_verifier: verifier,
      resource: 'https://pausa.test/mcp',
    }),
  });
  expect(token.status).toBe(200);
  return ((await token.json()) as { access_token: string }).access_token;
}

let nextId = 1;
async function rpc(token: string, method: string, params: Record<string, unknown> = {}) {
  const response = await call('/mcp', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: nextId++, method, params }),
  });
  expect(response.status).toBe(200);
  return (await response.json()) as any;
}

async function tool(token: string, name: string, args: Record<string, unknown> = {}) {
  const { result } = await rpc(token, 'tools/call', { name, arguments: args });
  return result;
}

describe('mcp', () => {
  it('challenges unauthenticated clients towards the OAuth metadata', async () => {
    const response = await call('/mcp', { method: 'POST', body: '{}' });
    expect(response.status).toBe(401);
    expect(response.headers.get('WWW-Authenticate')).toContain('resource_metadata');
    const metadata = (await (await call('/.well-known/oauth-protected-resource/mcp')).json()) as any;
    expect(metadata.resource).toBe('https://pausa.test/mcp');
  });

  it('sends the consent page to Google sign-in when there is no session', async () => {
    const response = await call('/authorize?client_id=x');
    expect(response.status).toBe(302);
    expect(response.headers.get('Location')).toMatch(/^\/auth\/login\?next=/);
  });

  it('lets an approved agent manage tasks, habits and counters', async () => {
    const token = await authorizeAgent();

    const init = await rpc(token, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 't', version: '1' } });
    expect(init.result.protocolVersion).toBe('2025-06-18');
    expect(init.result.serverInfo.name).toBe('pausa');

    const { result: list } = await rpc(token, 'tools/list');
    expect(list.tools.map((entry: any) => entry.name)).toEqual(
      expect.arrayContaining(['list_tasks', 'create_task', 'complete_task', 'check_habit', 'log_counter', 'day_summary', 'weekly_review', 'health_days']),
    );

    const created = await tool(token, 'create_task', { title: 'Revisar presupuesto', plan_date: '2026-10-14', plan_slot: 'evening' });
    expect(created.isError).toBe(false);
    const taskId = created.structuredContent.id;
    await tool(token, 'complete_task', { id: taskId });
    const tasks = await tool(token, 'list_tasks', { status: 'done' });
    expect(tasks.structuredContent.items.map((task: any) => task.id)).toEqual([taskId]);

    const habit = await tool(token, 'create_habit', { name: 'Meditar', weekly_goal: 3 });
    await tool(token, 'check_habit', { habit_id: habit.structuredContent.id, day: '2026-10-14' });
    const counters = await tool(token, 'log_counter', { kind: 'protein', amount: 35, day: '2026-10-14' });
    expect(counters.structuredContent.protein.total).toBe(35);
    const fixed = await tool(token, 'set_counter', { kind: 'protein', total: 120, day: '2026-10-14' });
    expect(fixed.structuredContent).toMatchObject({ day: '2026-10-14', previous: 35, protein: { total: 120 } });

    const summary = await tool(token, 'day_summary', { day: '2026-10-14' });
    expect(summary.structuredContent.habits).toEqual([expect.objectContaining({ name: 'Meditar', done: true })]);
    expect(summary.structuredContent.plan.evening.map((task: any) => task.id)).toEqual([taskId]);

    const imported = await tool(token, 'import_tasks', { items: [{ title: 'Idea', board: 'stack', source: 'notion', source_id: 'p1' }] });
    expect(imported.structuredContent).toMatchObject({ created: 1, skipped: 0 });
    await tool(token, 'set_done_total', { total: 10 });
    const board = await tool(token, 'board_summary');
    expect(board.structuredContent).toEqual({ columns: { stack: 1, priority: 0, vault: 0, failed: 0 }, done_total: 10 });

    const month = await tool(token, 'weekly_review', { day: '2026-10-14', period: 'month' });
    expect(month.structuredContent).toMatchObject({ period: 'month', start: '2026-10-01', end: '2026-10-31' });

    const health = await tool(token, 'health_days', { from: '2026-10-08', to: '2026-10-14' });
    expect(health.structuredContent).toMatchObject({ from: '2026-10-08', to: '2026-10-14', days_with_data: 0, per_day: [] });

    const features = await tool(token, 'list_features');
    expect(features.structuredContent.items).toEqual(expect.arrayContaining([expect.objectContaining({ key: 'review_zoom', enabled: false })]));
    const switched = await tool(token, 'set_feature', { key: 'review_zoom', enabled: true });
    expect(switched.structuredContent).toMatchObject({ key: 'review_zoom', enabled: true });

    const bad = await tool(token, 'update_task', { id: 'missing', title: 'x' });
    expect(bad.isError).toBe(true);
    const unknown = await rpc(token, 'tools/call', { name: 'drop_database' });
    expect(unknown.error.code).toBe(-32602);
  });
});
