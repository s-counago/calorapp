import { env } from 'cloudflare:test';
import { exports } from 'cloudflare:workers';
import { SignJWT, exportJWK, generateKeyPair } from 'jose';
import { vi } from 'vitest';
import { createDevice, sessionCookie, type Env } from '../src/auth';

export const BASE = 'https://pausa.test';
export const OWNER = 'owner@example.com';

export function call(path: string, init: RequestInit = {}): Promise<Response> {
  return exports.default.fetch(new Request(new URL(path, BASE), { redirect: 'manual', ...init }));
}

export async function deviceToken(): Promise<string> {
  return (await createDevice(env as unknown as Env, 'test phone')).token;
}

export async function json(path: string, token: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${token}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  return call(path, { ...init, headers });
}

/** The `name=value` part of the session cookie for the allowed account. */
export async function sessionHeader(email = OWNER): Promise<string> {
  return (await sessionCookie(env as unknown as Env, email)).split(';')[0];
}

// A fake Google: its signing key, its JWKS endpoint and its token endpoint.

const keys = await generateKeyPair('RS256', { extractable: true });
const jwk = { ...(await exportJWK(keys.publicKey)), kid: 'test-key', alg: 'RS256', use: 'sig' };

export async function googleIdToken(claims: Record<string, unknown> = {}, audience = 'web-client.apps.googleusercontent.com') {
  return new SignJWT({ email: OWNER, email_verified: true, ...claims })
    .setProtectedHeader({ alg: 'RS256', kid: 'test-key' })
    .setIssuer('https://accounts.google.com')
    .setAudience(audience)
    .setSubject('google-user-1')
    .setIssuedAt()
    .setExpirationTime('5m')
    .sign(keys.privateKey);
}

/** Routes the Worker's calls to Google to the fake; `idToken` is what the token endpoint returns. */
export function mockGoogle(idToken?: string) {
  const real = globalThis.fetch;
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input instanceof Request ? input.url : String(input);
    if (url === 'https://www.googleapis.com/oauth2/v3/certs') return Response.json({ keys: [jwk] });
    if (url === 'https://oauth2.googleapis.com/token') {
      return idToken ? Response.json({ id_token: idToken, access_token: 'x' }) : Response.json({ error: 'invalid_grant' }, { status: 400 });
    }
    return real(input, init);
  });
}
