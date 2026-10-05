const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright-core');

// Synthetic fixtures only. No bank access, credentials or saved browser profile.
(async () => {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    headless: true,
    args: ['--no-sandbox'],
  });
  let checks = 0;
  try {
    const page = await browser.newPage();
    const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/banking/read-visible.js'), 'utf8');
    async function read(html) {
      await page.setContent(html);
      return page.evaluate(script);
    }
    let data = await read('<main><p>Saldo disponible <span>1.234,56 EUR</span></p><table><tr><td>2026-10-01</td><td>Compra</td><td>−12,50 €</td></tr><tr><td>2026-10-01</td><td>Compra</td><td>−12,50 €</td></tr></table></main>');
    assert.equal(data.status, 'captured');
    assert.equal(data.partial, true);
    assert.equal(data.rows.filter(row => row.includes('Compra')).length, 2, 'identical purchases must not disappear');
    assert.ok(data.rows.some(row => row.includes('Saldo disponible') && row.includes('1.234,56')));
    checks += 4;

    data = await read('<main><p>Saldo 5 EUR</p><form><p>PIN 1234</p><input value="SECRET 600 EUR"></form><p hidden>HIDDEN 50 EUR</p><p aria-hidden="true">ARIA 20 EUR</p><input value="INPUT 30 EUR"><textarea>TEXTAREA 40 EUR</textarea><div contenteditable="true">EDITABLE 60 EUR</div></main>');
    assert.equal(data.status, 'captured');
    for (const secret of ['SECRET', 'HIDDEN', 'ARIA', 'INPUT', 'TEXTAREA', 'EDITABLE', '1234']) assert.ok(!JSON.stringify(data).includes(secret));
    checks += 8;

    for (const field of ['type="password"', 'autocomplete="one-time-code"']) {
      data = await read(`<main><p>Saldo 5 EUR</p><input ${field}></main>`);
      assert.equal(data.status, 'authentication_required'); checks++;
    }
    data = await read('<main><p>Saldo 5 EUR</p><input type="password" hidden></main>');
    assert.equal(data.status, 'captured'); checks++;
    for (const html of ['<main>Bienvenido</main>', '<p>Saldo 5 EUR</p>', '<main><form><p>Ingresar 5 EUR</p></form></main>']) {
      assert.equal((await read(html)).status, 'unsupported_page'); checks++;
    }
    data = await read('<main><ul>' + '<li>Compra 2 EUR</li>'.repeat(205) + '</ul></main>');
    assert.equal(data.rows.length, 200);
    assert.equal(data.truncated, true); checks += 2;
    data = await read('<main><table><tr><td>' + 'x'.repeat(500) + ' 3 EUR</td></tr></table></main>');
    assert.equal(data.rows[0].length, 400);
    assert.equal(data.truncated, true); checks += 2;
    data = await read('<main><ul><li><div role="row">Compra 3 EUR</div></li></ul><nav>Oferta 40 EUR</nav><button onclick="window.clicked=true">Comprar 50 EUR</button></main>');
    assert.deepEqual(data.rows, ['Compra 3 EUR']);
    assert.equal(await page.evaluate(() => window.clicked), undefined); checks += 2;
    console.log(`PASS: ${checks} banking reader assertions (Chromium, synthetic pages)`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
