// Bandeja: what was captured and not yet sorted into a lane (the old Lista): today first, then
// pending, planned and a folded archive of done ones. Search covers every lane.

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { isOpen, quickAdd, taskRow } from '../tasks.js';
import { empty, h, fill } from '../ui.js';

let search = '';
let showDone = false;

export async function renderList(root) {
  const tasks = await Pausa.tasks(search ? { search, limit: 300 } : { lane: 'inbox', limit: 1000 });
  const open = tasks.filter(isOpen);
  const today = open.filter((task) => task.plan_date && task.plan_date <= state.today);
  const pending = open.filter((task) => !task.plan_date).sort((a, b) => a.created_at - b.created_at);
  const planned = open.filter((task) => task.plan_date && task.plan_date > state.today).sort((a, b) => a.plan_date.localeCompare(b.plan_date));
  const done = tasks.filter((task) => task.status === 'done').sort((a, b) => (b.completed_at ?? 0) - (a.completed_at ?? 0));
  const cancelled = tasks.filter((task) => task.status === 'cancelled');

  const input = h('input', { type: 'search', value: search, placeholder: 'Buscar en todas las tareas y carriles', 'aria-label': 'Buscar tareas' });
  let timer;
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { search = input.value.trim(); state.refresh(); }, 250); });

  const section = (title, items, note) => items.length ? h('section.card', h('h2.section-title', `${title} (${items.length})`), note ? h('p.hint', note) : null, items.map((task) => taskRow(task))) : null;

  fill(root, 
    h('header.view-head', h('div', h('p.eyebrow', 'Tareas'), h('h1', 'Bandeja')), h('div.search', input)),
    quickAdd('Añadir a la bandeja'),
    h('p.hint', 'Lo que apuntas y aún no has decidido. Muévelo a un carril o planifícalo en un día desde el menú de cada tarea.'),
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
