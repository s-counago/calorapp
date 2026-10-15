// Salud: sleep, steps, exercise and resting heart rate the phone reads from the watch.

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { addDays, h, minutes, number, shortDay, svg, time, fill } from '../ui.js';

/** «6 h 10 · 10.261 pasos · 25 min · 63 lpm», or a hint when there is nothing yet. */
export function healthLine(day, { full = false } = {}) {
  if (!day) return h('p.muted', 'Aún no hay datos de este día. Llegan del móvil cuando abres Hoy allí.');
  const items = [
    ['Sueño', minutes(day.sleep_minutes), day.sleep_start && day.sleep_end ? `${time(day.sleep_start)}–${time(day.sleep_end)}` : null],
    ['Pasos', number(day.steps), null],
    ['Ejercicio', day.exercise_minutes === null ? '—' : `${day.exercise_minutes} min`, null],
    ['Reposo', day.resting_hr === null ? '—' : `${day.resting_hr} lpm`, null],
  ];
  return h('dl.stats', { class: full ? '' : 'inline' }, items.map(([label, value, detail]) => h('div', h('dt', label), h('dd', value, detail ? h('small.muted', ` ${detail}`) : null))));
}

const RANGES = [7, 30, 90, 365];

export async function renderHealth(root, params) {
  const days = RANGES.includes(Number(params)) ? Number(params) : 30;
  const to = state.today;
  const from = addDays(to, -(days - 1));
  const data = await Pausa.health(from, to);
  const byDay = new Map(data.per_day.map((row) => [row.day, row]));
  const series = Array.from({ length: days }, (_, index) => addDays(from, index)).map((day) => ({ day, ...(byDay.get(day) ?? {}) }));
  fill(root, 
    h('header.view-head',
      h('div', h('p.eyebrow', 'Salud del reloj'), h('h1', `Últimos ${days} días`)),
      h('div.segmented', RANGES.map((range) => h('a', { href: `#/salud/${range}`, class: range === days ? 'active' : '' }, range === 365 ? '1 año' : `${range} días`))),
    ),
    h('div.stat-row',
      stat('Sueño medio', minutes(data.average.sleep_minutes)),
      stat('Pasos de media', number(data.average.steps)),
      stat('Pulso en reposo', data.average.resting_hr === null ? '—' : `${data.average.resting_hr} lpm`),
      stat('Ejercicio total', `${number(data.exercise_minutes_total)} min`),
      stat('Días con datos', `${data.days_with_data} de ${days}`),
    ),
    h('div.chart-grid',
      chartCard('Sueño (horas)', series, (row) => (row.sleep_minutes ?? null) === null ? null : row.sleep_minutes / 60, { reference: 8 }),
      chartCard('Pasos', series, (row) => row.steps ?? null, { reference: 10000 }),
      chartCard('Ejercicio (min)', series, (row) => row.exercise_minutes ?? null),
      chartCard('Pulso en reposo (lpm)', series, (row) => row.resting_hr ?? null, { line: true }),
    ),
    h('details.card', h('summary.section-title', 'Tabla por día'),
      h('table.data',
        h('thead', h('tr', ['Día', 'Sueño', 'Se durmió', 'Despertó', 'Pasos', 'Ejercicio', 'Reposo'].map((label) => h('th', label)))),
        h('tbody', series.slice().reverse().filter((row) => byDay.has(row.day)).map((row) => h('tr',
          h('td', shortDay(row.day)), h('td', minutes(row.sleep_minutes)), h('td', row.sleep_start ? time(row.sleep_start) : '—'), h('td', row.sleep_end ? time(row.sleep_end) : '—'),
          h('td', number(row.steps)), h('td', row.exercise_minutes ?? '—'), h('td', row.resting_hr ?? '—'),
        ))),
      ),
    ),
  );
}

const stat = (label, value) => h('div.card.stat', h('span.muted', label), h('strong', value));

/** A bar (or line) chart of one value per day, with an optional reference line. */
export function chartCard(title, series, pick, { reference, line = false, goalLabel } = {}) {
  const values = series.map(pick);
  const present = values.filter((value) => value !== null && value !== undefined);
  const max = Math.max(reference ?? 0, ...present, 1) * 1.08;
  const min = line && present.length ? Math.min(...present) * 0.95 : 0;
  const width = 600, height = 160, pad = 4;
  const step = (width - pad * 2) / Math.max(1, values.length);
  const y = (value) => height - pad - ((value - min) / (max - min || 1)) * (height - pad * 2);
  const marks = line
    ? [svg('polyline', { points: values.map((value, index) => (value === null || value === undefined ? null : `${pad + step * (index + 0.5)},${y(value)}`)).filter(Boolean).join(' '), class: 'line' })]
    : values.map((value, index) => (value === null || value === undefined ? null
      : svg('rect', { x: pad + step * index + step * 0.15, y: y(value), width: Math.max(1, step * 0.7), height: Math.max(0, height - pad - y(value)), class: reference !== undefined && value > reference && goalLabel === 'limit' ? 'col over' : 'col' },
        svg('title', {}, document.createTextNode(`${shortDay(series[index].day)}: ${(Math.round(value * 10) / 10).toLocaleString('es-ES')}`)))));
  return h('div.card.chart',
    h('h3', title),
    svg('svg', { viewBox: `0 0 ${width} ${height}`, preserveAspectRatio: 'none', role: 'img', 'aria-label': title },
      reference !== undefined ? svg('line', { x1: 0, x2: width, y1: y(reference), y2: y(reference), class: 'reference' }) : null,
      marks,
    ),
    h('div.chart-axis', h('span', shortDay(series[0].day)), h('span', shortDay(series[series.length - 1].day))),
  );
}
