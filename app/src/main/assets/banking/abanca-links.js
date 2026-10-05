/* Used only during an explicit sync. Read-only destinations are kept in memory, never stored. */
(() => {
  if (location.origin !== 'https://bancaelectronica.abanca.com' ||
      location.pathname.toLowerCase() !== '/wele200/general/posicion/wele200m_posicion.aspx')
    return {status: 'unsupported_page'};
  const root = document.querySelector('#content');
  if (!root) return {status: 'unsupported_page'};
  const targets = [];
  for (const [selector, type] of [
    ['table#positions a[id$="_ctaLink"]', 'account'],
    ['table#cards a[id$="_lnkTarjeta"]', 'card'],
    ['table#loans a[id$="_ptmoLink"]', 'loan']
  ]) {
    for (const link of root.querySelectorAll(selector)) {
      if (targets.length >= 200) return {status: 'too_many_products'};
      if (!link.getClientRects().length || getComputedStyle(link).visibility === 'hidden' ||
          link.closest('form, [hidden], [aria-hidden="true"]')) continue;
      const label = link.innerText.replace(/\s+/g, ' ').trim().slice(0, 120);
      const kind = (link.closest('tr')?.querySelector('td.type')?.innerText || '').trim().slice(0, 80);
      if (label) targets.push({type, label, kind, url: link.href});
    }
  }
  return {status: 'ready', targets};
})()
