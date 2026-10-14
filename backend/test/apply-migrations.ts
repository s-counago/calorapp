import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeEach } from 'vitest';

await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);

// Storage is shared by the tests of a file, so each test starts from an empty database.
beforeEach(async () => {
  await env.DB.batch(
    ['tasks', 'habits', 'habit_checks', 'metric_entries', 'settings', 'devices', 'change_log', 'client_logs']
      .map((table) => env.DB.prepare(`DELETE FROM ${table}`))
      .concat(env.DB.prepare(`UPDATE meta SET value = 0 WHERE key = 'seq'`)),
  );
});
