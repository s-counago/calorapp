// The web shell: navigation, the capture box, hash routing and refresh. Each view lives in
// js/views and reads and writes the same REST API as the phone, so changes sync both ways.

import { Pausa, clientLog, reportError } from './api.js';
import { state, isOn } from './state.js';
import { LANE_LABELS, SLOT_LABELS, addDays, attempt, h, relativeDay, toast } from './ui.js';
import { firstUrl, parseLine, weekdayOf } from './quick.js';
import { renderToday } from './views/today.js';
import { renderWeek } from './views/week.js';
import { renderList } from './views/list.js';
import { renderBoard } from './views/board.js';
import { renderFollowups } from './views/followups.js';
import { renderHabits } from './views/habits.js';
import { renderReview } from './views/review.js';
import { renderHealth } from './views/health.js';
import { renderSettings } from './views/settings.js';
import { renderMoney } from './views/money.js';
import { renderCaptures } from './views/captures.js';
import { renderPlan } from './views/plan.js';

/**
 * Views in menu order. `feature` hides a view while that feature is off, like on the phone. With
 * Pausa 7 (`pausa_v7`) the menu follows the phone's four tabs: Hoy, Planificar, Hábitos and Dinero,
 * and the rest goes under «Más». `v7: 'only'` shows a view in the menu only with Pausa 7, `v7: 'hide'`
 * only without it; `parent` is the Pausa 7 entry that stays lit while it is open. Every view keeps
 * its address either way, so links between views still work.
 */
const VIEWS = [
  { path: 'hoy', label: 'Hoy', render: renderToday },
  { path: 'planificar', label: 'Planificar', render: renderPlan, v7: 'only' },
  { path: 'semana', label: 'Semana', render: renderWeek, v7: 'hide', parent: 'planificar' },
  { path: 'tareas', label: 'Bandeja', render: renderList, group: 'Tareas', v7: 'hide', parent: 'planificar' },
  { path: 'tablero', label: 'Carriles', render: renderBoard, feature: 'task_kanban', group: 'Tareas', v7: 'hide', parent: 'planificar' },
  { path: 'seguimientos', label: 'Esperando', render: renderFollowups, group: 'Tareas', v7: 'hide', parent: 'planificar' },
  { path: 'cajon', label: 'Cajón', render: renderCaptures, feature: 'captures', group: 'Tareas', v7: 'hide' },
  { path: 'habitos', label: 'Hábitos', render: renderHabits },
  { path: 'dinero', label: 'Dinero', render: renderMoney, feature: 'budget_cloud', v7: 'only' },
  { path: 'cajon', label: 'Cajón', render: renderCaptures, feature: 'captures', group: 'Más', v7: 'only' },
  { path: 'resumen', label: 'Resumen', render: renderReview, feature: 'review_zoom', group: () => (isOn('pausa_v7') ? 'Más' : null) },
  { path: 'dinero', label: 'Dinero', render: renderMoney, feature: 'budget_cloud', v7: 'hide' },
  { path: 'salud', label: 'Salud', render: renderHealth, feature: 'health_connect', group: () => (isOn('pausa_v7') ? 'Más' : null) },
  { path: 'ajustes', label: 'Ajustes', render: renderSettings, group: () => (isOn('pausa_v7') ? 'Más' : null) },
];

const featureOn = (view) => !view.feature || isOn(view.feature);
const inMenu = (view) => featureOn(view) && (view.v7 === 'only' ? isOn('pausa_v7') : view.v7 === 'hide' ? !isOn('pausa_v7') : true);
const groupOf = (view) => (typeof view.group === 'function' ? view.group() : view.group) ?? null;

const main = () => document.getElementById('view');

function route() {
  const [path, ...rest] = location.hash.replace(/^#\/?/, '').split('/');
  const view = VIEWS.find((candidate) => candidate.path === path && inMenu(candidate))
    ?? VIEWS.find((candidate) => candidate.path === path && featureOn(candidate)) ?? VIEWS[0];
  return { view, params: rest.join('/') || undefined };
}

let rendering = 0;
async function render({ keepScroll = false } = {}) {
  const { view, params } = route();
  const token = ++rendering;
  const scroll = scrollY;
  drawNav(view);
  const root = main();
  if (!keepScroll) root.replaceChildren(h('p.loading', 'Cargando…'));
  const container = h('div.view');
  const started = performance.now();
  try {
    await view.render(container, params);
    if (token !== rendering) return; // A newer navigation won.
    root.replaceChildren(container);
    document.title = `${view.label} · Pausa`;
    if (keepScroll) scrollTo(0, scroll);
    else scrollTo(0, 0);
    const ms = Math.round(performance.now() - started);
    if (ms > 3000) clientLog('warn', `Vista lenta: ${view.path}`, { ms });
  } catch (caught) {
    if (token !== rendering) return;
    console.error(caught);
    if (!caught?.requestId) reportError(`No se pudo dibujar ${view.path}: ${caught?.message}`, { stack: String(caught?.stack ?? '').slice(0, 2000) });
    root.replaceChildren(h('div.card.error-card',
      h('h2', 'No se ha podido cargar'),
      h('p', caught?.message ?? String(caught)),
      caught?.requestId ? h('p.hint', `request_id ${caught.requestId}: búscalo en los logs del Worker.`) : null,
      h('button.primary', { onclick: () => render() }, 'Reintentar'),
    ));
  }
}

function drawNav(active) {
  const nav = document.getElementById('nav');
  // A view out of the menu (e.g. Semana with Pausa 7) lights up the entry it belongs to.
  const lit = inMenu(active) ? active.path : isOn('pausa_v7') && active.parent ? active.parent : active.path;
  let group = null;
  const items = [];
  for (const view of VIEWS) {
    if (!inMenu(view)) continue;
    const viewGroup = groupOf(view);
    if (viewGroup && viewGroup !== group) items.push(h('p.nav-group', viewGroup));
    group = viewGroup;
    const current = view.path === lit;
    items.push(h('a', { href: `#/${view.path}`, class: current ? 'active' : '', 'aria-current': current ? 'page' : undefined }, view.label));
  }
  nav.replaceChildren(...items);
}

// Apuntar: the phone's one-line grammar (js/quick.js). «!» Prioridad, «esperar…» Esperando, a day and
// a slot at the end plan it, «+350» kcal, «p30» proteína, «c1» cigarro. With the Cajón on, a line with
// a link goes to the cajón, and Alt + Enter sends any line there as it is.
function describe(parsed, cajon) {
  if (cajon) return 'Al cajón, para ordenarlo luego.';
  if (parsed.kind !== 'task') {
    const unit = { calories: 'kcal hoy', protein: 'g de proteína hoy', cigarettes: parsed.amount === 1 ? 'cigarro hoy' : 'cigarros hoy' }[parsed.kind];
    return `+${parsed.amount} ${unit}`;
  }
  const where = `Tarea en ${LANE_LABELS[parsed.lane].toLowerCase()}`;
  if (parsed.day === null) return where;
  const day = addDays(state.today, parsed.day);
  return `${where} · ${relativeDay(day, state.today).toLowerCase()}${parsed.slot ? ` por la ${SLOT_LABELS[parsed.slot].toLowerCase()}` : ', a cualquier hora'}`;
}

function capture() {
  const input = h('input', { placeholder: 'Apuntar: llamar al banco mañana tarde, !urgente, esperar a Juan, +350, p30, c1', 'aria-label': 'Apuntar', maxLength: 500, 'aria-describedby': 'capture-preview' });
  const preview = h('p.capture-preview', { id: 'capture-preview', 'aria-live': 'polite' });
  const read = () => {
    const text = input.value.trim();
    const parsed = parseLine(text, { weekday: weekdayOf(state.today) });
    const toCajon = isOn('captures') && Boolean(firstUrl(text));
    return { text, parsed, toCajon };
  };
  const update = () => {
    const { text, parsed, toCajon } = read();
    preview.textContent = !text || !state.today ? '' : describe(parsed, toCajon);
  };
  input.addEventListener('input', update);
  input.addEventListener('blur', () => { if (!input.value.trim()) preview.textContent = ''; });

  const toCajon = async (text) => {
    const created = await attempt(() => Pausa.capture(text));
    if (!created) return false;
    toast('Al cajón', { undo: async () => { await attempt(() => Pausa.deleteCapture(created.id)); refresh(); } });
    return true;
  };
  const save = async (forceCajon) => {
    const { text, parsed, toCajon: link } = read();
    if (!text) return;
    if (isOn('captures') && (forceCajon || link)) {
      if (!(await toCajon(text))) return;
    } else if (parsed.kind !== 'task') {
      const { kind, amount } = parsed;
      if ((await attempt(() => Pausa.addCounter(kind, amount, state.today))) === undefined) return;
      toast(describe(parsed, false), { undo: async () => { await attempt(() => Pausa.addCounter(kind, -amount, state.today)); refresh(); } });
    } else {
      const task = { title: parsed.title, lane: parsed.lane };
      if (parsed.day !== null) Object.assign(task, { plan_date: addDays(state.today, parsed.day), plan_slot: parsed.slot });
      const created = await attempt(() => Pausa.createTask(task));
      if (!created) return;
      toast(`«${parsed.title}»: ${describe(parsed, false).replace(/^Tarea en /, '')}`, { undo: async () => { await attempt(() => Pausa.deleteTask(created.id)); refresh(); } });
    }
    input.value = '';
    update();
    refresh();
  };
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && event.altKey) { event.preventDefault(); save(true); }
  });
  return h('form.capture', { onsubmit: (event) => { event.preventDefault(); save(false); } }, input, preview);
}

/** Redraws the current view in place. Skipped while the user is typing or has a dialog open. */
function refresh({ shell = false, force = true } = {}) {
  if (!force) {
    const active = document.activeElement;
    if (document.querySelector('dialog[open]') || (active && /INPUT|TEXTAREA|SELECT/.test(active.tagName) && active.value)) return;
  }
  if (shell) drawNav(route().view);
  render({ keepScroll: true });
}

async function loadContext() {
  const [me, features] = await Promise.all([Pausa.me(), Pausa.features()]);
  const changed = state.today !== null && state.today !== me.today;
  state.today = me.today;
  state.featureList = features;
  state.features = Object.fromEntries(features.map((feature) => [feature.key, feature.enabled]));
  return changed;
}

async function start() {
  state.refresh = (options) => refresh(options);
  document.getElementById('capture-slot').replaceWith(capture());
  try {
    await loadContext();
  } catch (caught) {
    main().replaceChildren(h('div.card.error-card', h('h2', 'Pausa no responde'), h('p', caught.message), caught.requestId ? h('p.hint', `request_id ${caught.requestId}`) : null, h('button.primary', { onclick: () => location.reload() }, 'Reintentar')));
    return;
  }
  addEventListener('hashchange', () => render());
  // The phone and agents write too: catch up when the tab comes back and every minute while it is open.
  const catchUp = async () => {
    if (document.hidden) return;
    const dayChanged = await loadContext().catch(() => false);
    refresh({ shell: true, force: dayChanged });
  };
  addEventListener('focus', catchUp);
  document.addEventListener('visibilitychange', catchUp);
  setInterval(catchUp, 60_000);
  addEventListener('keydown', (event) => {
    if (event.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName ?? '')) {
      event.preventDefault();
      document.querySelector('.capture input')?.focus();
    }
  });
  render();
}

start();
