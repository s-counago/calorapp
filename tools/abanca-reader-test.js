const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright-core');

// Entirely fictional HTML. Every browser request is fulfilled locally, including navigation.
// No HAR, real customer data, session cookie, or live bank request is used.
const origin = 'https://bancaelectronica.abanca.com';
const routes = {
  overview: '/wele200/General/Posicion/WELE200M_Posicion.aspx',
  account: '/wele200/General/ConsultaMovimientos/WELE200M_ConsultaMovimientos_Res.aspx',
  card: '/wele200/Tarjetas/MovimientosTarjeta/WELE200M_MovimientosTarjeta_Ini.aspx'
};
const head = labels => '<tr>' + labels.map(v => `<th>${v}</th>`).join('') + '</tr>';
const money = v => `<span>${v}<span class="currency">EUR</span></span>`;
const accountRow = (amount = '-12,50', date = '05/10/2026', description = 'Compra ficticia') =>
  `<tr><td>${date}</td><td>06/10/2026</td><td>${description}</td><td>${money(amount)}</td><td>${money('1.234,56')}</td></tr>`;
const account = rows => '<div id="content"><table class="movements">' +
  head(['F.OPERAC.', 'F.VALOR', 'DESCRIPCIÓN', 'IMPORTE', 'SALDO']) + rows + '</table></div>';
const cardRow = () => `<tr><td>Titular ficticio</td><td>05/10/2026</td><td>Compra</td><td>Pendiente</td>
  <td><a href="/detalle?k=DO_NOT_STORE">Tienda ficticia</a></td><td>${money('-8,90')}</td><td>Próxima liquidación</td></tr>`;
const card = rows => '<div id="content"><table class="movements">' +
  head(['', 'F.OPERAC.', 'TIPO OPERACIÓN', 'SITUACIÓN', 'CONCEPTO', 'IMPORTE', 'F.PAGO']) + rows + '</table></div>';
const overview = () => `<div id="content">
  <table id="positions">${head(['-', 'Cuentas y depósitos', 'TIPO', '', 'SALDO'])}
    <tr><td></td><td><a id="tbAccounts_ctl01_ctaLink" href="${routes.account}?k=DO_NOT_STORE">Cuenta de prueba</a></td>
      <td>Corriente</td><td></td><td>${money('1.234,56')}</td></tr>
    <tr><td></td><td></td><td>Total</td><td></td><td>${money('1.234,56')}</td></tr>
  </table>
  <table id="cards">${head(['-', 'Tarjetas', 'TIPO', 'LÍMITE CONCEDIDO', 'SALDO DISPUESTO'])}
    <tr><td></td><td><a id="tbCards_ctl01_lnkTarjeta">Tarjeta de prueba</a></td><td>Crédito</td>
      <td>${money('2.000,00')}</td><td>${money('100,00')}</td></tr>
  </table>
  <table id="loans">${head(['-', 'Préstamos', 'TIPO', 'LÍMITE CONCEDIDO', 'SALDO PENDIENTE'])}
    <tr><td></td><td><a id="tbLoans_ctl01_ptmoLink">Préstamo de prueba</a></td><td>Personal</td>
      <td>${money('5.000,00')}</td><td>${money('3.000,00')}</td></tr>
  </table></div>`;

(async () => {
  const browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    headless: true, args: ['--no-sandbox']});
  let checks = 0;
  try {
    const context = await browser.newContext({serviceWorkers: 'block'});
    let html = '', requests = 0;
    await context.route('**/*', route => { requests++; return route.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: html}); });
    await context.addInitScript(() => {
      Object.defineProperty(document, 'cookie', {get() { throw new Error('cookie read'); }});
      for (const key of ['localStorage', 'sessionStorage'])
        Object.defineProperty(window, key, {get() { throw new Error('storage read'); }});
      window.fetch = () => { throw new Error('network'); };
      window.XMLHttpRequest = function () { throw new Error('network'); };
    });
    const page = await context.newPage();
    const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/banking/read-abanca.js'), 'utf8');
    async function read(body, type = 'account', url = origin + routes[type]) {
      html = body;
      await page.goto(url + '?k=DO_NOT_STORE');
      const before = requests;
      const data = await page.evaluate(script);
      assert.equal(requests, before, 'reader must not issue requests'); checks++;
      return data;
    }
    let data = await read(overview(), 'overview');
    assert.equal(data.status, 'captured');
    assert.deepEqual(data.records.map(r => r.productType), ['account', 'card', 'loan']);
    assert.equal(data.records[0].balance.amount, '1234.56');
    assert.equal(data.records[0].limit, null);
    assert.equal(data.records[1].limit.amount, '2000.00');
    assert.equal(data.records[1].balance.amount, '100.00');
    assert.equal(data.records[2].balance.amount, '3000.00');
    assert.ok(!JSON.stringify(data).includes('DO_NOT_STORE')); checks += 8;

    data = await read(account(accountRow() + accountRow()));
    assert.equal(data.records.length, 2, 'identical transactions retained');
    assert.equal(data.records[0].operationDate, '2026-10-05');
    assert.equal(data.records[0].valueDate, '2026-10-06');
    assert.deepEqual(data.records[0].amount, {amount: '-12.50', currency: 'EUR'});
    assert.equal(data.records[0].balance.amount, '1234.56'); checks += 5;

    data = await read(card(cardRow()), 'card');
    assert.equal(data.records[0].situation, 'Pendiente');
    assert.equal(data.records[0].payment, 'Próxima liquidación');
    assert.equal(data.records[0].operationType, 'Compra');
    assert.equal(data.records[0].amount.amount, '-8.90');
    for (const key of ['Titular ficticio', 'DO_NOT_STORE', 'valueDate', 'balance'])
      assert.ok(!JSON.stringify(data).includes(key)); checks += 8;

    for (const [value, expected] of [['−12,50', '-12.50'], ['+12,50', '12.50'], ['0,00', '0.00'],
      ['-0,00', '0.00'], ['1.234.567,89', '1234567.89'], ['999999999999999999,99', '999999999999999999.99']]) {
      data = await read(account(accountRow(value)));
      assert.equal(data.records[0].amount.amount, expected); checks++;
    }
    for (const value of ['12.50', '1,234.56', '12,345', '1.23,45', 'EUR USD 12,50']) {
      data = await read(account(accountRow(value) + accountRow()));
      assert.equal(data.records.length, 1);
      assert.equal(data.omittedRows, 1); checks += 2;
    }
    data = await read(account(accountRow()).replaceAll('<span class="currency">EUR</span>', ''));
    assert.equal(data.records[0].amount.currency, null); checks++;
    data = await read(account(accountRow('12,50')).replace('<td><span>12,50', '<td class="neg"><span>12,50'));
    assert.equal(data.records[0].amount.amount, '12.50', 'CSS cannot reverse the numeric sign'); checks++;

    data = await read(account(accountRow('-1,00', '31/02/2026') + accountRow('-2,00', '29/02/2024')));
    assert.equal(data.records.length, 1);
    assert.equal(data.omittedRows, 1);
    assert.equal(data.records[0].operationDate, '2024-02-29'); checks += 3;
    for (const field of ['type="password"', 'name="pin_number"', 'autocomplete="one-time-code"']) {
      data = await read(account(accountRow()) + `<input ${field}>`);
      assert.equal(data.status, 'authentication_required'); checks++;
    }
    data = await read(account(accountRow('-1,00', '05/10/2026', 'Compra <span hidden>HIDDEN</span><span aria-hidden="true">ARIA</span>' +
      '<input value="SECRET"><textarea>FORM</textarea><script>window.secret="SCRIPT"</script>' +
      '<span contenteditable="true">EDITABLE</span><span style="display:none">CSS</span>')));
    assert.equal(data.records[0].description, 'Compra'); checks++;
    data = await read(account(accountRow()).replace('<tr><td>', '<tr hidden><td>'));
    assert.equal(data.status, 'no_records'); checks++;
    data = await read(account(accountRow()), 'account', 'https://example.test' + routes.account);
    assert.equal(data.status, 'unsupported_page'); checks++;
    data = await read(account(accountRow()), 'account', origin + '/otra-pagina.aspx');
    assert.equal(data.status, 'unsupported_page'); checks++;
    data = await read(account(accountRow()).replace('F.VALOR', 'Otra columna'));
    assert.equal(data.status, 'unsupported_page'); checks++;
    data = await read('<form>' + account(accountRow()) + '</form>');
    assert.equal(data.status, 'unsupported_page'); checks++;
    data = await read(account(''));
    assert.equal(data.status, 'no_records'); checks++;
    data = await read(account(accountRow().repeat(205)));
    assert.equal(data.records.length, 200);
    assert.equal(data.truncated, true); checks += 2;
    data = await read(account(accountRow('-1,00', '05/10/2026', 'Texto '.repeat(100))));
    assert.equal(data.records[0].description.length, 240);
    assert.equal(data.truncated, true); checks += 2;
    console.log(`PASS: ${checks} ABANCA reader assertions (Chromium, local fictional pages only)`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
