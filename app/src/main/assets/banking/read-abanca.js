/* ABANCA HTML reader. Passive: no requests, clicks, form values, URLs in output,
 * cookies or storage. Only known tables from three authenticated page layouts.
 */
(() => {
  'use strict';
  const unsupported = () => ({status: 'unsupported_page'});
  const paths = {
    '/wele200/general/posicion/wele200m_posicion.aspx': 'overview',
    '/wele200/general/consultamovimientos/wele200m_consultamovimientos_res.aspx': 'account',
    '/wele200/tarjetas/movimientostarjeta/wele200m_movimientostarjeta_ini.aspx': 'card'
  };
  if (location.protocol !== 'https:' || location.hostname !== 'bancaelectronica.abanca.com' ||
      (location.port && location.port !== '443')) return unsupported();
  const excluded = 'form, input, textarea, select, button, nav, header, footer, script, style, ' +
    '[contenteditable="true"], [aria-hidden="true"], [hidden], #header, #footer, #sidebar';
  const visible = el => !!el && el.getClientRects().length > 0 &&
    getComputedStyle(el).visibility !== 'hidden' && getComputedStyle(el).display !== 'none';
  if ([...document.querySelectorAll('input[type="password"], input[name="pin_number"], input[autocomplete="one-time-code"]')]
    .some(visible)) return {status: 'authentication_required'};
  const pageType = paths[location.pathname.toLowerCase()];
  const root = document.querySelector('#content');
  if (!pageType || !root || !visible(root) || root.closest(excluded)) return unsupported();
  let truncated = false, omittedRows = 0;
  const records = [];
  const text = (el, limit = 240) => {
    if (!visible(el) || el.closest(excluded)) return '';
    const parts = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let size = 0;
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (node.parentElement.closest(excluded) || !visible(node.parentElement)) continue;
      const value = node.textContent.replace(/\s+/g, ' ').trim();
      if (!value) continue;
      parts.push(value); size += value.length + 1;
      if (size > limit) break;
    }
    const value = parts.join(' ');
    if (value.length > limit) { truncated = true; return value.slice(0, limit - 1) + '…'; }
    return value;
  };
  const normalized = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .toUpperCase().replace(/[^A-Z0-9]/g, '');
  const headersMatch = (table, expected) => {
    const row = table.rows[0];
    return row && [...row.cells].every(cell => cell.tagName === 'TH') &&
      row.cells.length === expected.length &&
      [...row.cells].every((cell, i) => normalized(text(cell, 80)) === expected[i]);
  };
  const money = cell => {
    let value = text(cell, 80).replace(/\u2212/g, '-');
    if (!value || /^[-—–]$/.test(value)) return null;
    const currencies = value.match(/\b[A-Z]{3}\b|€/g) || [];
    const codes = [...new Set(currencies.map(c => c === '€' ? 'EUR' : c))];
    if (codes.length > 1) throw new Error('currency');
    value = value.replace(/\b[A-Z]{3}\b|€/g, '').replace(/\s/g, '');
    if (!/^[+-]?(?:\d{1,3}(?:\.\d{3})+|\d+)(?:,\d{2})?$/.test(value)) throw new Error('amount');
    value = value.replace(/\./g, '').replace(',', '.');
    const negative = value.startsWith('-');
    value = value.replace(/^[+-]/, '');
    let [integer, decimal = '00'] = value.split('.');
    integer = integer.replace(/^0+(?=\d)/, '');
    if (integer.length > 18) throw new Error('amount');
    return {amount: (negative && (integer !== '0' || decimal !== '00') ? '-' : '') + integer + '.' + decimal,
      currency: codes[0] || null};
  };
  const date = cell => {
    const value = text(cell, 32);
    const match = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value);
    if (!match) throw new Error('date');
    const [, day, month, year] = match;
    const d = new Date(Date.UTC(+year, +month - 1, +day));
    if (d.getUTCFullYear() !== +year || d.getUTCMonth() !== +month - 1 || d.getUTCDate() !== +day)
      throw new Error('date');
    return `${year}-${month}-${day}`;
  };
  const append = record => {
    if (records.length < 200) records.push(record); else truncated = true;
  };
  if (pageType === 'overview') {
    const groups = [
      ['positions', 'account', '_ctaLink', ['', 'CUENTASYDEPOSITOS', 'TIPO', '', 'SALDO']],
      ['cards', 'card', '_lnkTarjeta', ['', 'TARJETAS', 'TIPO', 'LIMITECONCEDIDO', 'SALDODISPUESTO']],
      ['loans', 'loan', '_ptmoLink', ['', 'PRESTAMOS', 'TIPO', 'LIMITECONCEDIDO', 'SALDOPENDIENTE']]
    ];
    let recognized = 0;
    for (const [id, productType, suffix, headers] of groups) {
      const table = root.querySelector(`table#${id}`);
      if (!table || !visible(table)) continue;
      if (!headersMatch(table, headers)) return unsupported();
      recognized++;
      for (const row of [...table.rows].slice(1)) {
        if (!visible(row) || row.closest(excluded)) continue;
        const link = row.querySelector(`a[id$="${suffix}"]`);
        // Totals and empty placeholders are not separate products.
        if (!link) continue;
        try {
          const cells = [...row.cells];
          if (cells.length !== 5 || cells.some(c => c.colSpan !== 1)) throw new Error('columns');
          const label = text(link, 120), kind = text(cells[2], 80);
          const balance = money(cells[4]);
          if (!label || !balance) throw new Error('product');
          append({productType, label, kind, balance, limit: productType === 'account' ? null : money(cells[3])});
        } catch (_) { omittedRows++; }
      }
    }
    if (!recognized) return unsupported();
  } else {
    const tables = [...root.querySelectorAll('table.movements')].filter(visible);
    if (tables.length !== 1) return unsupported();
    const table = tables[0];
    const expected = pageType === 'account'
      ? ['FOPERAC', 'FVALOR', 'DESCRIPCION', 'IMPORTE', 'SALDO']
      : ['', 'FOPERAC', 'TIPOOPERACION', 'SITUACION', 'CONCEPTO', 'IMPORTE', 'FPAGO'];
    if (!headersMatch(table, expected)) return unsupported();
    for (const row of [...table.rows].slice(1)) {
      if (!visible(row) || row.closest(excluded)) continue;
      try {
        const c = [...row.cells];
        if (c.length !== expected.length || c.some(cell => cell.tagName !== 'TD' || cell.colSpan !== 1))
          throw new Error('columns');
        const description = text(c[pageType === 'account' ? 2 : 4]);
        const amount = money(c[pageType === 'account' ? 3 : 5]);
        if (!description || !amount) throw new Error('movement');
        if (pageType === 'account') {
          const balance = money(c[4]);
          if (!balance) throw new Error('balance');
          append({operationDate: date(c[0]), valueDate: date(c[1]), description, amount, balance});
        } else {
          append({operationDate: date(c[1]), operationType: text(c[2], 80), situation: text(c[3], 80),
            description, amount, payment: text(c[6], 80)});
        }
      } catch (_) { omittedRows++; }
    }
  }
  if (!records.length) return {status: 'no_records'};
  return {status: 'captured', reader: 'abanca-html-v1', pageType, partial: true,
    truncated, omittedRows, records};
})()
