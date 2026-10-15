// The web shell: navigation, the capture box, hash routing and refresh. Each view lives in
// js/views and reads and writes the same REST API as the phone, so changes sync both ways.

import { Pausa, clientLog, reportError } from './api.js';
import { state, isOn } from './state.js';
import { attempt, h, toast } from './ui.js';
import { renderToday } from './views/today.js';
import { renderWeek } from './views/week.js';
import { renderList } from './views/list.js';
import { renderBoard } from './views/board.js';
import { renderFollowups } from './views/followups.js';
import { renderHabits } from './views/habits.js';
import { renderReview } from './views/review.js';
import { renderHealth } from './views/health.js';
import { renderSettings } from './views/settings.js';

/** Views in menu order. `feature` hides a view while that feature is off, like on the phone. */
const VIEWS = [
  { path: 'hoy', label: 'Hoy', render: renderToday },
  { path: 'semana', label: 'Semana', render: renderWeek },
  { path: 'tareas', label: 'Bandeja', render: renderList, group: 'Tareas' },
  { path: 'tablero', label: 'Carriles', render: renderBoard, feature: 'task_kanban', group: 'Tareas' },
  { path: 'seguimientos', label: 'Esperando', render: renderFollowups, group: 'Tareas' },
  { path: 'habitos', label: 'Hábitos', render: renderHabits },
  { path: 'resumen', label: 'Resumen', render: renderReview, feature: 'review_zoom' },
  { path: 'salud', label: 'Salud', render: renderHealth, feature: 'health_connect' },
  { path: 'ajustes', label: 'Ajustes', render: renderSettings },
];

const main = () => document.getElementById('view');

function route() {
  const [path, ...rest] = location.hash.replace(/^#\/?/, '').split('/');
  const view = VIEWS.find((candidate) => candidate.path === path && (!candidate.feature || isOn(candidate.feature))) ?? VIEWS[0];
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
  let group = null;
  const items = [];
  for (const view of VIEWS) {
    if (view.feature && !isOn(view.feature)) continue;
    if (view.group && view.group !== group) items.push(h('p.nav-group', view.group));
    group = view.group ?? null;
    items.push(h('a', { href: `#/${view.path}`, class: view === active ? 'active' : '', 'aria-current': view === active ? 'page' : undefined }, view.label));
  }
  nav.replaceChildren(...items);
}

// Capture box: a task by default; «+350» adds calories, «p30» protein and «c1» a cigarette.
function capture() {
  const input = h('input', { placeholder: 'Apuntar: una tarea, +350 kcal, p30 proteína, c1 cigarro', 'aria-label': 'Apuntar', maxLength: 500 });
  return h('form.capture', {
    onsubmit: async (event) => {
      event.preventDefault();
      const text = input.value.trim();
      if (!text) return;
      const counter = /^(?:([+-])(\d+)|p\s*([+-]?\d+)|c\s*([+-]?\d+))$/i.exec(text);
      if (counter) {
        const [kind, amount] = counter[2] ? ['calories', Number(counter[1] + counter[2])] : counter[3] ? ['protein', Number(counter[3])] : ['cigarettes', Number(counter[4])];
        if ((await attempt(() => Pausa.addCounter(kind, amount, state.today))) === undefined) return;
        const unit = { calories: 'kcal', protein: 'g de proteína', cigarettes: Math.abs(amount) === 1 ? 'cigarro' : 'cigarros' }[kind];
        toast(`${amount > 0 ? '+' : ''}${amount} ${unit} hoy`, { undo: async () => { await attempt(() => Pausa.addCounter(kind, -amount, state.today)); refresh(); } });
      } else {
        const created = await attempt(() => Pausa.createTask({ title: text }));
        if (!created) return;
        toast(`«${text}» a la bandeja`, { undo: async () => { await attempt(() => Pausa.deleteTask(created.id)); refresh(); } });
      }
      input.value = '';
      refresh();
    },
  }, input);
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
