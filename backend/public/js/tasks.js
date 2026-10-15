// Task pieces shared by Hoy, Semana, Lista, Tablero and Seguimientos: a row, its menu,
// the editor and the quick-add form.

import { Pausa } from './api.js';
import { state } from './state.js';
import { LANE_LABELS, SLOT_LABELS, addDays, attempt, clock, confirmDialog, dateTime, dayMonth, dialog, h, relativeDay, toast } from './ui.js';

export const isOpen = (task) => task.status === 'todo';
export const laneOf = (task) => task.lane ?? (task.following ? 'waiting' : task.board ?? 'inbox');

/** Order inside a day: set times first, then the phone's position, then creation. */
export const byPlan = (a, b) => (a.plan_time ?? 1e9) - (b.plan_time ?? 1e9) || (a.position ?? 1e9) - (b.position ?? 1e9) || a.created_at - b.created_at;

const APPOINTMENT = ['plan_date', 'plan_slot', 'plan_time', 'plan_minutes', 'position'];

/** Plans a task on a day and slot (or takes it off with day null), with undo. A set time is dropped. */
export async function planOn(task, day, slot = null, message) {
  const before = Object.fromEntries(APPOINTMENT.map((key) => [key, task[key] ?? null]));
  const updated = await attempt(() => Pausa.planTask(task.id, { day, slot }));
  if (!updated) return null;
  const text = message ?? (day ? `Planificada ${relativeDay(day, state.today).toLowerCase()}${slot ? ` · ${SLOT_LABELS[slot].toLowerCase()}` : ''}` : 'Quitada del plan');
  toast(text, { undo: async () => { await attempt(() => Pausa.updateTask(task.id, before)); state.refresh(); } });
  state.refresh();
  return updated;
}

/** Moves a task to another lane, with undo. Its day, if any, stays. */
export async function moveTo(task, lane) {
  const previous = laneOf(task);
  if (previous === lane) return;
  if (!(await attempt(() => Pausa.moveTask(task.id, lane)))) return;
  toast(`Movida a ${LANE_LABELS[lane].toLowerCase()}`, { undo: async () => { await attempt(() => Pausa.moveTask(task.id, previous)); state.refresh(); } });
  state.refresh();
}

/** Marks a task done or not, with undo. */
export async function toggleDone(task) {
  const done = task.status !== 'done';
  const updated = await attempt(() => Pausa.updateTask(task.id, { status: done ? 'done' : 'todo' }));
  if (!updated) return;
  let message = done ? `«${task.title}» hecha` : `«${task.title}» pendiente otra vez`;
  if (done && task.board) {
    const board = await attempt(() => Pausa.board());
    if (board) message = `Hecha. Llevas ${board.done_total.toLocaleString('es-ES')}`;
  }
  toast(message, { undo: async () => { await attempt(() => Pausa.updateTask(task.id, { status: task.status })); state.refresh(); } });
  state.refresh();
}

export async function patchTask(task, patch, message) {
  const updated = await attempt(() => Pausa.updateTask(task.id, patch));
  if (!updated) return null;
  if (message) {
    const before = Object.fromEntries(Object.keys(patch).map((key) => [key, task[key] ?? null]));
    toast(message, { undo: async () => { await attempt(() => Pausa.updateTask(task.id, before)); state.refresh(); } });
  }
  state.refresh();
  return updated;
}

export async function removeTask(task) {
  const ok = await confirmDialog('Eliminar tarea', `«${task.title}» desaparecerá también del móvil.`);
  if (!ok) return;
  if ((await attempt(() => Pausa.deleteTask(task.id))) === undefined) return;
  toast(`«${task.title}» eliminada`, {
    undo: async () => {
      const { id, updated_at, completed_at, ...rest } = task;
      await attempt(() => Pausa.createTask({ ...rest, id }));
      state.refresh();
    },
  });
  state.refresh();
}

/** Small labels after the title: when it is planned, its time, when it is due, its lane, who it waits for. */
function chips(task, { hide = [] } = {}) {
  const list = [];
  const lane = laneOf(task);
  if (task.plan_date && !hide.includes('plan')) {
    const overdue = isOpen(task) && task.plan_date < state.today;
    list.push(h('span.chip', { class: overdue ? 'warn' : '' }, relativeDay(task.plan_date, state.today, 'short') + (task.plan_slot && task.plan_time === null ? ` · ${SLOT_LABELS[task.plan_slot].toLowerCase()}` : '')));
  }
  if (task.plan_time !== null && task.plan_time !== undefined) list.push(h('span.chip.time', clock(task.plan_time) + (task.plan_minutes ? ` · ${task.plan_minutes} min` : '')));
  else if (task.plan_minutes) list.push(h('span.chip.soft', `${task.plan_minutes} min`));
  if (task.due_date) list.push(h('span.chip', { class: isOpen(task) && task.due_date <= state.today ? 'warn' : '' }, `vence ${dayMonth(task.due_date)}`));
  if (lane !== 'inbox' && !hide.includes('lane')) list.push(h('span.chip.soft', { class: `lane-${lane}` }, lane === 'waiting' && task.waiting_for ? `esperando: ${task.waiting_for}` : LANE_LABELS[lane]));
  if (task.habit_id) list.push(h('span.chip.soft', 'hábito'));
  if (task.project) list.push(h('span.chip.soft', task.project));
  if (task.area) list.push(h('span.chip.soft', task.area));
  if (task.priority) list.push(h('span.chip.soft', `P${task.priority}`));
  if (task.notes) list.push(h('span.chip.soft', { title: task.notes }, 'notas'));
  return list;
}

/**
 * One task: checkbox, title (opens the editor), chips and a menu. `actions` are extra buttons for
 * the context (e.g. «Quitar del plan» in a day). Rows can be dragged onto days and columns.
 */
export function taskRow(task, { actions = [], hide = [], draggable = true } = {}) {
  const row = h('div.task', {
    class: task.status === 'done' ? 'done' : task.status === 'cancelled' ? 'cancelled' : '',
    draggable: draggable ? 'true' : undefined,
    dataset: { id: task.id },
    ondragstart: (event) => {
      event.dataTransfer.setData('application/x-pausa-task', JSON.stringify(task));
      event.dataTransfer.effectAllowed = 'move';
      row.classList.add('dragging');
    },
    ondragend: () => row.classList.remove('dragging'),
  },
    h('button.check', { 'aria-label': task.status === 'done' ? 'Marcar como pendiente' : 'Marcar como hecha', onclick: () => toggleDone(task) }, task.status === 'done' ? '✓' : ''),
    h('div.task-main',
      h('button.task-title', { onclick: () => editTask(task) }, task.title),
      h('div.chips', chips(task, { hide })),
    ),
    h('div.task-actions', actions, h('button.icon-button', { 'aria-label': 'Más acciones', title: 'Más acciones', onclick: (event) => taskMenu(task, event.currentTarget) }, '⋯')),
  );
  return row;
}

let openMenu = null;
export function popover(anchor, items) {
  openMenu?.remove();
  const menu = h('div.popover', { role: 'menu' },
    items.filter(Boolean).map((item) => item === '-' ? h('hr') : h('button', { role: 'menuitem', class: item.danger ? 'danger-text' : '', onclick: () => { menu.remove(); openMenu = null; item.run(); } }, item.label)),
  );
  document.body.append(menu);
  const rect = anchor.getBoundingClientRect();
  const width = menu.offsetWidth;
  menu.style.top = `${Math.min(rect.bottom + scrollY + 4, scrollY + innerHeight - menu.offsetHeight - 8)}px`;
  menu.style.left = `${Math.max(8, Math.min(rect.right + scrollX - width, scrollX + innerWidth - width - 8))}px`;
  openMenu = menu;
  setTimeout(() => document.addEventListener('click', function close(event) {
    if (!menu.contains(event.target)) { menu.remove(); if (openMenu === menu) openMenu = null; document.removeEventListener('click', close); }
  }), 0);
}

function taskMenu(task, anchor) {
  const today = state.today;
  const tomorrow = addDays(today, 1);
  const lane = laneOf(task);
  popover(anchor, [
    task.plan_date !== today && { label: 'Planificar hoy', run: () => planOn(task, today) },
    task.plan_date !== tomorrow && { label: 'Planificar mañana', run: () => planOn(task, tomorrow) },
    task.plan_date && { label: 'Quitar del plan', run: () => planOn(task, null) },
    '-',
    { label: 'Mover a…', run: () => laneMenu(task, anchor) },
    lane !== 'failed' && task.status === 'todo' && { label: 'Abandonar (a fracasos)', run: () => moveTo(task, 'failed') },
    '-',
    { label: 'Editar…', run: () => editTask(task) },
    { label: 'Historial de cambios…', run: () => showHistory('tasks', task.id, task.title) },
    { label: 'Eliminar', danger: true, run: () => removeTask(task) },
  ]);
}

export function laneMenu(task, anchor) {
  setTimeout(() => popover(anchor, Object.entries(LANE_LABELS).filter(([key]) => key !== laneOf(task)).map(([key, label]) => ({ label, run: () => moveTo(task, key) }))), 0);
}

/** Every saved version of a record and who wrote it: phone, web or agent. */
export async function showHistory(table, id, title) {
  const versions = await attempt(() => Pausa.history(table, id));
  if (!versions) return;
  dialog(`Historial · ${title}`, () =>
    h('div.sheet-body',
      versions.length ? h('ol.history', versions.slice().reverse().map((version) =>
        h('li',
          h('div.history-head', h('strong', dateTime(version.updated_at)), h('span.chip.soft', version.actor ?? '—'), version.deleted ? h('span.chip.warn', 'borrado') : null),
          h('pre', JSON.stringify(version.data, null, 1)),
          version.request_id ? h('p.hint', `request_id ${version.request_id}`) : null,
        ))) : h('p', 'Sin historial.'),
    ), { wide: true });
}

const field = (label, input, hint) => h('label.field', h('span', label), input, hint ? h('small.hint', hint) : null);

/** Full editor. `defaults` pre-fill a new task. Resolves when saved or closed. */
export function editTask(task, defaults = {}) {
  const isNew = !task;
  const value = { status: 'todo', ...defaults, ...(task ?? {}) };
  return dialog(isNew ? 'Nueva tarea' : 'Editar tarea', (close) => {
    const title = h('input', { value: value.title ?? '', required: true, maxLength: 500, autofocus: true, placeholder: '¿Qué quieres hacer?' });
    const notes = h('textarea', { rows: 3, placeholder: 'Notas (opcional)' }, value.notes ?? '');
    const planDate = h('input', { type: 'date', value: value.plan_date ?? '' });
    const slot = h('select', h('option', { value: '' }, 'Sin franja'), Object.entries(SLOT_LABELS).map(([key, label]) => h('option', { value: key, selected: value.plan_slot === key }, label)));
    const quick = (label, day) => h('button.pill', { type: 'button', onclick: () => { planDate.value = day ?? ''; } }, label);
    const due = h('input', { type: 'date', value: value.due_date ?? '' });
    const lane = h('select', Object.entries(LANE_LABELS).map(([key, label]) => h('option', { value: key, selected: (value.lane ?? laneOf(value)) === key }, label)));
    const at = h('input', { type: 'time', value: value.plan_time === null || value.plan_time === undefined ? '' : clock(value.plan_time) });
    const length = h('input', { type: 'number', min: 1, max: 1440, value: value.plan_minutes ?? '', placeholder: 'min' });
    const waitingFor = h('input', { value: value.waiting_for ?? '', placeholder: 'De quién o de qué depende' });
    const expected = h('input', { type: 'date', value: value.expected_date ?? '' });
    const review = h('input', { type: 'date', value: value.review_date ?? '' });
    const followBox = h('div.follow-fields', field('Esperando a', waitingFor), h('div.grid-2', field('Fecha prevista', expected), field('Volver a revisar', review)));
    const syncFollow = () => { followBox.hidden = lane.value !== 'waiting'; };
    lane.addEventListener('change', syncFollow);
    syncFollow();
    const project = h('input', { value: value.project ?? '', placeholder: 'Proyecto' });
    const area = h('input', { value: value.area ?? '', placeholder: 'salud, casa, trabajo…' });
    const priority = h('input', { type: 'number', min: 1, max: 9, value: value.priority ?? '', placeholder: '1 es la más alta' });
    const status = h('select', [['todo', 'Pendiente'], ['done', 'Hecha'], ['cancelled', 'Abandonada']].map(([key, label]) => h('option', { value: key, selected: value.status === key }, label)));
    const error = h('p.field-error');

    const submit = async (event) => {
      event.preventDefault();
      if (!title.value.trim()) { error.textContent = 'Escribe una tarea.'; return; }
      const waiting = lane.value === 'waiting';
      const time = planDate.value && at.value ? at.value : null;
      const slotForTime = (text) => { const minutes = Number(text.slice(0, 2)) * 60 + Number(text.slice(3, 5)); return minutes < 720 ? 'morning' : minutes < 1200 ? 'afternoon' : 'evening'; };
      const data = {
        title: title.value.trim(),
        notes: notes.value.trim() || null,
        plan_date: planDate.value || null,
        plan_slot: planDate.value ? slot.value || (time ? slotForTime(time) : null) : null,
        plan_time: time,
        plan_minutes: planDate.value && length.value ? Number(length.value) : null,
        due_date: due.value || null,
        lane: lane.value,
        waiting_for: waiting ? waitingFor.value.trim() || null : null,
        expected_date: waiting ? expected.value || null : null,
        review_date: waiting ? review.value || null : null,
        project: project.value.trim() || null,
        area: area.value.trim() || null,
        priority: priority.value ? Number(priority.value) : null,
        status: status.value,
      };
      if (!planDate.value) data.position = null;
      if (data.plan_minutes !== null && !(data.plan_minutes >= 1 && data.plan_minutes <= 1440)) { error.textContent = 'La duración va de 1 a 1440 minutos.'; return; }
      const saved = await attempt(() => (isNew ? Pausa.createTask(data) : Pausa.updateTask(task.id, data)));
      if (!saved) return;
      toast(isNew ? 'Tarea añadida' : 'Tarea guardada');
      close(saved);
      state.refresh();
    };

    return h('form.sheet-body', { onsubmit: submit },
      field('Tarea', title),
      field('Notas', notes),
      h('fieldset',
        h('legend', 'Cuándo'),
        h('div.pills', quick('Hoy', state.today), quick('Mañana', addDays(state.today, 1)), quick('Sin día', null)),
        h('div.grid-2', field('Día', planDate), field('Franja', slot)),
        h('div.grid-2', field('Hora (opcional)', at), field('Duración en minutos', length)),
        field('Fecha límite', due),
      ),
      h('fieldset',
        h('legend', 'Dónde'),
        h('div.grid-2', field('Carril', lane), field('Estado', status)),
        followBox,
      ),
      h('details', h('summary', 'Más detalles'), h('div.grid-3', field('Proyecto', project), field('Área', area), field('Prioridad', priority))),
      error,
      h('div.actions',
        !isNew ? h('button.danger-text', { type: 'button', onclick: async () => { close(); await removeTask(task); } }, 'Eliminar') : null,
        h('span.spacer'),
        h('button.ghost', { type: 'button', onclick: () => close() }, 'Cancelar'),
        h('button.primary', { type: 'submit' }, isNew ? 'Añadir' : 'Guardar'),
      ),
      !isNew ? h('p.hint', `Creada ${dateTime(task.created_at)}${task.completed_at ? ` · hecha ${dateTime(task.completed_at)}` : ''}${task.source ? ` · origen ${task.source}` : ''}`) : null,
    );
  }, { wide: true });
}

/** A one-line add box. `defaults` decide where the new task lands (a day, a slot, a column). */
export function quickAdd(placeholder, defaults = {}, { onAdded, compact = false } = {}) {
  const input = h('input', { placeholder, maxLength: 500, 'aria-label': placeholder });
  const form = h('form.quick-add', {
    onsubmit: async (event) => {
      event.preventDefault();
      const title = input.value.trim();
      if (!title) return;
      input.disabled = true;
      const created = await attempt(() => Pausa.createTask({ title, ...(typeof defaults === 'function' ? defaults() : defaults) }));
      input.disabled = false;
      if (!created) return;
      input.value = '';
      input.focus();
      toast(`«${title}» añadida`, { undo: async () => { await attempt(() => Pausa.deleteTask(created.id)); state.refresh(); } });
      onAdded?.(created);
      state.refresh();
    },
  }, input,
    compact ? null : h('button.ghost', { type: 'button', title: 'Con todos los detalles', onclick: () => editTask(null, { title: input.value.trim(), ...(typeof defaults === 'function' ? defaults() : defaults) }) }, '…'),
    h('button.primary', { type: 'submit', 'aria-label': 'Añadir', title: 'Añadir' }, compact ? '+' : 'Añadir'));
  if (compact) form.classList.add('compact');
  return form;
}

/** Makes an element accept dropped tasks; `onDrop(task)` decides what to change. */
export function dropTarget(element, onDrop) {
  element.addEventListener('dragover', (event) => {
    if (!event.dataTransfer.types.includes('application/x-pausa-task')) return;
    event.preventDefault();
    element.classList.add('drop-over');
  });
  element.addEventListener('dragleave', (event) => { if (!element.contains(event.relatedTarget)) element.classList.remove('drop-over'); });
  element.addEventListener('drop', (event) => {
    element.classList.remove('drop-over');
    const raw = event.dataTransfer.getData('application/x-pausa-task');
    if (!raw) return;
    event.preventDefault();
    onDrop(JSON.parse(raw));
  });
  return element;
}
