import { useCallback, useState } from 'react'
import { catalog } from '../api/catalog'
import { schedule } from '../api/schedule'
import type { AvailabilityView } from '../api/types'
import { Alert, Field, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { timeOf, todayIn } from '../lib/dates'
import { ProfessionalPicker } from './ProfessionalPicker'
import { useTenant } from './TenantLayout'

/** Consulta de horários livres (o que o cliente vai ver na página pública, na fase 2). */
export function AvailabilityPage() {
  const tenant = useTenant()
  const load = useCallback(() => Promise.all([catalog.services(tenant.id), catalog.professionals(tenant.id)]), [tenant.id])
  const { data, error } = useLoad(load, 'Não foi possível carregar o catálogo.')
  const [serviceId, setServiceId] = useState('')
  const [professionalId, setProfessionalId] = useState('')
  const [date, setDate] = useState(() => todayIn(tenant.timeZone))
  const [result, setResult] = useState<AvailabilityView | null>(null)
  const [services, professionals] = data ?? [[], []]
  const offered = services.filter((s) => s.active)
  const service = serviceId || offered[0]?.id
  const team = professionals.filter((p) => p.active && p.serviceIds.includes(service))
  const professional = team.some((p) => p.id === professionalId) ? professionalId : team[0]?.id

  const { busy, error: searchError, onSubmit } = useSubmit(async () => {
    setResult(null)
    setResult(await schedule.availability(tenant.id, professional!, service, date))
  }, 'Não foi possível consultar os horários.')

  if (error) return <Alert>{error}</Alert>
  if (!data) return null
  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold text-slate-900">Horários livres</h2>
      {offered.length === 0 ? (
        <p className="text-slate-500">Cadastre um serviço ativo primeiro.</p>
      ) : (
        <form onSubmit={onSubmit} className="space-y-3 rounded-xl bg-white p-4 ring-1 ring-slate-200">
          <ServicePicker services={offered} value={service} onChange={(id) => { setServiceId(id); setResult(null) }} />
          {team.length === 0 ? (
            <p className="text-sm text-slate-500">Ninguém ativo da equipe faz esse serviço.</p>
          ) : (
            <ProfessionalPicker professionals={team} value={professional!} onChange={(id) => { setProfessionalId(id); setResult(null) }} />
          )}
          <Field label="Dia" type="date" required value={date} onChange={(e) => { setDate(e.target.value); setResult(null) }} />
          {searchError && <Alert>{searchError}</Alert>}
          {team.length > 0 && <div className="w-40"><Submit busy={busy}>Ver horários</Submit></div>}
        </form>
      )}
      {result && (result.slots.length === 0 ? (
        <p className="text-slate-500">Nenhum horário livre nesse dia.</p>
      ) : (
        <ul aria-label="Horários livres" className="flex flex-wrap gap-2">
          {result.slots.map((s) => (
            <li key={s} className="rounded-lg bg-indigo-50 px-3 py-1.5 text-sm font-medium text-indigo-700">{timeOf(s)}</li>
          ))}
        </ul>
      ))}
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
