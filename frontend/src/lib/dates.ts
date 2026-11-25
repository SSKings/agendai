// Datas da agenda: sempre no fuso do ESTABELECIMENTO (não no do navegador), como a API.

export const WEEKDAYS = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo'] // ISO: 1 = segunda

/** "Hoje" (AAAA-MM-DD) no fuso informado. */
export function todayIn(timeZone: string, now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}

/** AAAA-MM-DD + n dias (conta no calendário, sem fuso). */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

/** "2026-12-25T09:30:00" → "25/12/2026 09:30" (hora local do estabelecimento, como veio da API). */
export function formatLocal(dateTime: string): string {
  const [date, time] = dateTime.split('T')
  const [y, m, d] = date.split('-')
  return `${d}/${m}/${y} ${time.slice(0, 5)}`
}

/** Início de horário livre ("2026-11-30T09:15:00-03:00") → "09:15", a hora no fuso do estabelecimento. */
export function timeOf(slot: string): string {
  return slot.slice(11, 16)
}

/** O campo de hora do navegador não aceita 24:00: na tela, o fim do dia é 00:00. */
export const toInput = (hhmm: string) => (hhmm === '24:00' ? '00:00' : hhmm)
export const fromEndInput = (hhmm: string) => (hhmm === '00:00' ? '24:00' : hhmm)

const WEEKDAY_SHORT = ['domingo', 'segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado']

/** "2026-11-30" → "segunda, 30/11" (sem fuso: é uma data do calendário). */
export function formatDay(date: string): string {
  const [, m, d] = date.split('-')
  return `${WEEKDAY_SHORT[new Date(`${date}T00:00:00Z`).getUTCDay()]}, ${d}/${m}`
}

/** Data local de um instante com deslocamento ("2026-11-30T23:00:00-03:00" → "2026-11-30"). */
export const dayOf = (offsetDateTime: string) => offsetDateTime.slice(0, 10)
