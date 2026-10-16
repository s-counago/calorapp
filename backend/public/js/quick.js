// Apuntar: one line that understands the basics, the same grammar as the phone (QuickCapture.java).
//   «llamar al banco mañana tarde» → a task planned tomorrow afternoon
//   «!comprar regalo» → Prioridad · «esperar factura de Juan» → Esperando
//   «+350» → 350 kcal · «p30» → 30 g of protein · «c1» → one cigarette
// Day and slot words are only read at the end of the line, so «preparar la cena de mañana para Ana»
// stays as it is. Pure functions, so the backend tests can check them against the phone's cases.

const WEEKDAYS = ['domingo', 'lunes', 'martes', 'miercoles', 'jueves', 'viernes', 'sabado'];
const WAITING = ['esperar', 'esperando', 'espero'];
const SLOTS = { manana: 'morning', tarde: 'afternoon', noche: 'evening' };
const URL = /https?:\/\/\S+/;

const norm = (word) => word.toLocaleLowerCase('es-ES').normalize('NFD').replace(/\p{M}/gu, '').replace(/[.,;:!?]+$/, '');

/** The first link in a line, or null. */
export const firstUrl = (text) => URL.exec(text ?? '')?.[0] ?? null;

/**
 * Reads a line. `weekday` is today's day of the week (0 = domingo). Returns a counter
 * `{ kind: 'calories' | 'protein' | 'cigarettes', amount }` or a task
 * `{ kind: 'task', title, lane, day, slot }`, where `day` is days from today or null.
 */
export function parseLine(line, { defaultLane = 'inbox', weekday }) {
  let text = String(line ?? '').trim().replace(/\s+/g, ' ');
  const lower = text.toLocaleLowerCase('es-ES');
  let match;
  if ((match = /^\+\s*(\d{1,5})(\s*kcal)?$/.exec(lower))) return { kind: 'calories', amount: Number(match[1]) };
  if ((match = /^p\s*(\d{1,4})(\s*g)?$/.exec(lower))) return { kind: 'protein', amount: Number(match[1]) };
  if ((match = /^c\s*(\d{1,2})$/.exec(lower))) return { kind: 'cigarettes', amount: Number(match[1]) };

  let lane = defaultLane;
  if (text.startsWith('!')) {
    lane = 'priority';
    text = text.slice(1).trim();
  }
  const words = text.split(' ');
  if (words.length && WAITING.includes(norm(words[0]))) lane = 'waiting';

  let end = words.length;
  let day = null;
  let slot = null;
  let spelledSlot = false;
  while (end > 1) {
    const word = norm(words[end - 1]);
    const before = end >= 2 ? norm(words[end - 2]) : '';
    const twoBefore = end >= 3 ? norm(words[end - 3]) : '';
    if (slot === null && day === null && SLOTS[word] && before === 'la' && twoBefore === 'por' && end > 3) {
      slot = SLOTS[word]; spelledSlot = true; end -= 3; continue;
    }
    if (slot === null && day === null && (word === 'tarde' || word === 'noche')) {
      slot = SLOTS[word]; end -= 1; continue;
    }
    if (day === null && word === 'manana') {
      if (before === 'pasado' && end > 2) { day = 2; end -= 2; } else { day = 1; end -= 1; }
      continue;
    }
    if (day === null && word === 'hoy') { day = 0; end -= 1; continue; }
    const index = WEEKDAYS.indexOf(word);
    if (day === null && index >= 0) {
      day = (index - weekday + 7) % 7;
      end -= 1;
      if (end > 1 && norm(words[end - 1]) === 'el') end -= 1;
      continue;
    }
    break;
  }
  // A bare «tarde» or «noche» without a day is part of the title («llegar tarde»).
  if (day === null && slot !== null && !spelledSlot) { slot = null; end = words.length; }
  else if (day === null && spelledSlot) day = 0;
  let title = words.slice(0, end).join(' ').trim();
  if (!title) { title = text; day = null; slot = null; }
  return { kind: 'task', title, lane, day, slot };
}

/** Day of the week (0 = domingo) of a YYYY-MM-DD day. */
export const weekdayOf = (day) => new Date(`${day}T12:00:00Z`).getUTCDay();
