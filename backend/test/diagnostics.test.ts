import { describe, expect, it } from 'vitest';
import { MCP_TOOLS } from '../src/mcp';
import { call, deviceToken, json, sessionHeader } from './helpers';
import readme from '../README.md?raw';
import claudeMd from '../../CLAUDE.md?raw';

describe('diagnostics', () => {
  it('stores app logs and returns them filtered by level', async () => {
    const token = await deviceToken();
    const sent = await json('/api/logs', token, {
      method: 'POST',
      body: JSON.stringify({
        app_version: '7.0-debug',
        entries: [
          { at: 1000, level: 'info', tag: 'sync', message: 'pull ok', data: { changes: 3 } },
          { at: 2000, level: 'error', tag: 'sync', message: 'push failed: timeout' },
        ],
      }),
    });
    expect(sent.status).toBe(200);

    const errors = (await (await json('/api/logs?level=warn', token)).json()) as any[];
    expect(errors.map((entry) => entry.message)).toEqual(['push failed: timeout']);
    const all = (await (await json('/api/logs?tag=sync', token)).json()) as any[];
    expect(all[1].data).toEqual({ changes: 3 });

    const status = (await (await json('/api/status', token)).json()) as any;
    expect(status.devices[0]).toMatchObject({ app_version: '7.0-debug', last_error_at: 2000 });
    expect(status.client_errors_last_24h).toBe(0); // `at` 2000 is long ago
  });

  it('tracks how far each device is behind and who changed a record', async () => {
    const token = await deviceToken();
    await json('/api/sync', token, {
      method: 'POST',
      body: JSON.stringify({ changes: [{ table: 'tasks', id: 't1', updated_at: 1, deleted: false, data: { title: 'A', status: 'todo', created_at: 1 } }] }),
    });
    await json('/api/sync?since=0', token);
    await call('/api/tasks/t1', {
      method: 'PATCH',
      headers: { Cookie: await sessionHeader(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: 'B' }),
    });

    const status = (await (await json('/api/status', token)).json()) as any;
    expect(status.server_seq).toBe(2);
    expect(status.devices[0]).toMatchObject({ last_pull_cursor: 1, behind_by: 1, last_push_count: 1 });
    expect(status.records.tasks).toEqual({ live: 1, deleted: 0 });

    const history = (await (await json('/api/history/tasks/t1', token)).json()) as any[];
    expect(history.map((entry) => [entry.data.title, entry.actor.split(':')[0]])).toEqual([['A', 'device'], ['B', 'web']]);
  });

  it('tags every response with a request id and explains auth failures', async () => {
    const response = await json('/api/tasks', 'pd_revoked');
    expect(response.status).toBe(401);
    const body = (await response.json()) as any;
    expect(body.code).toBe('invalid_device_token');
    expect(body.request_id).toBe(response.headers.get('X-Request-Id'));

    const traced = await call('/health', { headers: { 'X-Request-Id': 'phone-abc-12345' } });
    expect(traced.headers.get('X-Request-Id')).toBe('phone-abc-12345');
  });

  it('keeps the MCP tools described and documented', () => {
    for (const tool of MCP_TOOLS) {
      expect(tool.description.length, tool.name).toBeGreaterThan(20);
      expect(tool.inputSchema.type, tool.name).toBe('object');
      expect(readme, `README.md must list the MCP tool ${tool.name}`).toContain(`\`${tool.name}\``);
    }
    expect(claudeMd).toContain('backend');
  });
});
