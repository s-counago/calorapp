// Semana: seven days side by side, each with its slots, and the tasks still to place on the
// left. Drag a task onto a slot to plan it; the web has room to see the whole week at once.

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { byPlan, dropTarget, isOpen, planOn, quickAdd, taskRow } from '../tasks.js';
import { SLOT_ICONS, SLOT_LABELS, addDays, dayMonth, empty, h, relativeDay, weekStart, fill } from '../ui.js';

export async function renderWeek(root, start) {
  start = start && /^\d{4}-\d{2}-\d{2}$/.test(start) ? start : weekStart(state.today);
  const end = addDays(start, 6);
  const [planned, list, priority, behind] = await Promise.all([
    Pausa.tasks({ plan_from: start, plan_to: end, limit: 1000 }),
    Pausa.tasks({ status: 'todo', lane: 'inbox', limit: 1000 }),
    Pausa.tasks({ status: 'todo', lane: 'priority', limit: 1000 }),
    Pausa.overview(state.today).then((overview) => overview.left_over.filter((task) => !task.following)),
  ]);
  const days = Array.from({ length: 7 }, (_, index) => addDays(start, index));
  const unplanned = (tasks) => tasks.filter((task) => !task.plan_date);
  const visible = planned.filter((task) => task.status !== 'cancelled' && !task.following);

  const backlog = h('aside.backlog',
    behind.length ? h('div.card.warn-card',
      h('h3.section-title', `Quedó atrás (${behind.length})`),
      h('p.hint', 'Planificadas en días que ya pasaron. Arrástralas a un día o decide qué hacer.'),
      behind.map((task) => taskRow(task, { actions: [h('button.pill', { onclick: () => planOn(task, state.today, task.plan_slot ?? null, 'A hoy') }, 'A hoy')] })),
    ) : null,
    dropTarget(h('div.card',
      h('h3.section-title', `Bandeja (${unplanned(list).length})`),
      quickAdd('Nueva tarea'),
      unplanned(list).map((task) => taskRow(task)),
      unplanned(list).length ? null : h('p.muted', 'Nada pendiente sin día.'),
    ), (task) => planOn(task, null)),
    h('div.card',
      h('h3.section-title', `Prioridad (${unplanned(priority).length})`),
      unplanned(priority).slice(0, 80).map((task) => taskRow(task, { hide: ['lane'] })),
    ),
  );

  const grid = h('div.week-grid', days.map((day) => {
    const tasks = visible.filter((task) => task.plan_date === day);
    const open = tasks.filter(isOpen).length;
    const isToday = day === state.today;
    return h('section.week-day', { class: isToday ? 'today' : day < state.today ? 'past' : '' },
      h('header',
        h('a', { href: `#/hoy/${day}` }, h('strong', relativeDay(day, state.today, 'short')), h('span.muted', ` ${dayMonth(day)}`)),
        h('span.muted', tasks.length ? `${tasks.length - open}/${tasks.length}` : ''),
      ),
      [...Object.keys(SLOT_LABELS), null].map((slot) => {
        const inSlot = tasks.filter((task) => (task.plan_slot ?? null) === slot).sort(byPlan);
        if (slot === null && !inSlot.length) return null;
        return dropTarget(h('div.week-slot',
          h('h4', slot ? `${SLOT_ICONS[slot]} ${SLOT_LABELS[slot]}` : 'A cualquier hora'),
          inSlot.map((task) => taskRow(task, { hide: ['plan'] })),
        ), (task) => planOn(task, day, slot));
      }),
      quickAdd('Añadir', { plan_date: day }, { compact: true }),
    );
  }));

  fill(root, 
    h('header.view-head',
      h('div', h('p.eyebrow', 'Planificar'), h('h1', `Semana del ${dayMonth(start)} al ${dayMonth(end)}`)),
      h('div.day-nav',
        h('a.icon-button', { href: `#/semana/${addDays(start, -7)}`, 'aria-label': 'Semana anterior' }, '‹'),
        start !== weekStart(state.today) ? h('a.pill', { href: '#/semana' }, 'Esta semana') : null,
        h('a.icon-button', { href: `#/semana/${addDays(start, 7)}`, 'aria-label': 'Semana siguiente' }, '›'),
      ),
    ),
    h('p.hint', 'Arrastra tareas entre días y franjas, o desde la Bandeja y Prioridad. Suelta una tarea en la Bandeja para quitarla del plan; vuelve a su carril.'),
    h('div.week-layout', backlog, grid),
    visible.length || list.length ? null : empty('Semana en blanco.'),
  );
}

