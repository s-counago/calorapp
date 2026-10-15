// Tablero: the Kanban the Notion tasks moved to (stack infinito, prioridad, baúl, fracasos) and
// the running count of tasks done. Behind the task_kanban feature, like on the phone.

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { dropTarget, patchTask, quickAdd, taskRow, popover } from '../tasks.js';
import { BOARD_LABELS, askNumber, attempt, h, toast, fill } from '../ui.js';

const PAGE = 60;
const shown = {};
const filters = {};

export async function renderBoard(root) {
  const columns = Object.keys(BOARD_LABELS);
  const [summary, ...lists] = await Promise.all([Pausa.board(), ...columns.map((column) => Pausa.tasks({ board: column, status: 'todo', limit: 1000 }))]);

  const editTotal = async () => {
    const total = await askNumber('Tareas hechas', { value: summary.done_total, hint: 'Corrige el contador. Las tareas que completes después siguen sumando.' });
    if (total === undefined) return;
    const result = await attempt(() => Pausa.setDoneTotal(total));
    if (result) { toast(`Contador: ${result.previous} → ${result.done_total}`); state.refresh(); }
  };

  fill(root, 
    h('header.view-head',
      h('div', h('p.eyebrow', 'Tareas'), h('h1', 'Tablero')),
      h('button.done-total', { onclick: editTotal, title: 'Corregir el contador' }, h('span.muted', 'Tareas hechas'), h('strong', summary.done_total.toLocaleString('es-ES'))),
    ),
    h('div.board', columns.map((column, index) => {
      const tasks = lists[index].sort((a, b) => b.created_at - a.created_at);
      const query = (filters[column] ?? '').toLowerCase();
      const matching = query ? tasks.filter((task) => task.title.toLowerCase().includes(query) || (task.notes ?? '').toLowerCase().includes(query)) : tasks;
      const limit = shown[column] ?? PAGE;
      const search = h('input', { type: 'search', placeholder: 'Filtrar', value: filters[column] ?? '', 'aria-label': `Filtrar ${BOARD_LABELS[column]}` });
      search.addEventListener('change', () => { filters[column] = search.value; state.refresh(); });
      return dropTarget(h('section.board-column', { dataset: { column } },
        h('header', h('h2', BOARD_LABELS[column]), h('span.count', summary.columns[column])),
        quickAdd(`Añadir a ${BOARD_LABELS[column].toLowerCase()}`, { board: column }),
        tasks.length > 10 ? search : null,
        matching.slice(0, limit).map((task) => taskRow(task, { hide: ['board'], actions: [h('button.icon-button', { title: 'Mover a', onclick: (event) => moveMenu(task, event.currentTarget) }, '⇄')] })),
        matching.length > limit ? h('button.link', { onclick: () => { shown[column] = limit + PAGE * 2; state.refresh(); } }, `Mostrar ${Math.min(PAGE * 2, matching.length - limit)} más de ${matching.length - limit}`) : null,
        matching.length ? null : h('p.muted', 'Nada por aquí.'),
      ), (task) => task.board !== column && patchTask(task, { board: column }, `Movida a ${BOARD_LABELS[column].toLowerCase()}`));
    })),
  );
}

function moveMenu(task, anchor) {
  popover(anchor, [
    ...Object.entries(BOARD_LABELS).filter(([key]) => key !== task.board).map(([key, label]) => ({ label, run: () => patchTask(task, { board: key }, `Movida a ${label.toLowerCase()}`) })),
    { label: 'Lista (fuera del tablero)', run: () => patchTask(task, { board: null }, 'Movida a la lista') },
  ]);
}
