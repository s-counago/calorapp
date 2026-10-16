// The web's Apuntar box must read a line exactly as the phone does (QuickCaptureTest.java).
import { describe, expect, it } from 'vitest';
// @ts-expect-error Plain JavaScript served to the browser, without types.
import { firstUrl, parseLine, weekdayOf } from '../public/js/quick.js';

/** Thursday: 4 (0 = domingo). */
const parse = (line: string, defaultLane = 'inbox') => parseLine(line, { defaultLane, weekday: 4 });

describe('Apuntar (web)', () => {
  it('sends a plain line to the inbox', () => {
    expect(parse('poner lavadora')).toEqual({ kind: 'task', title: 'poner lavadora', lane: 'inbox', day: null, slot: null });
  });

  it('reads day and slot at the end', () => {
    expect(parse('llamar al banco mañana tarde')).toMatchObject({ title: 'llamar al banco', day: 1, slot: 'afternoon' });
    expect(parse('revisar vídeo hoy por la noche')).toMatchObject({ title: 'revisar vídeo', day: 0, slot: 'evening' });
    expect(parse('correr mañana por la mañana')).toMatchObject({ title: 'correr', day: 1, slot: 'morning' });
    expect(parse('dentista pasado mañana')).toMatchObject({ title: 'dentista', day: 2, slot: null });
  });

  it('counts weekdays from today', () => {
    expect(parse('cena con Ana el sábado noche')).toMatchObject({ title: 'cena con Ana', day: 2, slot: 'evening' });
    expect(parse('pagar alquiler martes').day).toBe(5);
    expect(parse('regar jueves').day).toBe(0);
    expect(parse('tirar basura el miercoles').day).toBe(6);
  });

  it('keeps day words inside the title', () => {
    expect(parse('preparar la cena de mañana para Ana').title).toBe('preparar la cena de mañana para Ana');
    expect(parse('no llegar tarde')).toMatchObject({ title: 'no llegar tarde', day: null, slot: null });
    expect(parse('mañana').title).toBe('mañana');
  });

  it('reads a spelled slot alone as today', () => {
    expect(parse('estirar por la tarde')).toMatchObject({ title: 'estirar', day: 0, slot: 'afternoon' });
  });

  it('picks lanes', () => {
    expect(parse('!comprar regalo madre')).toMatchObject({ lane: 'priority', title: 'comprar regalo madre' });
    expect(parse('esperar factura de Juan').lane).toBe('waiting');
    expect(parse('aprender a tocar el piano', 'stack').lane).toBe('stack');
  });

  it('reads counters', () => {
    expect(parse('+350')).toEqual({ kind: 'calories', amount: 350 });
    expect(parse('p30').kind).toBe('protein');
    expect(parse('P 30 g').amount).toBe(30);
    expect(parse('c1').kind).toBe('cigarettes');
    expect(parse('comprar 2 litros').kind).toBe('task');
  });

  it('finds links and weekdays', () => {
    expect(firstUrl('mirar https://example.com/a luego')).toBe('https://example.com/a');
    expect(firstUrl('nada')).toBeNull();
    expect(weekdayOf('2026-10-15')).toBe(4);
  });
});
