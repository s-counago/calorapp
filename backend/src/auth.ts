// Sign-in is Google only, and only the address in ALLOWED_EMAIL gets in.
// The web keeps a signed session cookie; the Android app trades a Google ID token
// for a long-lived device token, so it keeps syncing after hours without coverage.

import { createRemoteJWKSet, errors as joseErrors, jwtVerify, type JWTPayload } from 'jose';

export interface Env {
  DB: D1Database;
  OAUTH_KV: KVNamespace;
  ASSETS?: Fetcher;
  OAUTH_PROVIDER: import('@cloudflare/workers-oauth-provider').OAuthHelpers;
  TIME_ZONE: string;
  /** Where the Worker is served, e.g. https://pausa.example.workers.dev */
  PUBLIC_URL: string;
  GOOGLE_CLIENT_ID: string;
  GOOGLE_CLIENT_SECRET: string;
  GOOGLE_ANDROID_AUDIENCES?: string;
  ALLOWED_EMAIL: string;
  SESSION_SECRET: string;
}

export type Principal = { kind: 'session'; email: string } | { kind: 'device'; deviceId: string };

export const SESSION_COOKIE = '__Host-pausa_session';
export const LOGIN_COOKIE = '__Host-pausa_login';
const SESSION_DAYS = 30;
const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];
const GOOGLE_AUTHORIZE = 'https://accounts.google.com/o/oauth2/v2/auth';
const GOOGLE_TOKEN = 'https://oauth2.googleapis.com/token';
const GOOGLE_CERTS = new URL('https://www.googleapis.com/oauth2/v3/certs');

let jwks: ReturnType<typeof createRemoteJWKSet> | undefined;

export type AuthErrorCode =
  | 'invalid_token'
  | 'token_expired'
  | 'token_not_yet_valid'
  | 'wrong_audience'
  | 'wrong_issuer'
  | 'email_unverified'
  | 'account_not_allowed'
  | 'google_exchange_failed';

/** A sign-in failure with a precise reason, so logs say why instead of a bare 401/403. */
export class AuthError extends Error {
  constructor(message: string, readonly code: AuthErrorCode) {
    super(message);
    this.name = 'AuthError';
  }
}

function describeJoseError(caught: unknown): AuthError {
  if (caught instanceof joseErrors.JWTExpired) return new AuthError('Google token expired; check the phone clock if this repeats', 'token_expired');
  if (caught instanceof joseErrors.JWTClaimValidationFailed) {
    if (caught.claim === 'aud') return new AuthError('Google token was issued for a different client ID', 'wrong_audience');
    if (caught.claim === 'iss') return new AuthError('Token was not issued by Google', 'wrong_issuer');
    if (caught.claim === 'iat' || caught.claim === 'nbf') return new AuthError('Google token is not valid yet; check the device clock', 'token_not_yet_valid');
  }
  return new AuthError('Google token could not be verified', 'invalid_token');
}

// Encoding helpers ----------------------------------------------------------------

const encoder = new TextEncoder();

export function base64url(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = '';
  for (const byte of view) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64url(value: string): Uint8Array {
  const binary = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

export function randomToken(bytes = 32): string {
  return base64url(crypto.getRandomValues(new Uint8Array(bytes)));
}

export async function sha256(value: string): Promise<string> {
  return base64url(await crypto.subtle.digest('SHA-256', encoder.encode(value)));
}

async function hmacKey(secret: string) {
  return crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}

/** `payload.signature`, both base64url. */
export async function sign(secret: string, payload: object): Promise<string> {
  const body = base64url(encoder.encode(JSON.stringify(payload)));
  const signature = await crypto.subtle.sign('HMAC', await hmacKey(secret), encoder.encode(body));
  return `${body}.${base64url(signature)}`;
}

export async function unsign<T>(secret: string, value: string | undefined): Promise<T | null> {
  if (!value) return null;
  const [body, signature] = value.split('.');
  if (!body || !signature) return null;
  try {
    const valid = await crypto.subtle.verify('HMAC', await hmacKey(secret), fromBase64url(signature), encoder.encode(body));
    if (!valid) return null;
    return JSON.parse(new TextDecoder().decode(fromBase64url(body))) as T;
  } catch {
    return null;
  }
}

export function readCookie(request: Request, name: string): string | undefined {
  const header = request.headers.get('Cookie') ?? '';
  for (const part of header.split(';')) {
    const [key, ...rest] = part.trim().split('=');
    if (key === name) return rest.join('=');
  }
  return undefined;
}

export function cookie(name: string, value: string, maxAgeSeconds: number): string {
  return `${name}=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=${maxAgeSeconds}`;
}

// Google --------------------------------------------------------------------------

export function isAllowed(env: Env, email: unknown): boolean {
  return typeof email === 'string' && email.trim().toLowerCase() === env.ALLOWED_EMAIL.trim().toLowerCase();
}

/** Verifies a Google ID token and that it belongs to the one allowed account. */
export async function verifyGoogleIdToken(env: Env, idToken: string, audiences: string[]): Promise<JWTPayload & { email: string }> {
  jwks ??= createRemoteJWKSet(GOOGLE_CERTS);
  let payload: JWTPayload;
  try {
    ({ payload } = await jwtVerify(idToken, jwks, { issuer: GOOGLE_ISSUERS, audience: audiences }));
  } catch (caught) {
    throw describeJoseError(caught);
  }
  if (payload.email_verified !== true) throw new AuthError('Google account email is not verified', 'email_unverified');
  if (!isAllowed(env, payload.email)) throw new AuthError('This Google account is not allowed', 'account_not_allowed');
  return payload as JWTPayload & { email: string };
}

export function androidAudiences(env: Env): string[] {
  const extra = (env.GOOGLE_ANDROID_AUDIENCES ?? '').split(',').map((value) => value.trim()).filter(Boolean);
  return [env.GOOGLE_CLIENT_ID, ...extra];
}

export async function googleAuthorizeUrl(env: Env, redirectUri: string, state: string, verifier: string): Promise<string> {
  const url = new URL(GOOGLE_AUTHORIZE);
  url.searchParams.set('client_id', env.GOOGLE_CLIENT_ID);
  url.searchParams.set('redirect_uri', redirectUri);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', 'openid email');
  url.searchParams.set('state', state);
  url.searchParams.set('code_challenge', base64url(await crypto.subtle.digest('SHA-256', encoder.encode(verifier))));
  url.searchParams.set('code_challenge_method', 'S256');
  url.searchParams.set('login_hint', env.ALLOWED_EMAIL);
  url.searchParams.set('prompt', 'select_account');
  return url.toString();
}

export async function exchangeGoogleCode(env: Env, code: string, redirectUri: string, verifier: string) {
  const response = await fetch(GOOGLE_TOKEN, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      code,
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: redirectUri,
      grant_type: 'authorization_code',
      code_verifier: verifier,
    }),
  });
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new AuthError(`Google rejected the sign-in code (${response.status} ${detail.slice(0, 200)})`, 'google_exchange_failed');
  }
  const body = (await response.json()) as { id_token?: string };
  if (!body.id_token) throw new AuthError('Google returned no ID token', 'google_exchange_failed');
  return verifyGoogleIdToken(env, body.id_token, [env.GOOGLE_CLIENT_ID]);
}

// Sessions and devices ------------------------------------------------------------

export async function sessionCookie(env: Env, email: string): Promise<string> {
  const exp = Date.now() + SESSION_DAYS * 86400_000;
  return cookie(SESSION_COOKIE, await sign(env.SESSION_SECRET, { email, exp }), SESSION_DAYS * 86400);
}

export async function sessionEmail(env: Env, request: Request): Promise<string | null> {
  const session = await unsign<{ email: string; exp: number }>(env.SESSION_SECRET, readCookie(request, SESSION_COOKIE));
  if (!session || session.exp < Date.now() || !isAllowed(env, session.email)) return null;
  return session.email;
}

export async function createDevice(env: Env, name: string): Promise<{ id: string; token: string }> {
  const id = crypto.randomUUID();
  const token = `pd_${randomToken()}`;
  await env.DB.prepare(`INSERT INTO devices (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)`)
    .bind(id, name.slice(0, 100) || 'Android', await sha256(token), Date.now())
    .run();
  return { id, token };
}

export async function deviceFromToken(env: Env, token: string): Promise<string | null> {
  if (!token.startsWith('pd_')) return null;
  const row = await env.DB.prepare(`SELECT id FROM devices WHERE token_hash = ? AND revoked_at IS NULL`).bind(await sha256(token)).first<{ id: string }>();
  if (!row) return null;
  await env.DB.prepare(`UPDATE devices SET last_seen_at = ? WHERE id = ?`).bind(Date.now(), row.id).run();
  return row.id;
}

export async function authenticate(env: Env, request: Request): Promise<Principal | null> {
  const header = request.headers.get('Authorization');
  if (header?.startsWith('Bearer ')) {
    const deviceId = await deviceFromToken(env, header.slice(7).trim());
    return deviceId ? { kind: 'device', deviceId } : null;
  }
  const email = await sessionEmail(env, request);
  return email ? { kind: 'session', email } : null;
}

/** Only same-site relative paths, so the login can't be used as an open redirect. */
export function safeNext(value: string | null | undefined): string {
  return value && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\') ? value : '/';
}
