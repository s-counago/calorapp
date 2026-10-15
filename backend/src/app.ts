// Everything except /mcp: Google sign-in, the MCP consent page, the REST + sync API
// and the web app's static files.

import { Hono, type Context } from 'hono';
import { AuthorizationError } from '@cloudflare/workers-oauth-provider';
import {
  AuthError,
  LOGIN_COOKIE,
  SESSION_COOKIE,
  androidAudiences,
  authenticate,
  cookie,
  createDevice,
  exchangeGoogleCode,
  googleAuthorizeUrl,
  randomToken,
  readCookie,
  safeNext,
  sessionCookie,
  sessionEmail,
  sign,
  unsign,
  verifyGoogleIdToken,
  type Env,
  type Principal,
} from './auth';
import { ValidationError } from './schema';
import { applyChanges, parseChanges, pullChanges, recordHistory } from './sync';
import { annotate, errorFields, log, requestId } from './log';
import { parseClientLogs, queryClientLogs, recordPull, recordPush, storeClientLogs, syncStatus } from './diagnostics';
import { isTableName } from './schema';
import {
  NotFoundError,
  boardSummary,
  createHabit,
  createTask,
  daySummary,
  deleteTask,
  getTask,
  importTasks,
  listHabits,
  updateHabit,
  deleteHabit,
  counterEntries,
  setGoals,
  goals,
  listTasks,
  currentDay,
  logMetric,
  setDoneTotal,
  setHabitCheck,
  setMetricTotal,
  updateTask,
  type ImportItem,
  review,
  healthSummary,
  listFeatures,
  setFeature,
  type ReviewPeriod,
  type MetricKind,
} from './domain';

type AppEnv = { Bindings: Env; Variables: { principal: Principal } };

export const app = new Hono<AppEnv>();

// Errors carry a machine-readable code and the request id, so a client log line can be
// matched with the server's.
app.onError((caught, c) => {
  const request_id = requestId();
  if (caught instanceof ValidationError) {
    log('warn', 'validation_failed', { path: c.req.path, error: caught.message });
    return c.json({ error: caught.message, code: 'invalid_request', request_id }, 400);
  }
  if (caught instanceof NotFoundError) return c.json({ error: caught.message, code: 'not_found', request_id }, 404);
  if (caught instanceof AuthError) {
    log('warn', 'auth_failed', { path: c.req.path, reason: caught.code, error: caught.message });
    return c.json({ error: caught.message, code: caught.code, request_id }, 403);
  }
  log('error', 'handler_failed', { path: c.req.path, ...errorFields(caught) });
  return c.json({ error: 'Internal error', code: 'internal', request_id }, 500);
});

app.get('/health', (c) => c.json({ ok: true }));

// Web sign-in ---------------------------------------------------------------------

const LOGIN_MINUTES = 10;

app.get('/auth/login', async (c) => {
  const state = randomToken();
  const verifier = randomToken(48);
  const next = safeNext(c.req.query('next'));
  const redirectUri = new URL('/auth/callback', c.req.url).toString();
  const pending = await sign(c.env.SESSION_SECRET, { state, verifier, next, exp: Date.now() + LOGIN_MINUTES * 60000 });
  c.header('Set-Cookie', cookie(LOGIN_COOKIE, pending, LOGIN_MINUTES * 60));
  return c.redirect(await googleAuthorizeUrl(c.env, redirectUri, state, verifier));
});

app.get('/auth/callback', async (c) => {
  const pending = await unsign<{ state: string; verifier: string; next: string; exp: number }>(c.env.SESSION_SECRET, readCookie(c.req.raw, LOGIN_COOKIE));
  const code = c.req.query('code');
  if (!pending || pending.exp < Date.now() || pending.state !== c.req.query('state') || !code) {
    return c.text('El inicio de sesión ha caducado. Vuelve a intentarlo.', 400);
  }
  const redirectUri = new URL('/auth/callback', c.req.url).toString();
  let email: string;
  try {
    ({ email } = await exchangeGoogleCode(c.env, code, redirectUri, pending.verifier));
  } catch (caught) {
    if (caught instanceof AuthError) {
      log('warn', 'auth_failed', { flow: 'web', reason: caught.code, error: caught.message });
      return c.text(`No se ha podido iniciar sesión: ${caught.message} (${requestId()})`, 403);
    }
    throw caught;
  }
  log('info', 'web_login', {});
  c.header('Set-Cookie', await sessionCookie(c.env, email), { append: true });
  c.header('Set-Cookie', cookie(LOGIN_COOKIE, '', 0), { append: true });
  return c.redirect(safeNext(pending.next));
});

app.post('/auth/logout', (c) => {
  c.header('Set-Cookie', cookie(SESSION_COOKIE, '', 0));
  return c.redirect('/');
});

// The Android app signs in with Google on the phone and trades the ID token for a device token.
app.post('/auth/device', async (c) => {
  const body = await c.req.json<{ id_token?: unknown; name?: unknown }>().catch(() => ({}) as { id_token?: unknown; name?: unknown });
  if (typeof body.id_token !== 'string') throw new ValidationError('id_token is required');
  await verifyGoogleIdToken(c.env, body.id_token, androidAudiences(c.env));
  const device = await createDevice(c.env, typeof body.name === 'string' ? body.name : 'Android');
  log('info', 'device_registered', { device_id: device.id });
  return c.json({ device_id: device.id, token: device.token });
});

// MCP authorization ---------------------------------------------------------------
// Agents get here through OAuth. The user signs in with the same Google session as the
// web, then approves the client on a consent page.

const escape = (value: string) => value.replace(/[&<>"']/g, (char) => `&#${char.charCodeAt(0)};`);

function page(title: string, body: string) {
  return `<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escape(title)}</title>
<style>body{font-family:Georgia,serif;background:#f6f0e4;color:#2c3326;max-width:32rem;margin:3rem auto;padding:0 1rem;line-height:1.5}
button{font:inherit;padding:.6rem 1.2rem;border-radius:999px;border:0;margin-right:.5rem;cursor:pointer}
.allow{background:#3d5a40;color:#fff}.deny{background:#e5dccb}.warn{color:#a0482c}</style>
<body>${body}</body></html>`;
}

app.get('/authorize', async (c) => {
  const email = await sessionEmail(c.env, c.req.raw);
  if (!email) {
    const url = new URL(c.req.url);
    return c.redirect(`/auth/login?next=${encodeURIComponent(url.pathname + url.search)}`);
  }
  const oauth = c.env.OAUTH_PROVIDER;
  try {
    const request = await oauth.parseAuthRequest(c.req.raw);
    const details = await oauth.describeConsent(request);
    const consent = await oauth.beginConsent(request);
    const origin = details.clientDomain
      ? `Publicada por <strong>${escape(details.clientDomain)}</strong>.`
      : 'Esta aplicación se ha registrado sola; su nombre no está verificado.';
    const html = page(
      'Autorizar acceso a Pausa',
      `<h1>¿Dar acceso a ${escape(details.clientName)}?</h1>
<p>${origin} El acceso se enviará a <strong>${escape(details.redirectHost)}</strong>.</p>
${details.redirectIsLoopback ? '<p class="warn">Esto envía el acceso a una aplicación de este ordenador. Continúa solo si acabas de iniciarlo tú.</p>' : ''}
<p>Podrá leer y modificar tus tareas, hábitos, plan del día y contadores.</p>
<form method="post">
<input type="hidden" name="handle" value="${escape(consent.handle)}">
<button class="allow" name="decision" value="approve">Permitir</button><button class="deny" name="decision" value="deny">Denegar</button>
</form>`,
    );
    return new Response(html, { headers: mergeHeaders(consent.headers, { 'Content-Type': 'text/html; charset=utf-8' }) });
  } catch (caught) {
    if (caught instanceof AuthorizationError) return c.html(page('Error', `<p>${escape(caught.message)}</p>`), 400);
    throw caught;
  }
});

app.post('/authorize', async (c) => {
  const email = await sessionEmail(c.env, c.req.raw);
  if (!email) return c.text('Inicia sesión de nuevo.', 401);
  const oauth = c.env.OAUTH_PROVIDER;
  const form = await c.req.formData();
  const handle = String(form.get('handle') ?? '');
  try {
    if (form.get('decision') !== 'approve') {
      const denied = await oauth.denyConsent(c.req.raw, handle);
      return redirect(denied.redirectTo, denied.headers);
    }
    const approved = await oauth.approveConsent(c.req.raw, handle, { scope: ['pausa'] });
    const { redirectTo } = await oauth.completeAuthorization({
      request: approved.request,
      userId: email,
      metadata: {},
      scope: approved.request.scope,
      props: { email },
    });
    return redirect(redirectTo, approved.headers);
  } catch (caught) {
    if (caught instanceof AuthorizationError) return c.html(page('Error', `<p>La autorización ha caducado. Vuelve a intentarlo desde la aplicación.</p>`), 400);
    throw caught;
  }
});

function mergeHeaders(base: Headers, extra: Record<string, string>): Headers {
  const headers = new Headers(base);
  for (const [key, value] of Object.entries(extra)) headers.set(key, value);
  return headers;
}

function redirect(location: string, headers: Headers): Response {
  return new Response(null, { status: 302, headers: mergeHeaders(headers, { Location: location }) });
}

// API -----------------------------------------------------------------------------

app.use('/api/*', async (c, next) => {
  const principal = await authenticate(c.env, c.req.raw);
  if (!principal) {
    const bearer = c.req.header('Authorization')?.startsWith('Bearer ');
    const code = bearer ? 'invalid_device_token' : 'not_signed_in';
    return c.json({ error: bearer ? 'Device token unknown or revoked' : 'Not signed in', code, request_id: requestId() }, 401);
  }
  c.set('principal', principal);
  annotate(principal.kind === 'device' ? { actor: `device:${principal.deviceId}`, source: 'android' } : { actor: 'web', source: 'web' });
  await next();
});

const today = (c: Context<AppEnv>) => currentDay(c.env.TIME_ZONE);
const dayQuery = (c: Context<AppEnv>) => {
  const day = c.req.query('day');
  if (day === undefined) return today(c);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new ValidationError('day must be YYYY-MM-DD');
  return day;
};

app.get('/api/me', (c) => c.json({ principal: c.get('principal'), today: today(c), time_zone: c.env.TIME_ZONE }));

app.get('/api/sync', async (c) => {
  const since = Number(c.req.query('since') ?? 0);
  if (!Number.isInteger(since) || since < 0) throw new ValidationError('since must be a non-negative integer');
  const result = await pullChanges(c.env.DB, since);
  const principal = c.get('principal');
  if (principal.kind === 'device') await recordPull(c.env.DB, principal.deviceId, result.cursor);
  log('info', 'sync_pull', { since, cursor: result.cursor, changes: result.changes.length, more: result.more });
  return c.json(result);
});

app.post('/api/sync', async (c) => {
  const body = await c.req.json().catch(() => null);
  const result = await applyChanges(c.env.DB, parseChanges(body));
  const principal = c.get('principal');
  if (principal.kind === 'device') await recordPush(c.env.DB, principal.deviceId, result.applied.length);
  return c.json(result);
});

// Diagnostics -----------------------------------------------------------------------

app.post('/api/logs', async (c) => {
  const { entries, appVersion } = parseClientLogs(await c.req.json().catch(() => null));
  const principal = c.get('principal');
  await storeClientLogs(c.env.DB, principal.kind === 'device' ? principal.deviceId : null, entries, appVersion);
  return c.json({ stored: entries.length });
});

app.get('/api/logs', async (c) => {
  const query = c.req.query();
  return c.json(
    await queryClientLogs(c.env.DB, {
      since: query.since ? Number(query.since) : undefined,
      level: query.level,
      tag: query.tag,
      device_id: query.device_id,
      search: query.search,
      limit: query.limit ? Number(query.limit) : undefined,
    }),
  );
});

app.get('/api/status', async (c) => c.json(await syncStatus(c.env.DB)));

app.get('/api/history/:table/:id', async (c) => {
  const table = c.req.param('table');
  if (!isTableName(table)) throw new ValidationError('Unknown table');
  return c.json(await recordHistory(c.env.DB, table, c.req.param('id')));
});

app.get('/api/tasks', async (c) => {
  const query = c.req.query();
  return c.json(
    await listTasks(c.env.DB, {
      status: query.status,
      plan_date: query.plan_date,
      plan_from: query.plan_from,
      plan_to: query.plan_to,
      project: query.project,
      due_before: query.due_before,
      following: query.following === undefined ? undefined : query.following === 'true',
      board: query.board,
      search: query.search,
      limit: query.limit ? Number(query.limit) : undefined,
    }),
  );
});
app.post('/api/tasks/import', async (c) => {
  const body = await c.req.json<{ items?: unknown }>();
  return c.json(await importTasks(c.env.DB, body.items as ImportItem[]), 201);
});
app.get('/api/board', async (c) => c.json(await boardSummary(c.env.DB)));
app.put('/api/board/done', async (c) => {
  const body = await c.req.json<{ total?: unknown }>();
  return c.json(await setDoneTotal(c.env.DB, body.total as number));
});
app.get('/api/tasks/:id', async (c) => c.json(await getTask(c.env.DB, c.req.param('id'))));
app.post('/api/tasks', async (c) => c.json(await createTask(c.env.DB, await c.req.json()), 201));
app.patch('/api/tasks/:id', async (c) => c.json(await updateTask(c.env.DB, c.req.param('id'), await c.req.json())));
app.delete('/api/tasks/:id', async (c) => {
  await deleteTask(c.env.DB, c.req.param('id'));
  return c.body(null, 204);
});

app.get('/api/habits', async (c) => {
  const weeks = c.req.query('weeks');
  return c.json(await listHabits(c.env.DB, dayQuery(c), weeks === undefined ? undefined : Number(weeks)));
});
app.post('/api/habits', async (c) => {
  const body = await c.req.json<{ name?: unknown; weekly_goal?: unknown }>();
  if (typeof body.name !== 'string' || !body.name) throw new ValidationError('name is required');
  const id = await createHabit(c.env.DB, { name: body.name, weekly_goal: body.weekly_goal as number | undefined }, today(c));
  return c.json({ id }, 201);
});
app.patch('/api/habits/:id', async (c) => c.json(await updateHabit(c.env.DB, c.req.param('id'), await c.req.json())));
app.delete('/api/habits/:id', async (c) => {
  await deleteHabit(c.env.DB, c.req.param('id'));
  return c.body(null, 204);
});
app.post('/api/habits/:id/check', async (c) => {
  const body = await c.req.json<{ day?: string; done?: boolean }>().catch(() => ({}) as { day?: string; done?: boolean });
  const day = body.day ?? today(c);
  await setHabitCheck(c.env.DB, c.req.param('id'), day, body.done !== false);
  return c.json({ ok: true });
});

app.get('/api/counters', async (c) => c.json({ day: dayQuery(c), entries: await counterEntries(c.env.DB, dayQuery(c), c.req.query('kind')) }));

app.post('/api/counters', async (c) => {
  const body = await c.req.json<{ kind?: MetricKind; amount?: unknown; day?: string }>();
  if (typeof body.amount !== 'number' || !Number.isFinite(body.amount)) throw new ValidationError('amount must be a number');
  const day = body.day ?? today(c);
  const id = await logMetric(c.env.DB, body.kind as MetricKind, body.amount, day);
  return c.json({ id }, 201);
});

app.put('/api/counters', async (c) => {
  const body = await c.req.json<{ kind?: MetricKind; total?: unknown; day?: string }>();
  const day = body.day ?? today(c);
  return c.json(await setMetricTotal(c.env.DB, body.kind as MetricKind, body.total as number, day));
});

app.get('/api/goals', async (c) => c.json(await goals(c.env.DB)));
app.put('/api/goals', async (c) => c.json(await setGoals(c.env.DB, await c.req.json())));

app.get('/api/day', async (c) => c.json(await daySummary(c.env.DB, dayQuery(c))));
app.get('/api/review', async (c) =>
  c.json(await review(c.env.DB, dayQuery(c), c.env.TIME_ZONE, (c.req.query('period') ?? 'week') as ReviewPeriod)),
);

app.get('/api/health', async (c) => c.json(await healthSummary(c.env.DB, today(c), c.req.query('from'), c.req.query('to'))));

app.get('/api/features', async (c) => c.json({ items: await listFeatures(c.env.DB) }));
app.put('/api/features/:key', async (c) => {
  const body = await c.req.json<{ enabled?: unknown }>();
  return c.json(await setFeature(c.env.DB, c.req.param('key'), body.enabled as boolean));
});

app.get('/api/devices', async (c) => {
  const { results } = await c.env.DB.prepare(`SELECT id, name, created_at, last_seen_at, revoked_at FROM devices ORDER BY created_at DESC`).all();
  return c.json(results);
});
app.delete('/api/devices/:id', async (c) => {
  await c.env.DB.prepare(`UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`).bind(Date.now(), c.req.param('id')).run();
  return c.body(null, 204);
});

// Web app -------------------------------------------------------------------------

app.all('/api/*', (c) => c.json({ error: 'Not found' }, 404));

app.get('*', async (c) => {
  if (!(await sessionEmail(c.env, c.req.raw))) {
    const url = new URL(c.req.url);
    return c.redirect(`/auth/login?next=${encodeURIComponent(url.pathname + url.search)}`);
  }
  if (!c.env.ASSETS) return c.notFound();
  return c.env.ASSETS.fetch(c.req.raw);
});
