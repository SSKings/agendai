import { useCallback, useState } from 'react'
import { catalog } from '../api/catalog'
import { schedule } from '../api/schedule'
import type { WorkingInterval } from '../api/types'
import { Alert, Button, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { fromEndInput, toInput, WEEKDAYS } from '../lib/dates'
import { ProfessionalPicker } from './ProfessionalPicker'
import { useTenant } from './TenantLayout'

/** Expediente semanal de cada profissional: faixas por dia (duas faixas = pausa entre elas). */
export function HoursPage() {
  const tenant = useTenant()
  const load = useCallback(() => catalog.professionals(tenant.id), [tenant.id])
  const { data: professionals, error } = useLoad(load, 'Não foi possível carregar a equipe.')
  const [chosen, setChosen] = useState('')
  const current = chosen || professionals?.[0]?.id

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold text-slate-900">Expediente</h2>
      {error && <Alert>{error}</Alert>}
      {professionals?.length === 0 && <p className="text-slate-500">Cadastre a equipe primeiro.</p>}
      {professionals && current && (
        <>
          <ProfessionalPicker professionals={professionals} value={current} onChange={setChosen} />
          <Week key={current} tenantId={tenant.id} professionalId={current} owner={tenant.role === 'OWNER'} />
        </>
      )}
    </div>
  )
}

function Week({ tenantId, professionalId, owner }: { tenantId: string; professionalId: string; owner: boolean }) {
  const load = useCallback(() => schedule.workingHours(tenantId, professionalId), [tenantId, professionalId])
  const { data, error } = useLoad(load, 'Não foi possível carregar o expediente.')
  if (error) return <Alert>{error}</Alert>
  return data && <WeekForm tenantId={tenantId} professionalId={professionalId} owner={owner} initial={data.intervals} />
}

type Row = WorkingInterval & { key: number }
let nextKey = 0

function WeekForm({ tenantId, professionalId, owner, initial }: {
  tenantId: string
  professionalId: string
  owner: boolean
  initial: WorkingInterval[]
}) {
  const [rows, setRows] = useState<Row[]>(() => initial.map((i) => ({ ...i, key: nextKey++ })))
  const [saved, setSaved] = useState(false)
  const change = (key: number, patch: Partial<WorkingInterval>) => {
    setSaved(false)
    setRows(rows.map((r) => (r.key === key ? { ...r, ...patch } : r)))
  }
  const add = (weekday: number) => {
    setSaved(false)
    setRows([...rows, { weekday, start: '09:00', end: '18:00', key: nextKey++ }])
  }
  const remove = (key: number) => {
    setSaved(false)
    setRows(rows.filter((r) => r.key !== key))
  }
  const { busy, error, onSubmit } = useSubmit(async () => {
    await schedule.replaceWorkingHours(tenantId, professionalId, rows.map(({ weekday, start, end }) => ({ weekday, start, end })))
    setSaved(true)
  }, 'Não foi possível salvar o expediente.')

  return (
    <form onSubmit={onSubmit} className="space-y-3 rounded-xl bg-white p-4 ring-1 ring-slate-200">
      {WEEKDAYS.map((day, i) => {
        const weekday = i + 1
        const ofDay = rows.filter((r) => r.weekday === weekday)
        return (
          <fieldset key={day} className="flex flex-wrap items-center gap-2 border-b border-slate-100 pb-3">
            <legend className="w-24 text-sm font-medium text-slate-700">{day}</legend>
            {ofDay.length === 0 && <span className="text-sm text-slate-400">Folga</span>}
            {ofDay.map((r, n) => (
              <span key={r.key} className="flex items-center gap-1 text-sm">
                <input type="time" step={300} required disabled={!owner} aria-label={`${day}, faixa ${n + 1}: início`}
                  value={r.start} onChange={(e) => change(r.key, { start: e.target.value })}
                  className="rounded border border-slate-300 px-2 py-1" />
                até
                <input type="time" step={300} required disabled={!owner} aria-label={`${day}, faixa ${n + 1}: fim`}
                  value={toInput(r.end)} onChange={(e) => change(r.key, { end: fromEndInput(e.target.value) })}
                  className="rounded border border-slate-300 px-2 py-1" />
                {owner && <Button aria-label={`Remover ${day}, faixa ${n + 1}`} onClick={() => remove(r.key)}>×</Button>}
              </span>
            ))}
            {owner && <Button aria-label={`Adicionar faixa: ${day}`} onClick={() => add(weekday)}>+ faixa</Button>}
          </fieldset>
        )
      })}
      <p className="text-xs text-slate-500">Fim 00:00 = até a meia-noite. Horários de 5 em 5 minutos.</p>
      {error && <Alert>{error}</Alert>}
      {saved && <Alert kind="success">Expediente salvo.</Alert>}
      {owner && <div className="w-40"><Submit busy={busy}>Salvar</Submit></div>}
    </form>
  )
}
