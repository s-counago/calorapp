// Carriles: every open task by lane (bandeja, prioridad, stack, esperando, baúl, fracasos) and
// the running count of tasks done. Drag a card to another lane; its day, if any, stays.
// Behind task_kanban, like the board on the phone.

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { dropTarget, laneMenu, laneOf, moveTo, quickAdd, taskRow } from '../tasks.js';
import { LANE_LABELS, askNumber, attempt, h, toast, fill } from '../ui.js';

const PAGE = 60;
const shown = {};
const filters = {};

export async function renderBoard(root) {
  const lanes = Object.keys(LANE_LABELS);
  const [overview, ...lists] = await Promise.all([Pausa.overview(state.today), ...lanes.map((lane) => Pausa.tasks({ lane, status: 'todo', limit: 1000 }))]);

  const editTotal = async () => {
    const total = await askNumber('Tareas hechas', { value: overview.done_total, hint: 'Corrige el contador. Las tareas que completes después siguen sumando.' });
    if (total === undefined) return;
    const result = await attempt(() => Pausa.setDoneTotal(total));
    if (result) { toast(`Contador: ${result.previous} → ${result.done_total}`); state.refresh(); }
  };

  fill(root,
    h('header.view-head',
      h('div', h('p.eyebrow', 'Tareas'), h('h1', 'Carriles')),
      h('button.done-total', { onclick: editTotal, title: 'Corregir el contador' }, h('span.muted', 'Tareas hechas'), h('strong', overview.done_total.toLocaleString('es-ES'))),
    ),
    h('p.hint', 'Cada tarea está en un carril. Arrástrala a otro para moverla; si tenía día, lo conserva.'),
    h('div.board', lanes.map((lane, index) => {
      const tasks = lists[index].sort((a, b) => b.created_at - a.created_at);
      const query = (filters[lane] ?? '').toLowerCase();
      const matching = query ? tasks.filter((task) => task.title.toLowerCase().includes(query) || (task.notes ?? '').toLowerCase().includes(query)) : tasks;
      const limit = shown[lane] ?? PAGE;
      const search = h('input', { type: 'search', placeholder: 'Filtrar', value: filters[lane] ?? '', 'aria-label': `Filtrar ${LANE_LABELS[lane]}` });
      search.addEventListener('change', () => { filters[lane] = search.value; state.refresh(); });
      return dropTarget(h('section.board-column', { dataset: { column: lane } },
        h('header', h('h2', LANE_LABELS[lane]), h('span.count', overview.lanes[lane])),
        quickAdd(`Añadir a ${LANE_LABELS[lane].toLowerCase()}`, { lane }, { compact: true }),
        tasks.length > 10 ? search : null,
        matching.slice(0, limit).map((task) => taskRow(task, { hide: ['lane'], actions: [h('button.icon-button', { title: 'Mover a', onclick: (event) => laneMenu(task, event.currentTarget) }, '⇄')] })),
        matching.length > limit ? h('button.link', { onclick: () => { shown[lane] = limit + PAGE * 2; state.refresh(); } }, `Mostrar ${Math.min(PAGE * 2, matching.length - limit)} más de ${matching.length - limit}`) : null,
        matching.length ? null : h('p.muted', 'Nada por aquí.'),
      ), (task) => laneOf(task) !== lane && moveTo(task, lane));
    })),
  );
}
