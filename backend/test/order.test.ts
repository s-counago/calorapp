// The board's order on the web must match TaskStore.listOrder on the phone.
import { describe, expect, it } from 'vitest';
// @ts-expect-error Plain JavaScript served to the browser, without types.
import { laneOrder } from '../public/js/order.js';

const tasks = [
  { title: 'vieja sin fecha', created_at: 1 },
  { title: 'el viernes', created_at: 2, plan_date: '2026-10-23' },
  { title: 'vence el lunes', created_at: 3, plan_date: null, due_date: '2026-10-19' },
  { title: 'nueva sin fecha', created_at: 4 },
  { title: 'mañana', created_at: 5, plan_date: '2026-10-17' },
];
const titles = (datedLast: boolean) => tasks.slice().sort(laneOrder(datedLast)).map((task) => task.title);

describe('orden del tablero', () => {
  it('keeps newest first without dated_last', () => {
    expect(titles(false)).toEqual(['mañana', 'nueva sin fecha', 'vence el lunes', 'el viernes', 'vieja sin fecha']);
  });

  it('puts dated tasks at the bottom, soonest first, with dated_last', () => {
    expect(titles(true)).toEqual(['nueva sin fecha', 'vieja sin fecha', 'mañana', 'vence el lunes', 'el viernes']);
  });
});
