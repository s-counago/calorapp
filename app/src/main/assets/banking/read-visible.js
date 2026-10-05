/* Passive, bounded extraction. No clicks, fetch, form values, cookies or storage access.
 * Deliberately a partial page snapshot, NOT an account/transaction synchronizer.
 */
(() => {
  'use strict';
  const visible = el => el.getClientRects().length > 0 &&
    getComputedStyle(el).visibility !== 'hidden' && getComputedStyle(el).display !== 'none';
  if ([...document.querySelectorAll('input[type="password"], input[autocomplete="one-time-code"]')].some(visible)) {
    return {status: 'authentication_required'};
  }
  const root = document.querySelector('main, [role="main"]');
  if (!root) return {status: 'unsupported_page'};
  const excluded = 'form, input, textarea, select, button, nav, header, footer, script, style, [contenteditable="true"], [aria-hidden="true"]';
  const rows = [];
  let truncated = false;
  const text = el => {
    if (!visible(el) || el.closest(excluded)) return '';
    // Read text nodes only: input values and hidden content never enter the result.
    const parts = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (!node.parentElement.closest(excluded) && visible(node.parentElement)) parts.push(node.textContent);
    }
    return parts.join(' ').replace(/\s+/g, ' ').trim();
  };
  const add = value => {
    if (!value) return;
    if (value.length > 400) { value = value.slice(0, 399) + '…'; truncated = true; }
    // Do not deduplicate: two identical transaction rows may be distinct purchases.
    if (rows.length < 200) rows.push(value); else truncated = true;
  };
  const money = /(?:[−+\-]?\d[\d.,\s]*\s*(?:€|EUR|USD|GBP)|[€$£]\s*[−+\-]?\d)/i;
  const structured = 'tr, [role="row"], li, [role="listitem"], dl';
  for (const el of root.querySelectorAll(structured)) {
    // Nested structures are read once at their smallest complete container.
    if (el.querySelector(structured)) continue;
    const value = text(el);
    if (money.test(value)) add(value);
  }
  // Summary cards often use plain blocks rather than tables.
  for (const el of root.querySelectorAll('p, div, section, span')) {
    if (el.closest(structured) || el.querySelector(structured)) continue;
    const value = text(el);
    if (!value || value.length > 400 || !money.test(value)) continue;
    if ([...el.children].some(child => money.test(text(child)))) continue;
    // Include a short label from the immediate parent, but not a whole dashboard.
    const parentText = el.parentElement === root ? '' : text(el.parentElement);
    add(parentText && parentText.length <= 160 ? parentText : value);
  }
  if (!rows.length) return {status: 'unsupported_page'};
  return {status: 'captured', partial: true, truncated, rows};
})()
