// Pausa backend: one Worker serving the web app, the sync API for Android and an MCP
// server for agents. /mcp is protected by OAuth (agents); everything else goes to the
// Hono app, which handles Google sign-in and the cookie- or device-token-protected API.

import { OAuthProvider } from '@cloudflare/workers-oauth-provider';
import { app } from './app';
import type { Env } from './auth';
import { handleMcp } from './mcp';

let provider: OAuthProvider<Env> | undefined;

function oauthProvider(env: Env): OAuthProvider<Env> {
  // The resource URL depends on where the Worker is deployed, so the provider is built on first use.
  const origin = env.PUBLIC_URL.replace(/\/$/, '');
  provider ??= new OAuthProvider<Env>({
    apiRoute: '/mcp',
    apiHandler: { fetch: (request, workerEnv) => handleMcp(request, workerEnv) },
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
    return oauthProvider(env).fetch(request, env, ctx);
  },
} satisfies ExportedHandler<Env>;
