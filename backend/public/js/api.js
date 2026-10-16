// Every call to the backend goes through here: it sends an X-Request-Id, logs failures with
// the server's request id and reports web errors to /api/logs so they show up in get_client_logs.

export const WEB_VERSION = 'web-1.1.0';

export class ApiError extends Error {
  constructor(message, { status, code, requestId } = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }
}

const newRequestId = () => 'web-' + crypto.randomUUID().replaceAll('-', '').slice(0, 24);

export async function api(path, { method = 'GET', body, quiet = false } = {}) {
  const requestId = newRequestId();
  const headers = { 'X-Request-Id': requestId };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const started = performance.now();
  let response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' });
  } catch (caught) {
    console.warn('[pausa] red', method, path, requestId, caught);
    throw new ApiError('No hay conexión con Pausa. Revisa la red y vuelve a intentarlo.', { code: 'network', requestId });
  }
  const serverId = response.headers.get('X-Request-Id') ?? requestId;
  if (response.status === 401) {
    location.href = '/auth/login?next=' + encodeURIComponent(location.pathname + location.hash);
    throw new ApiError('Tienes que iniciar sesión.', { status: 401, code: 'not_signed_in', requestId: serverId });
  }
  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new ApiError(data?.error ?? `Error ${response.status}`, { status: response.status, code: data?.code, requestId: data?.request_id ?? serverId });
    console.warn('[pausa]', method, path, response.status, error.code, error.requestId);
    if (!quiet) reportError(`${method} ${path} → ${response.status}`, { code: error.code, error: error.message, request_id: error.requestId, ms: Math.round(performance.now() - started) });
    throw error;
  }
  return data;
}

// Client logs ----------------------------------------------------------------------

const pending = [];
let flushTimer = null;

export function clientLog(level, message, data) {
  pending.push({ at: Date.now(), level, tag: 'web', message: String(message).slice(0, 4000), data });
  if (pending.length > 50) pending.splice(0, pending.length - 50);
  clearTimeout(flushTimer);
  flushTimer = setTimeout(flushLogs, 1500);
}

export function reportError(message, data) {
  clientLog('error', message, data);
}

async function flushLogs() {
  if (!pending.length) return;
  const entries = pending.splice(0, pending.length);
  try {
    await fetch('/api/logs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Request-Id': newRequestId() },
      body: JSON.stringify({ app_version: WEB_VERSION, entries }),
      credentials: 'same-origin',
      keepalive: true,
    });
  } catch {
    // Nothing else to do: the browser console still has it.
  }
}

addEventListener('error', (event) => reportError(`Error de JavaScript: ${event.message}`, { source: event.filename, line: event.lineno, column: event.colno, page: location.hash }));
addEventListener('unhandledrejection', (event) => {
  if (event.reason instanceof ApiError) return; // Already reported by api().
  reportError(`Promesa rechazada: ${event.reason?.message ?? event.reason}`, { page: location.hash, stack: String(event.reason?.stack ?? '').slice(0, 2000) });
});
addEventListener('pagehide', flushLogs);

// Endpoints -------------------------------------------------------------------------

export const Pausa = {
  me: () => api('/api/me'),
  features: () => api('/api/features').then((data) => data.items),
  setFeature: (key, enabled) => api(`/api/features/${encodeURIComponent(key)}`, { method: 'PUT', body: { enabled } }),

  day: (day) => api(`/api/day?day=${day}`),
  goals: () => api('/api/goals'),
  setGoals: (goals) => api('/api/goals', { method: 'PUT', body: goals }),
  entries: (day) => api(`/api/counters?day=${day}`).then((data) => data.entries),
  addCounter: (kind, amount, day) => api('/api/counters', { method: 'POST', body: { kind, amount, day } }),
  setCounter: (kind, total, day) => api('/api/counters', { method: 'PUT', body: { kind, total, day } }),

  tasks: (filter = {}) => {
    const query = new URLSearchParams(Object.entries(filter).filter(([, value]) => value !== undefined && value !== null && value !== '').map(([key, value]) => [key, String(value)]));
    return api(`/api/tasks?${query}`);
  },
  task: (id) => api(`/api/tasks/${id}`),
  createTask: (task) => api('/api/tasks', { method: 'POST', body: task }),
  updateTask: (id, patch) => api(`/api/tasks/${id}`, { method: 'PATCH', body: patch }),
  deleteTask: (id) => api(`/api/tasks/${id}`, { method: 'DELETE' }),
  overview: (day) => api(`/api/tasks/overview?day=${day}`),
  moveTask: (id, lane) => api(`/api/tasks/${id}/move`, { method: 'POST', body: { lane } }),
  planTask: (id, appointment) => api(`/api/tasks/${id}/plan`, { method: 'POST', body: appointment }),
  habitToTask: (id, day) => api(`/api/habits/${id}/task`, { method: 'POST', body: { day } }),
  board: () => api('/api/board'),
  setDoneTotal: (total) => api('/api/board/done', { method: 'PUT', body: { total } }),

  habits: (day, weeks) => api(`/api/habits?day=${day}${weeks ? `&weeks=${weeks}` : ''}`),
  createHabit: (name, weekly_goal) => api('/api/habits', { method: 'POST', body: { name, weekly_goal } }),
  updateHabit: (id, patch) => api(`/api/habits/${id}`, { method: 'PATCH', body: patch }),
  deleteHabit: (id) => api(`/api/habits/${id}`, { method: 'DELETE' }),
  checkHabit: (id, day, done) => api(`/api/habits/${id}/check`, { method: 'POST', body: { day, done } }),

  review: (day, period) => api(`/api/review?day=${day}&period=${period}`),
  health: (from, to) => api(`/api/health?from=${from}&to=${to}`),

  captures: (filter = {}) => api(`/api/captures?${new URLSearchParams(Object.entries(filter).filter(([, value]) => value !== undefined && value !== null && value !== ''))}`),
  capture: (text) => api('/api/captures', { method: 'POST', body: { text } }),
  fileCapture: (id, decision) => api(`/api/captures/${id}/file`, { method: 'POST', body: decision }),
  deleteCapture: (id) => api(`/api/captures/${id}`, { method: 'DELETE' }),
  note: () => api('/api/note').then((data) => data.note),
  setNote: (text, until) => api('/api/note', { method: 'PUT', body: { text, until } }).then((data) => data.note),

  budget: (day) => api(`/api/budget${day ? `?day=${day}` : ''}`),
  devices: () => api('/api/devices'),
  revokeDevice: (id) => api(`/api/devices/${id}`, { method: 'DELETE' }),
  status: () => api('/api/status'),
  logs: (filter = {}) => api(`/api/logs?${new URLSearchParams(filter)}`),
  history: (table, id) => api(`/api/history/${table}/${encodeURIComponent(id)}`),
};
