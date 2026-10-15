// Cajón: links, ideas and notes dumped in two taps (here, from the phone or by an agent), sorted
// later. Each capture can become a task in a lane, stay in the library or go to the archive. An
// agent's suggestion shows next to it and can be accepted in one click.

import { Pausa } from '../api.js';
import { state } from '../state.js';
import { LANE_LABELS, ago, attempt, confirmDialog, dialog, empty, fill, h, toast } from '../ui.js';

const TABS = [
  { status: 'new', label: 'Por ordenar' },
  { status: 'kept', label: 'Biblioteca' },
  { status: 'filed', label: 'Hechas tarea' },
  { status: 'archived', label: 'Archivo' },
];
const KIND_LABELS = { note: 'Nota', idea: 'Idea', link: 'Enlace', task: 'Tarea', other: 'Otra' };

let tab = 'new';
let search = '';

export async function renderCaptures(root) {
  const list = await Pausa.captures({ status: tab, search: search || undefined, limit: 300 });

  const box = h('textarea', { rows: 2, maxLength: 4000, placeholder: 'Pega un enlace o escribe una idea', 'aria-label': 'Nueva captura' });
  const add = async (event) => {
    event.preventDefault();
    const text = box.value.trim();
    if (!text) return;
    const created = await attempt(() => Pausa.capture(text));
    if (!created) return;
    box.value = '';
    toast('Al cajón', { undo: async () => { await attempt(() => Pausa.deleteCapture(created.id)); state.refresh(); } });
    state.refresh();
  };
  box.addEventListener('keydown', (event) => { if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) add(event); });

  const input = h('input', { type: 'search', value: search, placeholder: 'Buscar en el cajón', 'aria-label': 'Buscar en el cajón' });
  let timer;
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { search = input.value.trim(); state.refresh(); }, 250); });

  fill(root,
    h('header.view-head', h('div', h('p.eyebrow', 'Cajón'), h('h1', list.waiting ? `${list.waiting} por ordenar` : 'Todo ordenado')), h('div.search', input)),
    h('form.card.capture-new', { onsubmit: add }, box, h('div.actions', h('span.hint', 'Ctrl + Enter para guardar'), h('button.primary', { type: 'submit' }, 'Al cajón'))),
    h('div.segmented', { role: 'tablist' }, TABS.map((item) => h('button', {
      role: 'tab', 'aria-selected': String(item.status === tab), class: item.status === tab ? 'active' : '',
      onclick: () => { tab = item.status; state.refresh(); },
    }, item.label))),
    list.items.length
      ? h('div.captures', list.items.map(captureCard))
      : empty(tab === 'new' ? 'Nada esperando en el cajón.' : 'Aquí no hay nada todavía.', tab === 'new' ? 'Lo que apuntes desde Compartir en el móvil o desde aquí aparece en esta lista.' : null),
  );
  if (search) { input.focus(); input.setSelectionRange(input.value.length, input.value.length); }
}

function captureCard(capture) {
  const suggestion = capture.suggestion;
  const tags = capture.tags ? String(capture.tags).split(',') : [];
  const host = capture.url ? safeHost(capture.url) : null;
  return h('article.card.drawer-item',
    h('div.capture-meta',
      capture.kind ? h('span.chip', KIND_LABELS[capture.kind] ?? capture.kind) : null,
      tags.map((tag) => h('span.chip.soft', `#${tag}`)),
      h('span.hint', ago(capture.created_at)),
      capture.source && capture.source !== 'web' ? h('span.hint', `desde ${capture.source}`) : null,
    ),
    capture.title ? h('h3', capture.title) : null,
    h('p.capture-text', capture.text),
    capture.url && capture.url !== capture.text ? h('a.link', { href: capture.url, target: '_blank', rel: 'noopener noreferrer' }, host ?? capture.url) : null,
    capture.url && capture.url === capture.text && host ? h('a.link', { href: capture.url, target: '_blank', rel: 'noopener noreferrer' }, `Abrir en ${host}`) : null,
    suggestion ? h('div.suggestion',
      h('p', h('strong', 'Sugerencia: '), describe(suggestion)),
      suggestion.reason ? h('p.hint', suggestion.reason) : null,
      capture.status === 'new' ? h('button.primary', { onclick: () => accept(capture, suggestion) }, 'Aceptar') : null,
    ) : null,
    capture.task_id ? h('p.hint', 'Convertida en tarea. ', h('a.link', { href: '#/tareas' }, 'Ver tareas')) : null,
    h('div.actions',
      capture.status !== 'filed' ? h('button.ghost', { onclick: () => toTask(capture) }, 'A tarea…') : null,
      capture.status !== 'kept' ? h('button.ghost', { onclick: () => keep(capture) }, 'A la biblioteca…') : null,
      capture.status !== 'archived' ? h('button.ghost', { onclick: () => file(capture, { as: 'archive' }, 'Archivada') }, 'Archivar') : null,
      h('button.ghost.danger-text', { onclick: () => remove(capture) }, 'Eliminar'),
    ),
  );
}

function safeHost(url) {
  try { return new URL(url).host.replace(/^www\./, ''); } catch { return null; }
}

function describe(suggestion) {
  if (suggestion.kind === 'task') {
    return [`tarea «${suggestion.title ?? '…'}»`, suggestion.lane ? `en ${LANE_LABELS[suggestion.lane] ?? suggestion.lane}` : null, suggestion.day ? `para el ${suggestion.day}` : null].filter(Boolean).join(' ');
  }
  return [KIND_LABELS[suggestion.kind]?.toLowerCase() ?? suggestion.kind, suggestion.title ? `«${suggestion.title}»` : null, suggestion.tags?.length ? suggestion.tags.map((tag) => `#${tag}`).join(' ') : null].filter(Boolean).join(' ');
}

async function file(capture, decision, message) {
  const result = await attempt(() => Pausa.fileCapture(capture.id, decision));
  if (!result) return;
  toast(message);
  state.refresh();
}

function accept(capture, suggestion) {
  if (suggestion.kind === 'task') {
    return file(capture, { as: 'task', title: suggestion.title ?? undefined, lane: suggestion.lane ?? 'inbox', day: suggestion.day ?? null, tags: suggestion.tags }, 'Ahora es una tarea');
  }
  return file(capture, { as: 'keep', kind: suggestion.kind, title: suggestion.title ?? undefined, tags: suggestion.tags }, 'Guardada en la biblioteca');
}

async function toTask(capture) {
  const decision = await dialog('Convertir en tarea', (close) => {
    const title = h('input', { value: capture.suggestion?.title ?? capture.title ?? String(capture.text).split('\n')[0].slice(0, 500), maxLength: 500, required: true, autofocus: true });
    const lane = h('select', Object.entries(LANE_LABELS).map(([key, label]) => h('option', { value: key, selected: key === (capture.suggestion?.lane ?? 'inbox') }, label)));
    const day = h('input', { type: 'date', value: capture.suggestion?.day ?? '' });
    return h('form.sheet-body', { onsubmit: (event) => { event.preventDefault(); close({ as: 'task', title: title.value.trim(), lane: lane.value, day: day.value || null }); } },
      h('label.field', h('span', 'Título'), title),
      h('label.field', h('span', 'Carril'), lane),
      h('label.field', h('span', 'Día (opcional)'), day),
      h('p.hint', 'El texto y el enlace quedan en las notas de la tarea.'),
      h('div.actions', h('button.ghost', { type: 'button', onclick: () => close(undefined) }, 'Cancelar'), h('button.primary', { type: 'submit' }, 'Crear tarea')),
    );
  });
  if (decision) await file(capture, decision, 'Ahora es una tarea');
}

async function keep(capture) {
  const decision = await dialog('Guardar en la biblioteca', (close) => {
    const kind = h('select', Object.entries(KIND_LABELS).filter(([key]) => key !== 'task').map(([key, label]) => h('option', { value: key, selected: key === (capture.kind ?? (capture.url ? 'link' : 'note')) }, label)));
    const title = h('input', { value: capture.title ?? '', maxLength: 500, placeholder: 'Opcional' });
    const tags = h('input', { value: capture.tags ?? '', placeholder: 'lectura, casa…', autofocus: true });
    return h('form.sheet-body', { onsubmit: (event) => { event.preventDefault(); close({ as: 'keep', kind: kind.value, title: title.value.trim() || undefined, tags: tags.value }); } },
      h('label.field', h('span', 'Tipo'), kind),
      h('label.field', h('span', 'Título'), title),
      h('label.field', h('span', 'Etiquetas, separadas por comas'), tags),
      h('div.actions', h('button.ghost', { type: 'button', onclick: () => close(undefined) }, 'Cancelar'), h('button.primary', { type: 'submit' }, 'Guardar')),
    );
  });
  if (decision) await file(capture, decision, 'Guardada en la biblioteca');
}

async function remove(capture) {
  if (!(await confirmDialog('Eliminar captura', `«${String(capture.title ?? capture.text).slice(0, 80)}» desaparece del cajón en todos tus dispositivos.`))) return;
  if (await attempt(() => Pausa.deleteCapture(capture.id)) === undefined) return;
  toast('Captura eliminada');
  state.refresh();
}
