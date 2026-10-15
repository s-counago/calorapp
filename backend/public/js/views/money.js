// Dinero: the summary of each payday-to-payday cycle that the phone uploads (budget_cloud).
// Movements and credentials stay on the phone; this only shows totals, lines, caps and categories.

import { Pausa } from '../api.js';
import { dayMonth, empty, h, fill, dateTime } from '../ui.js';

const euros = (cents, decimals = false) => (cents === null || cents === undefined ? '—'
  : new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', minimumFractionDigits: decimals ? 2 : 0, maximumFractionDigits: decimals ? 2 : 0 }).format(cents / 100));

const STATUS = { late: 'Atrasado', soon: 'Pronto', partial: 'En curso', upcoming: 'Pendiente', reserved: 'Apartado', paid: 'Pagado', skipped: 'Saltado', missed: 'No llegó' };

export async function renderMoney(root, day) {
  const data = await Pausa.budget(/^\d{4}-\d{2}-\d{2}$/.test(day ?? '') ? day : undefined);
  const cycle = data.cycle;
  if (!cycle) {
    fill(root, h('header.view-head', h('div', h('p.eyebrow', 'Dinero'), h('h1', 'Aún no hay datos'))),
      empty('El móvil todavía no ha subido ningún ciclo.', 'Con «Dinero en la web» encendida, abre Dinero en el móvil y sincroniza. Los movimientos y las credenciales no salen del teléfono.'));
    return;
  }
  const caps = cycle.caps;
  const fixed = cycle.lines.filter((line) => !line.cap);
  const categories = cycle.categories.slice().sort((a, b) => b.cents - a.cents);
  const maxCategory = Math.max(1, ...categories.map((item) => item.cents));
  const shortfall = Math.max(0, (cycle.savings_target ?? 0) - (cycle.savings_at_close ?? 0));

  fill(root,
    h('header.view-head',
      h('div', h('p.eyebrow', cycle.current ? 'Dinero · ciclo actual' : 'Dinero'), h('h1', `Del ${dayMonth(cycle.start)} al ${dayMonth(cycle.end)}`)),
      h('label.inline-field', 'Ciclo ', h('select', { onchange: (event) => { location.hash = `#/dinero/${event.target.value}`; } },
        data.cycles.map((item) => h('option', { value: item.start, selected: item.start === cycle.start }, `${dayMonth(item.start)} – ${dayMonth(item.end)}${item.current ? ' (actual)' : ''}`)))),
    ),
    h('div.stat-row',
      stat(cycle.current ? 'Disponible hoy' : 'Libre al cerrar', euros(cycle.available), cycle.current ? `${euros(cycle.daily_allowance)} al día hasta el cobro` : null, cycle.available < 0),
      cycle.current ? stat('Al cierre a este ritmo', euros(cycle.projection), null, cycle.projection < 0) : null,
      stat('Ahorro al cerrar', euros(cycle.savings_at_close), shortfall ? `faltan ${euros(shortfall)} para el objetivo de ${euros(cycle.savings_target)}` : `objetivo ${euros(cycle.savings_target)}`, shortfall > 0),
      stat('Gasto libre', euros(cycle.free_spent), `de ${euros(cycle.free_budget)} libres`),
      stat('Ingresos', euros(cycle.income), `fijos ${euros(cycle.fixed)}`),
      cycle.cushion_left !== null && cycle.cushion_left >= 0 ? stat('Colchón', euros(cycle.cushion_left), cycle.unexpected ? `imprevistos ${euros(cycle.unexpected)}, cubiertos ${euros(cycle.covered)}` : null) : null,
    ),
    h('div.list-columns',
      h('section.card',
        h('h2.section-title', 'Topes'),
        caps.length ? caps.map((line) => {
          const over = line.paid > line.expected;
          return h('div.cap',
            h('div.section-row', h('strong', line.name), h('span.spacer'), h('span', { class: over ? 'warn-text' : '' }, `${euros(line.paid)} de ${euros(line.expected)}`)),
            h('div.bar', { class: over ? 'over' : '' }, h('span', { style: { width: `${Math.min(100, (line.paid / Math.max(1, line.expected)) * 100)}%` } })),
            h('p.hint', over ? `Te has pasado ${euros(line.paid - line.expected)}` : `Quedan ${euros(line.expected - line.paid)}`, line.logged ? ` · ${euros(line.logged)} apuntado a mano` : ''),
          );
        }) : h('p.muted', 'Sin topes.'),
        h('p.hint', 'Los topes se apuntan en el móvil.'),
      ),
      h('section.card',
        h('h2.section-title', 'Gasto libre por categoría'),
        categories.length ? categories.map((item) => h('div.category',
          h('span', item.label), h('div.bar', h('span', { style: { width: `${(item.cents / maxCategory) * 100}%` } })), h('span.amount', euros(item.cents)))) : h('p.muted', 'Nada todavía.'),
      ),
      h('section.card',
        h('h2.section-title', `Fijos (${fixed.length})`),
        fixed.length ? h('table.data', h('thead', h('tr', ['Concepto', 'Previsto', 'Pagado', 'Estado', 'Día'].map((label) => h('th', label)))),
          h('tbody', fixed.map((line) => h('tr', { class: line.status === 'late' ? 'warn-text' : '' },
            h('td', line.name, line.frequency > 1 ? h('small.muted', ` cada ${line.frequency} meses`) : null),
            h('td', euros(line.expected, true)), h('td', euros(line.paid, true)), h('td', STATUS[line.status] ?? line.status), h('td', line.due ? dayMonth(line.due) : '—'))))) : h('p.muted', 'Sin fijos.'),
      ),
    ),
    h('p.hint', `Calculado en el móvil ${dateTime(cycle.computed_at ?? cycle.updated_at)}. Los movimientos y las credenciales no salen del teléfono.`),
  );
}

const stat = (label, value, detail, warn = false) => h('div.card.stat', h('span.muted', label), h('strong', { class: warn ? 'warn-text' : '' }, value), detail ? h('small.muted', detail) : null);
