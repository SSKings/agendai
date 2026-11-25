import { useCallback, useId, useState } from 'react'
import { bookings } from '../api/bookings'
import { catalog } from '../api/catalog'
import type { BookingStatus, BookingView } from '../api/types'
import { Alert, Button, Field } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { messageOf } from '../lib/errors'
import { addDays, dayOf, formatDay, timeOf, todayIn } from '../lib/dates'
import { ProfessionalPicker } from './ProfessionalPicker'
import { useTenant } from './TenantLayout'

const STATUS: Record<BookingStatus, string> = {
  CONFIRMED: 'Confirmado',
  COMPLETED: 'Atendido',
  NO_SHOW: 'Faltou',
  CANCELLED: 'Cancelado',
}

/** Agenda do dia ou da semana (7 dias a partir do dia escolhido), de todos ou de um profissional. */
export function AgendaPage() {
  const tenant = useTenant()
  const [date, setDate] = useState(() => todayIn(tenant.timeZone))
  const [week, setWeek] = useState(false)
  const [professionalId, setProfessionalId] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const viewId = useId()
  const load = useCallback(() => Promise.all([
    bookings.agenda(tenant.id, date, week ? addDays(date, 6) : date, professionalId || undefined),
    catalog.services(tenant.id),
    catalog.professionals(tenant.id),
  ]), [tenant.id, date, week, professionalId])
  const { data, error, reload } = useLoad(load, 'Não foi possível carregar a agenda.')
  const [agenda, services, professionals] = data ?? [null, [], []]
  const serviceName = new Map(services.map((s) => [s.id, s.name]))
  const professionalName = new Map(professionals.map((p) => [p.id, p.name]))
  const days = [...new Set(agenda?.bookings.map((b) => dayOf(b.start)))]

  const change = async (b: BookingView, status: BookingStatus) => {
    try {
      setActionError(null)
      await bookings.changeStatus(tenant.id, b.id, status)
      await reload()
    } catch (err) {
      setActionError(messageOf(err, 'Não foi possível alterar o agendamento.'))
    }
  }

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold text-slate-900">Agenda</h2>
      <div className="grid gap-3 rounded-xl bg-white p-4 ring-1 ring-slate-200 sm:grid-cols-3">
        <Field label={week ? 'A partir de' : 'Dia'} type="date" required value={date} onChange={(e) => e.target.value && setDate(e.target.value)} />
        <div>
          <label htmlFor={viewId} className="text-sm font-medium text-slate-700">Visão</label>
          <select id={viewId} value={week ? 'week' : 'day'} onChange={(e) => setWeek(e.target.value === 'week')}
            className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2">
            <option value="day">Dia</option>
            <option value="week">Semana</option>
          </select>
        </div>
        <ProfessionalPicker professionals={professionals} value={professionalId} onChange={setProfessionalId} extra="Todos" />
      </div>
      {error && <Alert>{error}</Alert>}
      {actionError && <Alert>{actionError}</Alert>}
      {agenda?.truncated && <Alert>Há mais agendamentos do que cabem na tela: filtre por profissional ou veja um dia.</Alert>}
      {agenda?.bookings.length === 0 && <p className="text-slate-500">Nenhum agendamento no período.</p>}
      {days.map((day) => (
        <section key={day} aria-label={formatDay(day)} className="space-y-2">
          {week && <h3 className="font-medium capitalize text-slate-700">{formatDay(day)}</h3>}
          <ul className="divide-y divide-slate-200 rounded-xl bg-white ring-1 ring-slate-200">
            {agenda!.bookings.filter((b) => dayOf(b.start) === day).map((b) => (
              <li key={b.id} className={`flex flex-wrap items-center gap-3 p-4 ${b.status === 'CANCELLED' ? 'opacity-60' : ''}`}>
                <div className="flex-1">
                  <p className="font-medium text-slate-900">
                    {timeOf(b.start)}–{timeOf(b.end)} · {b.customer.name}
                    <span className="ml-2 rounded bg-slate-100 px-1.5 text-xs text-slate-600">{STATUS[b.status]}</span>
                    {b.outsideHours && b.status === 'CONFIRMED' && (
                      <span className="ml-2 rounded bg-amber-100 px-1.5 text-xs text-amber-800">fora do expediente</span>
                    )}
                  </p>
                  <p className="text-sm text-slate-500">
                    {serviceName.get(b.serviceId) ?? 'Serviço'} com {professionalName.get(b.professionalId) ?? 'profissional'} · {b.customer.phone}
                    {b.note ? ` · ${b.note}` : ''}
                  </p>
                </div>
                {b.status === 'CONFIRMED' && (
                  <div className="flex gap-2">
                    <Button aria-label={`Atendido: ${b.customer.name} às ${timeOf(b.start)}`} onClick={() => void change(b, 'COMPLETED')}>Atendido</Button>
                    <Button aria-label={`Faltou: ${b.customer.name} às ${timeOf(b.start)}`} onClick={() => void change(b, 'NO_SHOW')}>Faltou</Button>
                    <Button aria-label={`Cancelar: ${b.customer.name} às ${timeOf(b.start)}`} onClick={() => void change(b, 'CANCELLED')}>Cancelar</Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
