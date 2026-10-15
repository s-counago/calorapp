// Hoy: counters with the phone's quick buttons, the day plan by slot, the day's habits and
// health. Any day can be opened to correct what was forgotten.

import { Pausa } from '../api.js';
import { state, isOn } from '../state.js';
import { dropTarget, editTask, isOpen, patchTask, quickAdd, taskRow } from '../tasks.js';
import { SLOT_ICONS, SLOT_LABELS, addDays, askNumber, attempt, currentSlot, dialog, empty, h, longDay, number, relativeDay, time, toast, fill } from '../ui.js';
import { healthLine } from './health.js';

const KINDS = {
  calories: { label: 'Calorías', unit: 'kcal', quick: [100, 200, 500], goalKey: 'calorie_goal', goalLabel: 'Límite diario', limit: true },
  protein: { label: 'Proteína', unit: 'g', quick: [10, 25, 50], goalKey: 'protein_goal', goalLabel: 'Objetivo diario', limit: false },
  cigarettes: { label: 'Cigarros', unit: '', quick: [], goalKey: 'cigarette_goal', goalLabel: 'Máximo diario', limit: true },
};

export async function renderToday(root, day) {
  day ??= state.today;
  const [summary, entries, tomorrow] = await Promise.all([
    Pausa.day(day),
    Pausa.entries(day),
    day === state.today ? Pausa.tasks({ plan_date: addDays(day, 1), status: 'todo' }) : Promise.resolve(null),
  ]);
  const isToday = day === state.today;
  fill(root, 
    h('header.view-head',
      h('div',
        h('p.eyebrow', isToday ? 'Hoy' : relativeDay(day, state.today)),
        h('h1', longDay(day)),
      ),
      h('div.day-nav',
        h('a.icon-button', { href: `#/hoy/${addDays(day, -1)}`, 'aria-label': 'Día anterior', title: 'Día anterior' }, '‹'),
        h('input', { type: 'date', value: day, max: addDays(state.today, 60), 'aria-label': 'Elegir día', onchange: (event) => { if (event.target.value) location.hash = `#/hoy/${event.target.value}`; } }),
        h('a.icon-button', { href: `#/hoy/${addDays(day, 1)}`, 'aria-label': 'Día siguiente', title: 'Día siguiente' }, '›'),
        !isToday ? h('a.pill', { href: '#/hoy' }, 'Volver a hoy') : null,
      ),
    ),
    !isToday && day < state.today ? h('p.notice', 'Estás corrigiendo un día pasado: lo que apuntes se guarda en ese día.') : null,
    h('div.today-grid',
      h('section.column', h('h2.section-title', 'Contadores'), Object.keys(KINDS).map((kind) => counterCard(kind, summary.counters[kind], day, entries))),
      h('section.column', planBlock(summary.plan, day, isToday)),
      h('section.column',
        habitsBlock(summary.habits, day),
        isOn('health_connect') ? h('div.card', h('h2.section-title', 'Salud del reloj'), healthLine(summary.health, { full: true }), h('a.link', { href: '#/salud' }, 'Ver la evolución')) : null,
        tomorrow ? tomorrowBlock(tomorrow, day) : null,
        entriesBlock(entries, day),
      ),
    ),
  );
}

// Counters ---------------------------------------------------------------------------

function counterCard(kind, counter, day, entries) {
  const spec = KINDS[kind];
  const { total, goal } = counter;
  const ratio = goal > 0 ? total / goal : 0;
  const over = spec.limit && total > goal;
  const reached = !spec.limit && total >= goal;
  const status = spec.limit
    ? over ? `Límite superado en ${number(total - goal)} ${spec.unit}`.trim() : total === goal ? 'Límite alcanzado' : `Quedan ${number(goal - total)} ${spec.unit}`.trim()
    : reached ? '¡Objetivo cumplido!' : `Faltan ${number(goal - total)} ${spec.unit}`;
  const add = async (amount) => {
    if ((await attempt(() => Pausa.addCounter(kind, amount, day))) === undefined) return;
    toast(`${amount > 0 ? '+' : ''}${number(amount)} ${spec.unit || (Math.abs(amount) === 1 ? 'cigarro' : 'cigarros')}`.trim(), {
      undo: async () => { await attempt(() => Pausa.addCounter(kind, -amount, day)); state.refresh(); },
    });
    state.refresh();
  };
  const other = async () => {
    const value = await askNumber(`Añadir ${spec.label.toLowerCase()}`, { label: `Cantidad${spec.unit ? ` en ${spec.unit}` : ''}`, min: 1, action: 'Añadir' });
    if (value) add(value);
  };
  const correct = async () => {
    const value = await askNumber(`Corregir ${spec.label.toLowerCase()}`, { label: `Total del día${spec.unit ? ` en ${spec.unit}` : ''}`, value: total, hint: 'Corrige lo que olvidaste apuntar. Se guarda en ese día como una corrección.' });
    if (value === undefined) return;
    const result = await attempt(() => Pausa.setCounter(kind, value, day));
    if (!result) return;
    toast(`${spec.label}: ${number(result.previous)} → ${number(value)}`, { undo: async () => { await attempt(() => Pausa.setCounter(kind, result.previous, day)); state.refresh(); } });
    state.refresh();
  };
  const reset = async () => {
    if (!total) return;
    const previous = total;
    if ((await attempt(() => Pausa.setCounter(kind, 0, day))) === undefined) return;
    toast(`${spec.label} a cero`, { undo: async () => { await attempt(() => Pausa.setCounter(kind, previous, day)); state.refresh(); } });
    state.refresh();
  };
  const editGoal = async () => {
    const value = await askNumber(`${spec.goalLabel}: ${spec.label.toLowerCase()}`, { value: goal, min: 1, hint: kind === 'calories' ? 'Kilocalorías al día. El arco se llena al alcanzarlo.' : kind === 'protein' ? 'Gramos al día. La barra se llena al alcanzarlo.' : 'Máximo diario. Cada punto de la tira es uno.' });
    if (value === undefined) return;
    if (await attempt(() => Pausa.setGoals({ [spec.goalKey]: value }))) { toast('Objetivo guardado'); state.refresh(); }
  };
  const count = entries.filter((entry) => entry.kind === kind).length;
  return h('article.card.counter', { class: over ? 'over' : reached ? 'reached' : '' },
    h('div.counter-head',
      h('h3', spec.label),
      h('button.link', { onclick: editGoal, title: 'Cambiar el objetivo' }, `${spec.goalLabel.split(' ')[0]} ${number(goal)}${spec.unit ? ` ${spec.unit}` : ''}`),
    ),
    h('div.counter-value', h('strong', number(total)), spec.unit ? h('span', ` ${spec.unit}`) : null, h('span.muted', ` / ${number(goal)}`)),
    kind === 'cigarettes' ? dots(total, goal) : h('div.bar', h('span', { style: { width: `${Math.min(100, ratio * 100)}%` } })),
    h('p.counter-status', status, count ? h('span.muted', ` · ${count} ${count === 1 ? 'entrada' : 'entradas'}`) : null),
    h('div.counter-buttons',
      kind === 'cigarettes'
        ? [h('button.round', { onclick: () => total > 0 && add(-1), 'aria-label': 'Restar un cigarro', disabled: total <= 0 }, '−'), h('button.round.primary', { onclick: () => add(1), 'aria-label': 'Sumar un cigarro' }, '+')]
        : [...spec.quick.map((amount) => h('button.pill', { onclick: () => add(amount) }, `+${amount}`)), h('button.pill', { onclick: other }, 'Otra')],
      h('span.spacer'),
      h('button.link', { onclick: correct }, 'Corregir'),
      h('button.link', { onclick: reset, disabled: !total }, 'Poner a cero'),
    ),
  );
}

function dots(count, goal) {
  const shown = Math.max(goal, count);
  return h('div.dots', Array.from({ length: shown }, (_, index) => h('span', { class: index < count ? (index >= goal ? 'on over' : 'on') : '' })));
}

function entriesBlock(entries, day) {
  if (!entries.length) return null;
  const label = { calories: 'kcal', protein: 'g proteína', cigarettes: 'cigarro' };
  return h('details.card',
    h('summary.section-title', `Entradas del día (${entries.length})`),
    h('ul.entries', entries.slice().reverse().map((entry) =>
      h('li', h('span.muted', time(entry.at)), h('span', `${entry.amount > 0 ? '+' : ''}${number(entry.amount)} ${label[entry.kind]}`),
        h('button.link', { title: 'Añade la entrada contraria', onclick: async () => { if ((await attempt(() => Pausa.addCounter(entry.kind, -entry.amount, day))) !== undefined) { toast('Entrada compensada'); state.refresh(); } } }, 'Deshacer')),
    )),
    h('p.hint', 'Deshacer añade la entrada contraria, igual que el móvil, para que dos sumas sin conexión nunca se pisen.'),
  );
}

// Plan -------------------------------------------------------------------------------

function planBlock(plan, day, isToday) {
  const now = isToday ? currentSlot() : null;
  const visible = (tasks) => tasks.filter((task) => task.status !== 'cancelled' && !task.following);
  const all = Object.values(plan).flatMap(visible);
  const done = all.filter((task) => task.status === 'done').length;
  const sections = [...Object.keys(SLOT_LABELS), 'unslotted'].map((slot) => {
    const tasks = visible(plan[slot] ?? []).sort((a, b) => (a.position ?? 1e9) - (b.position ?? 1e9) || a.created_at - b.created_at);
    if (slot === 'unslotted' && !tasks.length) return null;
    const section = h('div.slot', { class: slot === now ? 'now' : '' },
      h('h3.slot-title', slot === 'unslotted' ? 'Sin franja' : [h('span.slot-icon', SLOT_ICONS[slot]), SLOT_LABELS[slot]], slot === now ? h('span.chip', 'Ahora') : null),
      tasks.length ? tasks.map((task) => taskRow(task, { hide: ['plan'], actions: [slotMover(task, slot)] })) : h('p.slot-empty', 'Libre'),
      slot !== 'unslotted' ? quickAdd(`Añadir por la ${SLOT_LABELS[slot].toLowerCase()}`, { plan_date: day, plan_slot: slot }) : null,
    );
    return dropTarget(section, (task) => patchTask(task, { plan_date: day, plan_slot: slot === 'unslotted' ? null : slot, following: false }, `Movida a ${slot === 'unslotted' ? 'sin franja' : SLOT_LABELS[slot].toLowerCase()}`));
  });
  return h('div',
    h('div.section-row', h('h2.section-title', 'Tu día'), all.length ? h('span.muted', `${done} de ${all.length} hechas`) : null, h('span.spacer'), h('button.pill', { onclick: () => pickFromList(day) }, 'Añadir de tu lista')),
    all.length ? null : empty('Tu día está en blanco.', 'Añade algo pequeño para empezar, o arrastra tareas desde Semana.'),
    sections,
  );
}

function slotMover(task, slot) {
  const order = ['morning', 'afternoon', 'evening'];
  const index = order.indexOf(slot);
  return [
    index > 0 ? h('button.icon-button', { title: `A la ${SLOT_LABELS[order[index - 1]].toLowerCase()}`, onclick: () => patchTask(task, { plan_slot: order[index - 1] }) }, '↑') : null,
    index >= 0 && index < 2 ? h('button.icon-button', { title: `A la ${SLOT_LABELS[order[index + 1]].toLowerCase()}`, onclick: () => patchTask(task, { plan_slot: order[index + 1] }) }, '↓') : null,
    h('button.icon-button', { title: 'Quitar del plan', onclick: () => patchTask(task, { plan_date: null, plan_slot: null, position: null }, 'Quitada del plan') }, '×'),
  ];
}

/** Like the phone's «Añadir tareas»: pick open tasks from the list (and Prioridad) to plan them on this day. */
async function pickFromList(day) {
  const [list, priority] = await Promise.all([
    attempt(() => Pausa.tasks({ status: 'todo', board: 'none', following: false })),
    isOn('task_kanban') ? attempt(() => Pausa.tasks({ status: 'todo', board: 'priority' })) : Promise.resolve([]),
  ]);
  if (!list) return;
  const candidates = [...list, ...(priority ?? [])].filter((task) => task.plan_date !== day);
  dialog(`Añadir a ${relativeDay(day, state.today).toLowerCase()}`, (close) => {
    const slot = h('select', Object.entries(SLOT_LABELS).map(([key, label]) => h('option', { value: key, selected: key === currentSlot() }, label)));
    const search = h('input', { type: 'search', placeholder: 'Buscar', autofocus: true });
    const listBox = h('div.pick-list');
    const draw = () => {
      const query = search.value.trim().toLowerCase();
      const shown = candidates.filter((task) => !query || task.title.toLowerCase().includes(query)).slice(0, 200);
      listBox.replaceChildren(...(shown.length ? shown.map((task) =>
        h('button.pick', { onclick: async (event) => {
          const button = event.currentTarget;
          if (await attempt(() => Pausa.updateTask(task.id, { plan_date: day, plan_slot: slot.value }))) {
            button.classList.add('picked');
            button.disabled = true;
            candidates.splice(candidates.indexOf(task), 1);
            state.refresh();
          }
        } }, h('span', task.title), task.board ? h('span.chip.soft', 'Prioridad') : task.plan_date ? h('span.chip', relativeDay(task.plan_date, state.today, 'short')) : null),
      ) : [empty('Todo listo. Puedes crear una tarea arriba.')]));
    };
    search.addEventListener('input', draw);
    draw();
    return h('div.sheet-body', h('p.hint', 'Toca tareas para añadirlas al instante.'), h('div.grid-2', h('label.field', h('span', 'Buscar'), search), h('label.field', h('span', 'Franja'), slot)), listBox,
      h('div.actions', h('button.primary', { onclick: () => close() }, 'Listo')));
  }, { wide: true });
}

function tomorrowBlock(tasks, day) {
  const open = tasks.filter((task) => isOpen(task) && !task.following);
  return h('div.card',
    h('div.section-row', h('h2.section-title', 'Mañana'), h('span.spacer'), h('a.link', { href: `#/hoy/${addDays(day, 1)}` }, 'Abrir')),
    open.length ? h('ul.compact', open.map((task) => h('li', h('button.link', { onclick: () => editTask(task) }, task.title), task.plan_slot ? h('span.muted', ` · ${SLOT_LABELS[task.plan_slot].toLowerCase()}`) : null)))
      : h('p.muted', 'Prepara mañana con calma.'),
    quickAdd('Añadir a mañana', { plan_date: addDays(day, 1) }),
  );
}

// Habits -----------------------------------------------------------------------------

function habitsBlock(habits, day) {
  return h('div.card',
    h('div.section-row', h('h2.section-title', 'Hábitos de hoy'), h('span.spacer'), h('a.link', { href: '#/habitos' }, 'Abrir hábitos')),
    habits.length
      ? h('ul.habit-checks', habits.map((habit) =>
        h('li', h('label.check-field', { class: habit.done ? 'done' : '' },
          h('input', { type: 'checkbox', checked: habit.done, onchange: async (event) => {
            const done = event.target.checked;
            if ((await attempt(() => Pausa.checkHabit(habit.id, day, done))) === undefined) { event.target.checked = !done; return; }
            toast(done ? `«${habit.name}» hecho` : 'Marca deshecha', { undo: async () => { await attempt(() => Pausa.checkHabit(habit.id, day, !done)); state.refresh(); } });
            state.refresh();
          } }),
          h('span', habit.name)))))
      : h('p.muted', 'Aún no tienes hábitos.'),
  );
}
