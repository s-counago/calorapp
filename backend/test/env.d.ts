import type { Env as PausaEnv } from '../src/auth';

declare global {
  namespace Cloudflare {
    interface Env extends Omit<PausaEnv, 'OAUTH_PROVIDER'> {
      TEST_MIGRATIONS: import('cloudflare:test').D1Migration[];
    }
    interface GlobalProps {
      mainModule: typeof import('../src/index');
    }
  }
}
