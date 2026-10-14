// Pausa backend: one Worker serving the web app, the sync API for Android and an MCP
// server for agents. /mcp is protected by OAuth (agents); everything else goes to the
// Hono app, which handles Google sign-in and the cookie- or device-token-protected API.

import { OAuthProvider } from '@cloudflare/workers-oauth-provider';
import { app } from './app';
import type { Env } from './auth';
import { handleMcp } from './mcp';
import { annotate, errorFields, log, runWithRequest } from './log';

let provider: OAuthProvider<Env> | undefined;

function oauthProvider(env: Env): OAuthProvider<Env> {
  // The resource URL depends on where the Worker is deployed, so the provider is built on first use.
  const origin = env.PUBLIC_URL.replace(/\/$/, '');
  provider ??= new OAuthProvider<Env>({
    apiRoute: '/mcp',
    apiHandler: {
      fetch: (request, workerEnv, ctx) => {
        const auth = (ctx as ExecutionContext & { auth?: { clientId?: string } }).auth;
        annotate({ actor: `mcp:${auth?.clientId ?? 'unknown'}`, source: 'mcp' });
        return handleMcp(request, workerEnv);
      },
    },
    defaultHandler: { fetch: (request, workerEnv, ctx) => app.fetch(request, workerEnv, ctx) },
    authorizeEndpoint: '/authorize',
    tokenEndpoint: '/oauth/token',
    clientRegistrationEndpoint: '/oauth/register',
    clientIdMetadataDocumentEnabled: true,
    scopesSupported: ['pausa'],
    resourceMetadata: {
      resource: `${origin}/mcp`,
      authorization_servers: [origin],
      scopes_supported: ['pausa'],
      resource_name: 'Pausa',
    },
    accessTokenTTL: 3600,
  });
  return provider;
}

export default {
  fetch(request: Request, env: Env, ctx: ExecutionContext) {
    // Clients may send their own X-Request-Id so one operation can be followed from the phone to here.
    const incoming = request.headers.get('X-Request-Id');
    const id = incoming && /^[A-Za-z0-9_-]{8,64}$/.test(incoming) ? incoming : crypto.randomUUID();
    return runWithRequest(id, async () => {
      const started = Date.now();
      const url = new URL(request.url);
      let response: Response;
      try {
        response = await oauthProvider(env).fetch(request, env, ctx);
      } catch (caught) {
        log('error', 'unhandled', { method: request.method, path: url.pathname, ...errorFields(caught) });
        response = Response.json({ error: 'Internal error', request_id: id }, { status: 500 });
      }
      log(response.status >= 500 ? 'error' : response.status >= 400 ? 'warn' : 'info', 'request', {
        method: request.method,
        path: url.pathname,
        status: response.status,
        ms: Date.now() - started,
        user_agent: request.headers.get('User-Agent')?.slice(0, 120),
      });
      const headers = new Headers(response.headers);
      headers.set('X-Request-Id', id);
      return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
    });
  },
} satisfies ExportedHandler<Env>;
