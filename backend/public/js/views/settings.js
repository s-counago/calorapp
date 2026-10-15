// Ajustes: features, goals, signed-in devices and the diagnostics a debugging session needs
// (sync status per device and the latest client logs).

import { Pausa, WEB_VERSION } from '../api.js';
import { state } from '../state.js';
import { ago, attempt, confirmDialog, dateTime, h, number, toast, fill } from '../ui.js';

let logLevel = 'warn';

export async function renderSettings(root) {
  const [features, goals, status, logs, board] = await Promise.all([
    Pausa.features(),
    Pausa.goals(),
    Pausa.status(),
    Pausa.logs({ level: logLevel, limit: 50 }),
    Pausa.board(),
  ]);

  const goalInput = (key, label, unit) => {
    const input = h('input', { type: 'number', min: 0, step: 1, value: goals[key] });
    input.addEventListener('change', async () => {
      const value = Number(input.value);
      if (!Number.isInteger(value) || value < 0) { toast('Escribe un número entero', { error: true }); return; }
      if (await attempt(() => Pausa.setGoals({ [key]: value }))) toast(`${label}: ${number(value)} ${unit}`.trim());
    });
    return h('label.field', h('span', label), h('div.with-unit', input, unit ? h('span.muted', unit) : null));
  };

  fill(root, 
    h('header.view-head', h('div', h('p.eyebrow', 'Pausa'), h('h1', 'Ajustes'))),
    h('div.settings-grid',
      h('section.card',
        h('h2.section-title', 'Funciones'),
        h('p.hint', 'Enciende solo lo que te ayude. Se sincroniza con el móvil en su próxima sincronización.'),
        features.map((feature) => h('label.switch-row',
          h('div', h('strong', feature.title), h('p.muted', feature.description)),
          h('input', { type: 'checkbox', role: 'switch', checked: feature.enabled, onchange: async (event) => {
            const enabled = event.target.checked;
            const result = await attempt(() => Pausa.setFeature(feature.key, enabled));
            if (!result) { event.target.checked = !enabled; return; }
            state.features[feature.key] = enabled;
            toast(`${feature.title}: ${enabled ? 'encendida' : 'apagada'}`);
            state.refresh({ shell: true });
          } }),
        )),
      ),
      h('section.card',
        h('h2.section-title', 'Objetivos diarios'),
        h('div.grid-3', goalInput('calorie_goal', 'Límite de calorías', 'kcal'), goalInput('protein_goal', 'Objetivo de proteína', 'g'), goalInput('cigarette_goal', 'Máximo de cigarros', '')),
        h('p.hint', `Tareas hechas en total: ${board.done_total.toLocaleString('es-ES')}. Se corrige desde el Tablero.`),
      ),
      h('section.card',
        h('h2.section-title', 'Dispositivos y sincronización'),
        h('p.hint', `Servidor en el cambio ${status.server_seq}. Errores del móvil en 24 h: ${status.client_errors_last_24h}.`),
        h('table.data',
          h('thead', h('tr', ['Dispositivo', 'Versión', 'Último pull', 'Último push', 'Por detrás', 'Último error', ''].map((label) => h('th', label)))),
          h('tbody', status.devices.map((device) => h('tr', { class: device.revoked_at ? 'muted' : '' },
            h('td', device.name ?? '—', device.revoked_at ? ' (revocado)' : ''),
            h('td', device.app_version ?? '—'),
            h('td', { title: dateTime(device.last_pull_at) }, ago(device.last_pull_at)),
            h('td', { title: dateTime(device.last_push_at) }, ago(device.last_push_at), device.last_push_count ? ` · ${device.last_push_count}` : ''),
            h('td', device.behind_by ?? '—'),
            h('td', device.last_error_at ? dateTime(device.last_error_at) : '—'),
            h('td', device.revoked_at ? null : h('button.danger-text', { onclick: async () => {
              if (!(await confirmDialog('Cerrar sesión del dispositivo', `«${device.name}» dejará de sincronizar hasta que vuelvas a entrar desde Ajustes → Nube en el móvil.`, 'Revocar'))) return;
              if ((await attempt(() => Pausa.revokeDevice(device.id))) !== undefined) { toast('Dispositivo revocado'); state.refresh(); }
            } }, 'Revocar')),
          ))),
        ),
        h('details', h('summary', 'Registros guardados'), h('table.data', h('tbody', Object.entries(status.records).map(([table, counts]) => h('tr', h('td', table), h('td', `${number(counts.live)} vivos`), h('td', `${number(counts.deleted)} borrados`)))))),
      ),
      h('section.card.wide',
        h('div.section-row', h('h2.section-title', 'Logs de las apps'), h('span.spacer'),
          h('select', { 'aria-label': 'Nivel mínimo', onchange: (event) => { logLevel = event.target.value; state.refresh(); } },
            [['debug', 'Todo'], ['info', 'Info y más'], ['warn', 'Avisos y errores'], ['error', 'Solo errores']].map(([value, label]) => h('option', { value, selected: value === logLevel }, label)))),
        logs.length ? h('table.data.logs',
          h('thead', h('tr', ['Cuándo', 'Nivel', 'Origen', 'Etiqueta', 'Mensaje', 'request_id'].map((label) => h('th', label)))),
          h('tbody', logs.map((entry) => h('tr', { class: `level-${entry.level}` },
            h('td', dateTime(entry.at)), h('td', entry.level), h('td', entry.device_id ? (entry.app_version ?? 'móvil') : (entry.app_version ?? 'web')), h('td', entry.tag),
            h('td', h('span', entry.message), entry.data ? h('details', h('summary', 'datos'), h('pre', JSON.stringify(entry.data, null, 1))) : null),
            h('td.mono', entry.request_id ?? ''),
          ))),
        ) : h('p.muted', 'Sin logs con ese nivel en los últimos 30 días.'),
      ),
      h('section.card',
        h('h2.section-title', 'Sesión'),
        h('p.hint', `Web ${WEB_VERSION}. Hoy es ${state.today} (el día cierra a las 03:00, hora de Madrid).`),
        h('form', { method: 'post', action: '/auth/logout' }, h('button.ghost', { type: 'submit' }, 'Cerrar sesión en este navegador')),
      ),
    ),
  );
}
