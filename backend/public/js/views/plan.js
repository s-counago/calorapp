// Planificar (Pausa 7): the week strip on top and the lanes below, like the phone's Planificar tab.
// Drag a task onto a day to plan it, or onto a lane to move it there. What was left behind on a past
// day comes first, so nothing rots unnoticed. The full week by slots stays one click away (Semana).

import { Pausa } from '../api.js';
import { state, isOn } from '../state.js';
import { byLane, byPlan, dropTarget, isOpen, laneOf, moveTo, planOn, quickAdd, taskRow } from '../tasks.js';
import { LANE_LABELS, addDays, askNumber, attempt, clock, dayMonth, empty, fill, h, relativeDay, toast, weekStart } from '../ui.js';

const PAGE = 60;
const LANES = Object.keys(LANE_LABELS);
const laneKey = 'pausa.planLane';
let lane = (() => { try { return LANES.includes(localStorage.getItem(laneKey)) ? localStorage.getItem(laneKey) : 'inbox'; } catch { return 'inbox'; } })();
let filter = '';
let shown = PAGE;

const LANE_HINTS = {
  inbox: 'Lo que apuntas y aún no has decidido. Muévelo a un carril o arrástralo a un día.',
  priority: 'Lo próximo. Pocas cosas, y a la vista.',
  stack: 'Todo lo que algún día harás. Sube algo a Prioridad cuando quieras hacerlo pronto.',
  waiting: 'Lo que depende de otra persona. «Me toca» lo pasa a Prioridad.',
  vault: 'Ideas guardadas para más adelante.',
  failed: 'Lo que dejaste. Se queda a la vista para aprender de ello.',
};

export async function renderPlan(root, start) {
  start = start && /^\d{4}-\d{2}-\d{2}$/.test(start) ? start : weekStart(state.today);
  const end = addDays(start, 6);
  const [overview, planned, tasks, drawer] = await Promise.all([
    Pausa.overview(state.today),
    Pausa.tasks({ plan_from: start, plan_to: end, limit: 1000 }),
    Pausa.tasks({ lane, status: 'todo', limit: 1000 }),
    lane === 'inbox' && isOn('captures') ? Pausa.captures({ status: 'new', limit: 3 }) : Promise.resolve(null),
  ]);
  const behind = overview.left_over.filter((task) => !task.following);

  const editTotal = async () => {
    const total = await askNumber('Tareas hechas', { value: overview.done_total, hint: 'Corrige el contador. Las tareas que completes después siguen sumando.' });
    if (total === undefined) return;
    const result = await attempt(() => Pausa.setDoneTotal(total));
    if (result) { toast(`Contador: ${result.previous} → ${result.done_total}`); state.refresh(); }
  };

  fill(root,
    h('header.view-head',
      h('div', h('p.eyebrow', 'Planificar'), h('h1', `Semana del ${dayMonth(start)} al ${dayMonth(end)}`)),
      h('div.head-actions',
        h('div.day-nav',
          h('a.icon-button', { href: `#/planificar/${addDays(start, -7)}`, 'aria-label': 'Semana anterior' }, '‹'),
          start !== weekStart(state.today) ? h('a.pill', { href: '#/planificar' }, 'Esta semana') : null,
          h('a.icon-button', { href: `#/planificar/${addDays(start, 7)}`, 'aria-label': 'Semana siguiente' }, '›'),
        ),
        h('a.pill', { href: `#/semana/${start}`, title: 'Los siete días con sus franjas' }, 'Semana por franjas'),
        h('button.done-total', { onclick: editTotal, title: 'Corregir el contador' }, h('span.muted', 'Tareas hechas'), h('strong', overview.done_total.toLocaleString('es-ES'))),
      ),
    ),
    behind.length ? leftOver(behind) : null,
    weekStrip(start, planned),
    lanes(overview.lanes, tasks, drawer),
  );
}

// Week strip ------------------------------------------------------------------------

function weekStrip(start, planned) {
  const visible = planned.filter((task) => task.status !== 'cancelled' && !task.following);
  // On a narrow screen the strip scrolls: start it at today, once the view is on the page.
  requestAnimationFrame(() => {
    const strip = document.querySelector('.plan-week');
    const today = strip?.querySelector('.plan-day.today');
    if (strip && today && strip.scrollWidth > strip.clientWidth) strip.scrollLeft = today.offsetLeft - strip.offsetLeft;
  });
  return h('div.plan-week', Array.from({ length: 7 }, (_, index) => addDays(start, index)).map((day) => {
    const tasks = visible.filter((task) => task.plan_date === day).sort(byPlan);
    const open = tasks.filter(isOpen);
    const isToday = day === state.today;
    return dropTarget(h('section.plan-day', { class: isToday ? 'today' : day < state.today ? 'past' : '' },
      h('a.plan-day-head', { href: `#/hoy/${day}`, title: 'Abrir el día' },
        h('strong', relativeDay(day, state.today, 'short')), ['Hoy', 'Mañana', 'Ayer'].includes(relativeDay(day, state.today)) ? h('span.muted', ` ${dayMonth(day)}`) : null,
        h('span.spacer'), tasks.length ? h('span.muted', `${tasks.length - open.length}/${tasks.length}`) : null),
      open.slice(0, 5).map((task) => h('button.plan-day-task', { draggable: 'true', title: task.title,
        ondragstart: (event) => { event.dataTransfer.setData('application/x-pausa-task', JSON.stringify(task)); event.dataTransfer.effectAllowed = 'move'; },
        onclick: () => { location.hash = `#/hoy/${day}`; },
      }, task.plan_time !== null && task.plan_time !== undefined ? h('span.muted', `${clock(task.plan_time)} `) : null, task.title)),
      open.length > 5 ? h('span.muted.small', `y ${open.length - 5} más`) : null,
      tasks.length ? null : h('span.muted.small', day < state.today ? '—' : 'Libre'),
    ), (task) => planOn(task, day, null));
  }));
}

// Left behind -----------------------------------------------------------------------

function leftOver(tasks) {
  return h('div.card.warn-card',
    h('h2.section-title', `Quedó atrás (${tasks.length})`),
    h('p.hint', 'Planificadas en días que ya pasaron. Tráelas a hoy, devuélvelas a su carril o déjalas.'),
    tasks.slice(0, 20).map((task) => taskRow(task, { actions: [
      h('button.pill', { onclick: () => planOn(task, state.today, task.plan_slot ?? null, 'A hoy') }, 'A hoy'),
      h('button.pill', { title: 'Quita el día y la deja en su carril', onclick: () => planOn(task, null, null, 'De vuelta a su carril') }, 'A su carril'),
      h('button.pill', { onclick: () => moveTo(task, 'failed') }, 'Abandonar'),
    ] })),
    tasks.length > 20 ? h('p.hint', `Y ${tasks.length - 20} más.`) : null,
  );
}

// Lanes -----------------------------------------------------------------------------

function lanes(counts, tasks, drawer) {
  const choose = (key) => {
    lane = key; filter = ''; shown = PAGE;
    try { localStorage.setItem(laneKey, key); } catch { /* Only a convenience. */ }
    state.refresh();
  };
  const sorted = tasks.slice().sort((a, b) => lane === 'waiting'
    ? (a.review_date ?? a.expected_date ?? '9999').localeCompare(b.review_date ?? b.expected_date ?? '9999')
    : byLane(a, b));
  const query = filter.toLowerCase();
  const matching = query ? sorted.filter((task) => task.title.toLowerCase().includes(query) || (task.notes ?? '').toLowerCase().includes(query)) : sorted;
  const search = h('input', { type: 'search', placeholder: `Filtrar ${LANE_LABELS[lane].toLowerCase()}`, value: filter, 'aria-label': `Filtrar ${LANE_LABELS[lane]}` });
  search.addEventListener('change', () => { filter = search.value.trim(); shown = PAGE; state.refresh(); });

  return h('section.plan-lanes',
    h('div.lane-tabs', { role: 'tablist' }, LANES.map((key) => dropTarget(h('button', {
      role: 'tab', 'aria-selected': String(key === lane), class: key === lane ? 'active' : '', title: 'Suelta una tarea aquí para moverla a este carril',
      onclick: () => choose(key),
    }, LANE_LABELS[key], h('span.count', counts[key] ?? 0)), (task) => laneOf(task) !== key && moveTo(task, key)))),
    h('div.card',
      h('p.hint', LANE_HINTS[lane]),
      drawer && drawer.waiting ? h('a.drawer-line', { href: '#/cajon' },
        h('strong', `Cajón · ${drawer.waiting} sin ordenar`),
        h('span.muted', ` ${drawer.items.map((item) => String(item.title ?? item.text).slice(0, 60)).join(' · ')}`),
        h('span.link', ' Ordenar')) : null,
      quickAdd(`Añadir a ${LANE_LABELS[lane].toLowerCase()}`, lane === 'waiting' ? { lane, review_date: addDays(state.today, 3) } : { lane }),
      sorted.length > 10 ? search : null,
      matching.slice(0, shown).map((task) => taskRow(task, { hide: ['lane'], actions: lane === 'waiting' ? [
        h('button.pill', { title: 'Pasa a prioridad', onclick: () => moveTo(task, 'priority') }, 'Me toca'),
      ] : [] })),
      matching.length > shown ? h('button.link', { onclick: () => { shown += PAGE * 2; state.refresh(); } }, `Mostrar ${Math.min(PAGE * 2, matching.length - shown)} más de ${matching.length - shown}`) : null,
      matching.length ? null : empty(query ? 'Nada coincide con el filtro.' : 'Nada por aquí.'),
    ),
  );
}
