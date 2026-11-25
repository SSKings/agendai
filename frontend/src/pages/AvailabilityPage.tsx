import { useCallback, useState } from 'react'
import { catalog } from '../api/catalog'
import { schedule } from '../api/schedule'
import { bookings } from '../api/bookings'
import type { AvailabilityView } from '../api/types'
import { Alert, Button, Field, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { timeOf, todayIn } from '../lib/dates'
import { ProfessionalPicker } from './ProfessionalPicker'
import { useTenant } from './TenantLayout'

/** Horários livres (o que o cliente vai ver na página pública, na fase 2) e agendamento manual de quem ligou. */
export function AvailabilityPage() {
  const tenant = useTenant()
  const load = useCallback(() => Promise.all([catalog.services(tenant.id), catalog.professionals(tenant.id)]), [tenant.id])
  const { data, error } = useLoad(load, 'Não foi possível carregar o catálogo.')
  const [serviceId, setServiceId] = useState('')
  const [professionalId, setProfessionalId] = useState('')
  const [date, setDate] = useState(() => todayIn(tenant.timeZone))
  const [result, setResult] = useState<AvailabilityView | null>(null)
  const [chosen, setChosen] = useState<string | null>(null)
  const [booked, setBooked] = useState<string | null>(null)
  const [services, professionals] = data ?? [[], []]
  const offered = services.filter((s) => s.active)
  const service = serviceId || offered[0]?.id
  const team = professionals.filter((p) => p.active && p.serviceIds.includes(service))
  const professional = team.some((p) => p.id === professionalId) ? professionalId : team[0]?.id

  const search = async () => {
    setResult(null)
    setChosen(null)
    setResult(await schedule.availability(tenant.id, professional!, service, date))
  }
  const { busy, error: searchError, onSubmit } = useSubmit(async () => {
    setBooked(null)
    await search()
  }, 'Não foi possível consultar os horários.')
  const reset = () => {
    setResult(null)
    setBooked(null)
  }

  if (error) return <Alert>{error}</Alert>
  if (!data) return null
  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold text-slate-900">Horários livres</h2>
      {offered.length === 0 ? (
        <p className="text-slate-500">Cadastre um serviço ativo primeiro.</p>
      ) : (
        <form onSubmit={onSubmit} className="space-y-3 rounded-xl bg-white p-4 ring-1 ring-slate-200">
          <ServicePicker services={offered} value={service} onChange={(id) => { setServiceId(id); reset() }} />
          {team.length === 0 ? (
            <p className="text-sm text-slate-500">Ninguém ativo da equipe faz esse serviço.</p>
          ) : (
            <ProfessionalPicker professionals={team} value={professional!} onChange={(id) => { setProfessionalId(id); reset() }} />
          )}
          <Field label="Dia" type="date" required value={date} onChange={(e) => { setDate(e.target.value); reset() }} />
          {searchError && <Alert>{searchError}</Alert>}
          {team.length > 0 && <div className="w-40"><Submit busy={busy}>Ver horários</Submit></div>}
        </form>
      )}
      {booked && <Alert kind="success">{booked}</Alert>}
      {result && (result.slots.length === 0 ? (
        <p className="text-slate-500">Nenhum horário livre nesse dia.</p>
      ) : (
        <ul aria-label="Horários livres" className="flex flex-wrap gap-2">
          {result.slots.map((s) => (
            <li key={s}>
              <button type="button" aria-pressed={chosen === s} onClick={() => setChosen(s)}
                className={`rounded-lg px-3 py-1.5 text-sm font-medium ${chosen === s ? 'bg-indigo-600 text-white' : 'bg-indigo-50 text-indigo-700 hover:bg-indigo-100'}`}>
                {timeOf(s)}
              </button>
            </li>
          ))}
        </ul>
      ))}
      {chosen && (
        <BookingForm key={chosen} slot={chosen} onCancel={() => setChosen(null)} onBook={async (customer, note) => {
          await bookings.create(tenant.id, { professionalId: professional!, serviceId: service, start: chosen.slice(0, 16), customer, note })
          const who = team.find((p) => p.id === professional)!.name
          await search()
          setBooked(`Agendado: ${timeOf(chosen)} com ${who}.`)
        }} />
      )}
    </div>
  )
}

function ServicePicker({ services, value, onChange }: {
  services: { id: string; name: string; durationMinutes: number }[]
  value: string
  onChange: (id: string) => void
}) {
  return (
    <div>
      <label htmlFor="servico" className="text-sm font-medium text-slate-700">Serviço</label>
      <select id="servico" value={value} onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2">
        {services.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.durationMinutes} min)</option>)}
      </select>
    </div>
  )
}

type CustomerInput = { name: string; phone: string; email?: string }

function BookingForm({ slot, onBook, onCancel }: {
  slot: string
  onBook: (customer: CustomerInput, note?: string) => Promise<void>
  onCancel: () => void
}) {
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [note, setNote] = useState('')
  const { busy, error, onSubmit } = useSubmit(async () => {
    await onBook({ name: name.trim(), phone: phone.trim(), email: email.trim() || undefined }, note.trim() || undefined)
  }, 'Não foi possível agendar.')
  return (
    <form onSubmit={onSubmit} className="space-y-3 rounded-xl bg-white p-4 ring-1 ring-slate-200">
      <h3 className="font-semibold text-slate-900">Agendar às {timeOf(slot)}</h3>
      <Field label="Nome do cliente" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      <Field label="Telefone" type="tel" required maxLength={25} hint="Com DDD." value={phone} onChange={(e) => setPhone(e.target.value)} />
      <Field label="E-mail (opcional)" type="email" maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} />
      <Field label="Observação (opcional)" maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
      {error && <Alert>{error}</Alert>}
      <div className="flex gap-2">
        <div className="w-40"><Submit busy={busy}>Confirmar</Submit></div>
        <Button onClick={onCancel}>Cancelar</Button>
      </div>
    </form>
  )
}
