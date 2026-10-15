// Resumen: the week (Monday to Sunday) or the month around a day, with tasks, habits, counters
// and health. Behind review_zoom, like the Día · Semana · Mes selector on the phone.

import { Pausa } from '../api.js';
import { state, isOn } from '../state.js';
import { taskRow } from '../tasks.js';
import { addDays, dayMonth, h, minutes, monthName, number, fill } from '../ui.js';
import { chartCard } from './health.js';

export async function renderReview(root, params = '') {
  const [periodRaw, dayRaw] = params.split('/');
  const period = periodRaw === 'month' ? 'month' : 'week';
  const day = /^\d{4}-\d{2}-\d{2}$/.test(dayRaw ?? '') ? dayRaw : state.today;
  const data = await Pausa.review(day, period);
  const previous = period === 'week' ? addDays(data.start, -7) : addDays(data.start, -1);
  const next = addDays(data.end, 1);
  const c = data.counters;
  const title = period === 'week' ? `Semana del ${dayMonth(data.start)} al ${dayMonth(data.end)}` : monthName(data.start);
  const series = c.per_day;

  fill(root, 
    h('header.view-head',
      h('div', h('p.eyebrow', 'Resumen'), h('h1', title)),
      h('div.head-actions',
        h('div.segmented', h('a', { href: `#/resumen/week/${day}`, class: period === 'week' ? 'active' : '' }, 'Semana'), h('a', { href: `#/resumen/month/${day}`, class: period === 'month' ? 'active' : '' }, 'Mes')),
        h('div.day-nav',
          h('a.icon-button', { href: `#/resumen/${period}/${previous}`, 'aria-label': 'Periodo anterior' }, '‹'),
          next <= state.today ? h('a.icon-button', { href: `#/resumen/${period}/${next}`, 'aria-label': 'Periodo siguiente' }, '›') : null,
        ),
      ),
    ),
    data.start > state.today ? h('p.notice', 'Aún no ha empezado.') : null,
    h('div.stat-row',
      stat('Tareas hechas', number(data.tasks.completed.length)),
      stat('Hábitos cumplidos', `${data.habits.filter((habit) => habit.met).length} de ${data.habits.length}`),
      stat('Calorías medias', c.average.calories === null ? '—' : `${number(c.average.calories)} kcal`, `${c.days_within_calorie_goal} de ${c.days_logged} días dentro del límite`),
      stat('Proteína media', c.average.protein === null ? '—' : `${number(c.average.protein)} g`, `${c.days_reaching_protein_goal} días con el objetivo`),
      stat('Cigarros de media', c.average.cigarettes === null ? '—' : number(c.average.cigarettes), `límite ${c.goals.cigarette_goal}`),
      isOn('health_connect') ? stat('Sueño medio', minutes(data.health.average.sleep_minutes), `${number(data.health.average.steps)} pasos de media`) : null,
    ),
    h('div.chart-grid',
      chartCard(`Calorías por día frente al límite de ${number(c.goals.calorie_goal)}`, series, (row) => row.calories || null, { reference: c.goals.calorie_goal, goalLabel: 'limit' }),
      chartCard(`Proteína por día (objetivo ${c.goals.protein_goal} g)`, series, (row) => row.protein || null, { reference: c.goals.protein_goal }),
      chartCard(`Cigarros por día (límite ${c.goals.cigarette_goal})`, series, (row) => row.cigarettes || null, { reference: c.goals.cigarette_goal, goalLabel: 'limit' }),
      isOn('health_connect') && data.health.per_day.length ? chartCard('Sueño (horas)', series.map((row) => ({ day: row.day, ...(data.health.per_day.find((item) => item.day === row.day) ?? {}) })), (row) => (row.sleep_minutes ?? null) === null ? null : row.sleep_minutes / 60, { reference: 8 }) : null,
    ),
    h('div.list-columns',
      h('section.card',
        h('h2.section-title', 'Hábitos'),
        data.habits.length ? h('table.data', h('thead', h('tr', h('th', 'Hábito'), h('th', 'Hechos'), h('th', 'Objetivo'), h('th', ''))),
          h('tbody', data.habits.map((habit) => h('tr', h('td', habit.name), h('td', habit.days_done), h('td', habit.target), h('td', habit.met ? '✓' : ''))))) : h('p.muted', 'Sin hábitos todavía.'),
      ),
      h('section.card',
        h('h2.section-title', `Pendiente con fecha (${data.tasks.pending_due_or_planned.length})`),
        data.tasks.pending_due_or_planned.length ? data.tasks.pending_due_or_planned.slice(0, 100).map((task) => taskRow(task)) : h('p.muted', 'Nada pendiente con fecha.'),
      ),
      h('details.card',
        h('summary.section-title', `Hechas en el periodo (${data.tasks.completed.length})`),
        data.tasks.completed.slice().reverse().slice(0, 300).map((task) => taskRow(task, { draggable: false })),
      ),
    ),
    h('p.hint', 'El dinero del periodo sigue solo en el móvil.'),
  );
}

const stat = (label, value, detail) => h('div.card.stat', h('span.muted', label), h('strong', value), detail ? h('small.muted', detail) : null);
