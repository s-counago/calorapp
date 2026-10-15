// Small DOM helpers shared by every view: element builder, dates, toasts with undo, dialogs.

/** h('button.primary', { onclick }, 'Guardar') builds an element. Children can be strings, nodes, arrays or null. */
export function h(tag, props = {}, ...children) {
  if (props === null || typeof props !== 'object' || props instanceof Node || Array.isArray(props)) {
    children.unshift(props);
    props = {};
  }
  const [name, ...classes] = tag.split('.');
  const element = document.createElement(name || 'div');
  if (classes.length) element.className = classes.join(' ');
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith('on') && typeof value === 'function') element.addEventListener(key.slice(2), value);
    else if (key === 'class') element.className += (element.className ? ' ' : '') + value;
    else if (key === 'style' && typeof value === 'object') Object.assign(element.style, value);
    else if (key === 'dataset') Object.assign(element.dataset, value);
    else if (key in element && typeof value !== 'string') element[key] = value;
    else element.setAttribute(key, value === true ? '' : value);
  }
  append(element, children);
  return element;
}

function append(element, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function svg(tag, attrs = {}, ...children) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attrs)) if (value !== undefined && value !== null) element.setAttribute(key, value);
  for (const child of children.flat(Infinity)) if (child) element.append(child);
  return element;
}

export function icon(name) {
  return h('span.icon', { 'aria-hidden': 'true' }, ICONS[name] ?? '•');
}

const ICONS = { sun: '☀', sunset: '◐', moon: '☾', dot: '·', plus: '+', check: '✓', close: '×', more: '⋯', left: '‹', right: '›', drag: '⠿' };

// Dates ------------------------------------------------------------------------------

export function addDays(day, days) {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

export function weekStart(day) {
  const weekday = new Date(`${day}T00:00:00Z`).getUTCDay();
  return addDays(day, -((weekday + 6) % 7));
}

const formatter = (options) => new Intl.DateTimeFormat('es-ES', { timeZone: 'UTC', ...options });
const LONG = formatter({ weekday: 'long', day: 'numeric', month: 'long' });
const SHORT = formatter({ weekday: 'short', day: 'numeric' });
const DAY_MONTH = formatter({ day: 'numeric', month: 'short' });
const MONTH = formatter({ month: 'long', year: 'numeric' });

const asDate = (day) => new Date(`${day}T00:00:00Z`);
export const longDay = (day) => LONG.format(asDate(day));
export const shortDay = (day) => SHORT.format(asDate(day));
export const dayMonth = (day) => DAY_MONTH.format(asDate(day));
export const monthName = (day) => MONTH.format(asDate(day));

/** «Hoy», «Mañana», «Ayer» or the weekday and date. */
export function relativeDay(day, today, style = 'long') {
  const difference = Math.round((asDate(day) - asDate(today)) / 86400000);
  if (difference === 0) return 'Hoy';
  if (difference === 1) return 'Mañana';
  if (difference === -1) return 'Ayer';
  return style === 'long' ? longDay(day) : shortDay(day);
}

export function time(ms) {
  return new Intl.DateTimeFormat('es-ES', { timeZone: 'Europe/Madrid', hour: '2-digit', minute: '2-digit' }).format(new Date(ms));
}

export function dateTime(ms) {
  if (!ms) return '—';
  return new Intl.DateTimeFormat('es-ES', { timeZone: 'Europe/Madrid', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(ms));
}

export function ago(ms) {
  if (!ms) return 'nunca';
  const minutes = Math.round((Date.now() - ms) / 60000);
  if (minutes < 1) return 'ahora mismo';
  if (minutes < 60) return `hace ${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `hace ${hours} h`;
  return `hace ${Math.round(hours / 24)} días`;
}

export const number = (value) => (value === null || value === undefined ? '—' : new Intl.NumberFormat('es-ES').format(Math.round(value)));

export function minutes(value) {
  if (value === null || value === undefined) return '—';
  const hours = Math.floor(value / 60);
  return hours ? `${hours} h ${String(value % 60).padStart(2, '0')}` : `${value} min`;
}

// Feedback ---------------------------------------------------------------------------

let toastTimer;
/** Shows a message at the bottom. With `undo`, a button runs it. */
export function toast(message, { undo, error = false } = {}) {
  const host = document.getElementById('toast');
  host.replaceChildren(
    h('span', message),
    undo ? h('button.link', { onclick: async () => { host.hidden = true; await undo(); } }, 'Deshacer') : null,
  );
  host.classList.toggle('error', error);
  host.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { host.hidden = true; }, error ? 9000 : 5000);
}

export function showError(caught) {
  const id = caught?.requestId ? ` · ${caught.requestId}` : '';
  toast(`${caught?.message ?? caught}${id}`, { error: true });
}

/** Runs an action and shows its error, so a failed click never fails silently. */
export async function attempt(action) {
  try {
    return await action();
  } catch (caught) {
    showError(caught);
    return undefined;
  }
}

// Dialogs ----------------------------------------------------------------------------

/**
 * Opens a modal dialog. `build(close)` returns the body; the promise resolves with what close() gets.
 * Escape and the backdrop close it with undefined.
 */
export function dialog(title, build, { wide = false } = {}) {
  return new Promise((resolve) => {
    const element = h('dialog.sheet', { class: wide ? 'wide' : '' });
    const close = (value) => { element.close(); element.remove(); resolve(value); };
    element.append(
      h('header.sheet-head', h('h2', title), h('button.icon-button', { 'aria-label': 'Cerrar', onclick: () => close(undefined) }, '×')),
      build(close),
    );
    element.addEventListener('cancel', (event) => { event.preventDefault(); close(undefined); });
    element.addEventListener('click', (event) => { if (event.target === element) close(undefined); });
    document.body.append(element);
    element.showModal();
    element.querySelector('[autofocus]')?.focus();
  });
}

export function confirmDialog(title, message, action = 'Eliminar') {
  return dialog(title, (close) =>
    h('div.sheet-body',
      h('p', message),
      h('div.actions', h('button.ghost', { onclick: () => close(false) }, 'Cancelar'), h('button.danger', { autofocus: true, onclick: () => close(true) }, action)),
    ),
  );
}

/** Asks for a number. Resolves with the number, or undefined if cancelled. */
export function askNumber(title, { label, value = '', hint, min = 0, integer = true, action = 'Guardar' } = {}) {
  return dialog(title, (close) => {
    const input = h('input', { type: 'number', inputMode: 'numeric', value, min, step: integer ? 1 : 'any', autofocus: true, required: true });
    const error = h('p.field-error');
    const submit = (event) => {
      event.preventDefault();
      const parsed = Number(input.value);
      if (input.value === '' || !Number.isFinite(parsed) || parsed < min || (integer && !Number.isInteger(parsed))) {
        error.textContent = integer ? `Escribe un número entero${min > 0 ? ` mayor o igual que ${min}` : ''}.` : 'Escribe un número válido.';
        return;
      }
      close(parsed);
    };
    return h('form.sheet-body', { onsubmit: submit },
      h('label.field', h('span', label ?? title), input),
      hint ? h('p.hint', hint) : null,
      error,
      h('div.actions', h('button.ghost', { type: 'button', onclick: () => close(undefined) }, 'Cancelar'), h('button.primary', { type: 'submit' }, action)),
    );
  });
}

export function empty(message, detail) {
  return h('div.empty', h('p', message), detail ? h('p.hint', detail) : null);
}

export const SLOT_LABELS = { morning: 'Mañana', afternoon: 'Tarde', evening: 'Noche' };
export const SLOT_ICONS = { morning: '☀', afternoon: '◐', evening: '☾' };
export const BOARD_LABELS = { stack: 'Stack infinito', priority: 'Prioridad', vault: 'Baúl', failed: 'Fracasos' };

/** The slot of the day it is now, with the phone's boundaries: before 12, before 20, after. */
export function currentSlot() {
  const hour = Number(new Intl.DateTimeFormat('es-ES', { timeZone: 'Europe/Madrid', hour: 'numeric', hourCycle: 'h23' }).format(new Date()));
  return hour < 12 && hour >= 3 ? 'morning' : hour >= 12 && hour < 20 ? 'afternoon' : 'evening';
}

/** Replaces an element's children, skipping null and false like h() does. */
export function fill(element, ...children) {
  element.replaceChildren();
  append(element, children);
  return element;
}
