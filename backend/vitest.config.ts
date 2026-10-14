import path from 'node:path';
import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [
    cloudflareTest(async () => ({
      wrangler: { configPath: './wrangler.jsonc' },
      miniflare: {
        bindings: {
          TEST_MIGRATIONS: await readD1Migrations(path.join(import.meta.dirname, 'migrations')),
          PUBLIC_URL: 'https://pausa.test',
          ALLOWED_EMAIL: 'owner@example.com',
          GOOGLE_CLIENT_ID: 'web-client.apps.googleusercontent.com',
          GOOGLE_CLIENT_SECRET: 'test-secret',
          GOOGLE_ANDROID_AUDIENCES: 'android-client.apps.googleusercontent.com',
          SESSION_SECRET: 'test-session-secret',
        },
      },
    })),
  ],
  test: {
    setupFiles: ['./test/apply-migrations.ts'],
  },
});
