// Lista: every task off the board in one place, like the phone's Tareas → Lista: today first,
// then pending, planned and a folded archive of done ones. Search covers the board too.

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { isOpen, quickAdd, taskRow } from '../tasks.js';
import { empty, h, fill } from '../ui.js';

let search = '';
let showDone = false;

export async function renderList(root) {
  const filter = search ? { search, limit: 300 } : { board: 'none', limit: 1000 };
  const tasks = (await Pausa.tasks(filter)).filter((task) => search || !task.following);
  const open = tasks.filter(isOpen);
  const today = open.filter((task) => task.plan_date && task.plan_date <= state.today);
  const pending = open.filter((task) => !task.plan_date).sort((a, b) => a.created_at - b.created_at);
  const planned = open.filter((task) => task.plan_date && task.plan_date > state.today).sort((a, b) => a.plan_date.localeCompare(b.plan_date));
  const done = tasks.filter((task) => task.status === 'done').sort((a, b) => (b.completed_at ?? 0) - (a.completed_at ?? 0));
  const cancelled = tasks.filter((task) => task.status === 'cancelled');

  const input = h('input', { type: 'search', value: search, placeholder: 'Buscar en todas las tareas, también el tablero', 'aria-label': 'Buscar tareas' });
  let timer;
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { search = input.value.trim(); state.refresh(); }, 250); });

  const section = (title, items, note) => items.length ? h('section.card', h('h2.section-title', `${title} (${items.length})`), note ? h('p.hint', note) : null, items.map((task) => taskRow(task))) : null;

  fill(root, 
    h('header.view-head', h('div', h('p.eyebrow', 'Tareas'), h('h1', 'Tu lista')), h('div.search', input)),
    quickAdd('Añadir tarea'),
    search ? h('p.hint', `${tasks.length} resultados para «${search}»`) : null,
    h('div.list-columns',
      h('div',
        section('Hoy', today, 'Planificadas para hoy o para días que ya pasaron.'),
        section('Pendientes', pending),
        open.length ? null : empty('Un poco de espacio mental.', 'Escribe arriba tu primera tarea.'),
      ),
      h('div',
        section('Planificadas', planned),
        done.length ? h('section.card',
          h('div.section-row', h('h2.section-title', `Hechas (${done.length})`), h('span.spacer'), h('button.link', { onclick: () => { showDone = !showDone; state.refresh(); } }, showDone ? 'Ocultar' : 'Mostrar')),
          showDone ? done.slice(0, 200).map((task) => taskRow(task)) : null,
        ) : null,
        section('Abandonadas', cancelled),
      ),
    ),
  );
  if (search) { input.focus(); input.setSelectionRange(input.value.length, input.value.length); }
}
