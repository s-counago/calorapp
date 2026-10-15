// Hábitos: weekly habits with their points, streak and chart, the days of any week to correct,
// and creating, editing, archiving or deleting them. Points come from the backend, which scores
// the synced checks the same way the phone does.

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { popover, showHistory } from '../tasks.js';
import { addDays, attempt, confirmDialog, dayMonth, dialog, empty, h, shortDay, svg, toast, weekStart, fill } from '../ui.js';

const WEEK_OPTIONS = [10, 26, 52, 104];
const weeksKey = 'pausa.habitChartWeeks';
const chartWeeks = () => { try { return WEEK_OPTIONS.includes(Number(localStorage.getItem(weeksKey))) ? Number(localStorage.getItem(weeksKey)) : 10; } catch { return 10; } };

export async function renderHabits(root, day) {
  const viewDay = day && /^\d{4}-\d{2}-\d{2}$/.test(day) && day <= state.today ? day : state.today;
  const start = weekStart(viewDay);
  const isThisWeek = start === weekStart(state.today);
  const weeks = chartWeeks();
  const [habits, weekHabits] = await Promise.all([
    Pausa.habits(state.today, weeks),
    isThisWeek ? Promise.resolve(null) : Pausa.habits(viewDay),
  ]);
  const weekDone = new Map((weekHabits ?? habits).map((habit) => [habit.id, habit.week_days_done]));
  const total = habits.reduce((sum, habit) => sum + habit.points, 0);
  const met = habits.filter((habit) => habit.minimum_met).length;
  const days = Array.from({ length: 7 }, (_, index) => addDays(start, index));

  fill(root, 
    h('header.view-head',
      h('div', h('p.eyebrow', 'Pequeños pasos que se quedan contigo'), h('h1', 'Tus hábitos')),
      h('div.head-actions',
        h('div.done-total', h('span.muted', 'Puntos totales'), h('strong', total.toLocaleString('es-ES'))),
        h('button.primary', { onclick: () => habitEditor(null) }, 'Nuevo hábito'),
      ),
    ),
    h('div.toolbar',
      h('div.day-nav',
        h('a.icon-button', { href: `#/habitos/${addDays(start, -7)}`, 'aria-label': 'Semana anterior' }, '‹'),
        h('span', isThisWeek ? 'Esta semana' : `Semana del ${dayMonth(start)}`),
        !isThisWeek ? h('a.icon-button', { href: `#/habitos/${addDays(start, 7)}`, 'aria-label': 'Semana siguiente' }, '›') : null,
        !isThisWeek ? h('a.pill', { href: '#/habitos' }, 'Volver a esta semana') : null,
      ),
      h('span.muted', `${met} de ${habits.length} con el mínimo esta semana`),
      h('span.spacer'),
      h('label.inline-field', 'Gráfica ', h('select', { onchange: (event) => { try { localStorage.setItem(weeksKey, event.target.value); } catch {} state.refresh(); } },
        WEEK_OPTIONS.map((option) => h('option', { value: option, selected: option === weeks }, `${option} semanas`)))),
      h('button.link', { onclick: explain }, '¿Cómo se puntúa?'),
    ),
    !isThisWeek ? h('p.notice', 'Estás corrigiendo una semana pasada. Los puntos se recalculan con las marcas que cambies.') : null,
    habits.length ? h('div.habit-grid', habits.map((habit) => habitCard(habit, days, new Set(weekDone.get(habit.id) ?? []), isThisWeek)))
      : empty('Crea algo que quieras repetir.', 'Hoy la gráfica será plana; tu constancia la hará crecer.'),
  );
}

function habitCard(habit, days, done, isThisWeek) {
  const count = done.size;
  const toggle = async (day) => {
    const value = !done.has(day);
    if ((await attempt(() => Pausa.checkHabit(habit.id, day, value))) === undefined) return;
    toast(value ? `«${habit.name}» · ${shortDay(day)} hecho` : `Marca deshecha · ${shortDay(day)}`, { undo: async () => { await attempt(() => Pausa.checkHabit(habit.id, day, !value)); state.refresh(); } });
    state.refresh();
  };
  return h('article.card.habit', { class: habit.minimum_met ? 'met' : '' },
    h('header',
      h('div', h('h3', habit.name), h('p.muted', `Mínimo ${habit.weekly_goal} ${habit.weekly_goal === 1 ? 'día' : 'días'} por semana`)),
      h('button.icon-button', { 'aria-label': 'Más acciones', onclick: (event) => popover(event.currentTarget, [
        { label: 'Editar…', run: () => habitEditor(habit) },
        { label: 'Archivar', run: () => archive(habit) },
        { label: 'Historial de cambios…', run: () => showHistory('habits', habit.id, habit.name) },
        { label: 'Eliminar', danger: true, run: () => remove(habit) },
      ]) }, '⋯'),
    ),
    h('div.week-dots', days.map((day) => {
      const future = day > state.today;
      return h('button', {
        class: [done.has(day) ? 'on' : '', day === state.today ? 'today' : ''].join(' '),
        disabled: future,
        title: `${shortDay(day)}${done.has(day) ? ' · hecho' : ''}`,
        'aria-pressed': done.has(day) ? 'true' : 'false',
        onclick: () => toggle(day),
      }, shortDay(day).slice(0, 2));
    })),
    h('p', h('strong', `${count}/${habit.weekly_goal}`), h('span.muted', isThisWeek ? ' esta semana' : ' esa semana')),
    h('dl.stats.inline',
      h('div', h('dt', 'Puntos'), h('dd', habit.points.toLocaleString('es-ES'))),
      h('div', h('dt', 'Racha'), h('dd', `${habit.streak} ${habit.streak === 1 ? 'semana' : 'semanas'}`)),
      h('div', h('dt', habit.minimum_met ? 'Día extra' : 'Al cumplir'), h('dd', `+${habit.next_reward}`)),
      habit.last_week_penalty ? h('div', h('dt', 'Semana pasada'), h('dd.warn-text', `−${habit.last_week_penalty}`)) : null,
    ),
    sparkline(habit.sparkline),
  );
}

function sparkline(values) {
  const width = 300, height = 60;
  const max = Math.max(1, ...values);
  const step = width / Math.max(1, values.length - 1);
  const points = values.map((value, index) => `${index * step},${height - 4 - (value / max) * (height - 8)}`).join(' ');
  return svg('svg', { class: 'sparkline', viewBox: `0 0 ${width} ${height}`, preserveAspectRatio: 'none', role: 'img', 'aria-label': `Puntos de las últimas ${values.length} semanas` },
    svg('polyline', { points: `0,${height} ${points} ${width},${height}`, class: 'area' }),
    svg('polyline', { points, class: 'line' }),
  );
}

function habitEditor(habit) {
  return dialog(habit ? 'Editar hábito' : 'Nuevo hábito', (close) => {
    const name = h('input', { value: habit?.name ?? '', placeholder: 'Ej. Llamar a mis abuelas', maxLength: 200, autofocus: true, required: true });
    let goal = habit?.weekly_goal ?? 3;
    const goalValue = h('strong', String(goal));
    const setGoal = (value) => { goal = Math.max(1, Math.min(7, value)); goalValue.textContent = String(goal); };
    const error = h('p.field-error');
    return h('form.sheet-body', {
      onsubmit: async (event) => {
        event.preventDefault();
        if (!name.value.trim()) { error.textContent = 'Ponle un nombre al hábito.'; return; }
        const saved = await attempt(() => habit ? Pausa.updateHabit(habit.id, { name: name.value.trim(), weekly_goal: goal }) : Pausa.createHabit(name.value.trim(), goal));
        if (!saved) return;
        toast(habit ? 'Hábito guardado' : `«${name.value.trim()}» empieza hoy`);
        close(saved);
        state.refresh();
      },
    },
      h('label.field', h('span', 'Nombre'), name),
      h('div.field', h('span', 'Mínimo por semana'), h('div.stepper', h('button.round', { type: 'button', onclick: () => setGoal(goal - 1), 'aria-label': 'Reducir' }, '−'), goalValue, h('button.round', { type: 'button', onclick: () => setGoal(goal + 1), 'aria-label': 'Aumentar' }, '+'))),
      habit ? h('p.hint', 'Cambiar el mínimo recalcula los puntos con el nuevo objetivo.') : null,
      error,
      h('div.actions', h('button.ghost', { type: 'button', onclick: () => close() }, 'Cancelar'), h('button.primary', { type: 'submit' }, habit ? 'Guardar' : 'Crear')),
    );
  });
}

async function archive(habit) {
  if (!(await attempt(() => Pausa.updateHabit(habit.id, { archived: true })))) return;
  toast(`«${habit.name}» archivado`, { undo: async () => { await attempt(() => Pausa.updateHabit(habit.id, { archived: false })); state.refresh(); } });
  state.refresh();
}

async function remove(habit) {
  if (!(await confirmDialog('Eliminar hábito', `Se borrarán «${habit.name}», su historial y sus puntos en todos tus dispositivos. Si solo quieres dejarlo, archívalo.`))) return;
  if ((await attempt(() => Pausa.deleteHabit(habit.id))) === undefined) return;
  toast(`Se borró «${habit.name}»`);
  state.refresh();
}

function explain() {
  dialog('¿Cómo se puntúa?', (close) => h('div.sheet-body',
    h('p', 'Al cumplir el mínimo de una semana: +5 puntos la primera vez. Cada semana seguida crece un 15 %, redondeando hacia arriba.'),
    h('p', 'Las marcas extra suman el 20 % de la semana. Si una semana termina sin llegar al mínimo, el marcador baja un 15 %.'),
    h('p.hint', 'La web calcula los puntos desde las marcas sincronizadas, con el mínimo actual de cada hábito.'),
    h('div.actions', h('button.primary', { onclick: () => close() }, 'Cerrar')),
  ));
}
