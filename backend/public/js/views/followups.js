// Seguimientos: what you are waiting on from others, with when it is expected and when to
// check again. Like the phone's «En seguimiento».

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { editTask, patchTask, taskRow } from '../tasks.js';
import { addDays, dayMonth, empty, h, fill } from '../ui.js';

export async function renderFollowups(root) {
  const tasks = (await Pausa.tasks({ following: true, status: 'todo', limit: 1000 }))
    .sort((a, b) => (a.review_date ?? a.expected_date ?? '9999').localeCompare(b.review_date ?? b.expected_date ?? '9999'));
  const due = tasks.filter((task) => task.review_date && task.review_date <= state.today);
  const rest = tasks.filter((task) => !due.includes(task));
  const actions = (task) => [
    h('button.pill', { title: 'Pasa a pendientes', onclick: () => patchTask(task, { following: false }, 'Ahora te toca a ti') }, 'Me toca'),
    h('button.pill', { title: 'Volver a revisar en una semana', onclick: () => patchTask(task, { review_date: addDays(state.today, 7) }, 'Revisar en una semana') }, '+7 días'),
    h('button.pill', { onclick: () => patchTask(task, { status: 'done' }, 'Resuelto') }, 'Resuelto'),
  ];
  const row = (task) => h('div.follow',
    taskRow(task, { hide: ['following'], actions: actions(task), draggable: false }),
    h('p.follow-meta',
      task.waiting_for ? `Esperando a ${task.waiting_for}` : 'Sin persona',
      task.expected_date ? ` · previsto ${dayMonth(task.expected_date)}` : '',
      task.review_date ? ` · revisar ${task.review_date <= state.today ? 'hoy' : dayMonth(task.review_date)}` : '',
    ),
  );
  fill(root, 
    h('header.view-head',
      h('div', h('p.eyebrow', 'Tareas'), h('h1', 'En seguimiento')),
      h('button.primary', { onclick: () => editTask(null, { following: true, review_date: addDays(state.today, 3) }) }, 'Nuevo seguimiento'),
    ),
    h('p.hint', 'Tenlo a la vista mientras esperas. Cuando te toque actuar, pásalo a pendientes.'),
    due.length ? h('section.card.warn-card', h('h2.section-title', `Revisar hoy (${due.length})`), due.map(row)) : null,
    rest.length ? h('section.card', h('h2.section-title', `A la espera (${rest.length})`), rest.map(row)) : null,
    tasks.length ? null : empty('Nada en seguimiento.', 'Lo que esperas de otros, a la vista.'),
  );
}
