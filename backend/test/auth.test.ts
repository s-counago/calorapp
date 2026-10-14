import { afterEach, describe, expect, it, vi } from 'vitest';
import { OWNER, call, googleIdToken, json, mockGoogle } from './helpers';

afterEach(() => vi.restoreAllMocks());

function cookiesOf(response: Response): string[] {
  return response.headers.getSetCookie();
}

describe('android sign-in', () => {
  it('trades a Google ID token for a device token that works with the API', async () => {
    mockGoogle();
    const idToken = await googleIdToken({}, 'android-client.apps.googleusercontent.com');
    const response = await call('/auth/device', { method: 'POST', body: JSON.stringify({ id_token: idToken, name: 'Pixel' }) });
    expect(response.status).toBe(200);
    const { token } = (await response.json()) as { token: string };
    expect(token).toMatch(/^pd_/);
    expect((await json('/api/me', token)).status).toBe(200);

    const devices = (await (await json('/api/devices', token)).json()) as { id: string; name: string }[];
    expect(devices.map((device) => device.name)).toEqual(['Pixel']);
    await json(`/api/devices/${devices[0].id}`, token, { method: 'DELETE' });
    expect((await json('/api/me', token)).status).toBe(401);
  });

  it('refuses other accounts, unverified emails and tokens for other apps', async () => {
    mockGoogle();
    const attempts = [
      await googleIdToken({ email: 'intruso@example.com' }),
      await googleIdToken({ email_verified: false }),
      await googleIdToken({}, 'someone-elses-app.apps.googleusercontent.com'),
      'not-a-jwt',
    ];
    for (const idToken of attempts) {
      const response = await call('/auth/device', { method: 'POST', body: JSON.stringify({ id_token: idToken }) });
      expect(response.status).toBe(403);
    }
  });
});

describe('web sign-in', () => {
  it('signs the owner in through Google and back to the page they asked for', async () => {
    const login = await call('/auth/login?next=/tareas');
    expect(login.status).toBe(302);
    const google = new URL(login.headers.get('Location')!);
    expect(google.origin).toBe('https://accounts.google.com');
    expect(google.searchParams.get('code_challenge_method')).toBe('S256');
    const pending = cookiesOf(login)[0].split(';')[0];

    mockGoogle(await googleIdToken());
    const callback = await call(`/auth/callback?code=abc&state=${google.searchParams.get('state')}`, { headers: { Cookie: pending } });
    expect(callback.status).toBe(302);
    expect(callback.headers.get('Location')).toBe('/tareas');
    const session = cookiesOf(callback).find((value) => value.startsWith('__Host-pausa_session='))!.split(';')[0];

    const me = (await (await call('/api/me', { headers: { Cookie: session } })).json()) as any;
    expect(me.principal).toEqual({ kind: 'session', email: OWNER });
  });

  it('rejects a callback with the wrong state or a different Google account', async () => {
    const login = await call('/auth/login');
    const pending = cookiesOf(login)[0].split(';')[0];
    const state = new URL(login.headers.get('Location')!).searchParams.get('state');

    expect((await call('/auth/callback?code=abc&state=forged', { headers: { Cookie: pending } })).status).toBe(400);

    mockGoogle(await googleIdToken({ email: 'intruso@example.com' }));
    const callback = await call(`/auth/callback?code=abc&state=${state}`, { headers: { Cookie: pending } });
    expect(callback.status).toBe(403);
    expect(cookiesOf(callback).some((value) => value.startsWith('__Host-pausa_session='))).toBe(false);
  });

  it('never redirects off-site after login', async () => {
    const login = await call('/auth/login?next=//evil.example');
    const pending = cookiesOf(login)[0].split(';')[0];
    const state = new URL(login.headers.get('Location')!).searchParams.get('state');
    mockGoogle(await googleIdToken());
    const callback = await call(`/auth/callback?code=abc&state=${state}`, { headers: { Cookie: pending } });
    expect(callback.headers.get('Location')).toBe('/');
  });

  it('sends visitors without a session to the login', async () => {
    const response = await call('/');
    expect(response.status).toBe(302);
    expect(response.headers.get('Location')).toBe('/auth/login?next=%2F');
  });
});
