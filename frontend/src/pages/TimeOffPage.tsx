import { useCallback, useState } from 'react'
import { catalog } from '../api/catalog'
import { schedule } from '../api/schedule'
import type { ProfessionalView } from '../api/types'
import { Alert, Button, Field, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { messageOf } from '../lib/errors'
import { addDays, formatLocal, todayIn } from '../lib/dates'
import { ProfessionalPicker } from './ProfessionalPicker'
import { useTenant } from './TenantLayout'

/** Bloqueios dos próximos 92 dias (o maior período que a API lista de uma vez). */
const DAYS = 91

export function TimeOffPage() {
  const tenant = useTenant()
  const owner = tenant.role === 'OWNER'
  const load = useCallback(() => {
    const from = todayIn(tenant.timeZone)
    return Promise.all([schedule.timeOff(tenant.id, from, addDays(from, DAYS)), catalog.professionals(tenant.id)])
  }, [tenant.id, tenant.timeZone])
  const { data, error, reload } = useLoad(load, 'Não foi possível carregar os bloqueios.')
  const [removeError, setRemoveError] = useState<string | null>(null)
  const [list, professionals] = data ?? [null, []]
  const nameOf = new Map(professionals.map((p) => [p.id, p.name]))

  const remove = async (id: string) => {
    try {
      setRemoveError(null)
      await schedule.deleteTimeOff(tenant.id, id)
      await reload()
    } catch (err) {
      setRemoveError(messageOf(err, 'Não foi possível remover o bloqueio.'))
    }
  }

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold text-slate-900">Bloqueios e feriados</h2>
      {owner && <TimeOffForm tenantId={tenant.id} professionals={professionals} onDone={reload} />}
      {error && <Alert>{error}</Alert>}
      {removeError && <Alert>{removeError}</Alert>}
      {list?.length === 0 && <p className="text-slate-500">Nenhum bloqueio nos próximos 3 meses.</p>}
      <ul className="divide-y divide-slate-200 rounded-xl bg-white ring-1 ring-slate-200">
        {list?.map((t) => (
          <li key={t.id} className="flex items-center gap-4 p-4">
            <div className="flex-1">
              <p className="font-medium text-slate-900">{formatLocal(t.start)} → {formatLocal(t.end)}</p>
              <p className="text-sm text-slate-500">
                {t.professionalId ? nameOf.get(t.professionalId) ?? 'Profissional removido' : 'Todo o estabelecimento'}
                {t.reason ? ` · ${t.reason}` : ''}
              </p>
            </div>
            {owner && <Button aria-label={`Remover bloqueio de ${formatLocal(t.start)}`} onClick={() => void remove(t.id)}>Remover</Button>}
          </li>
        ))}
      </ul>
    </div>
  )
}

function TimeOffForm({ tenantId, professionals, onDone }: {
  tenantId: string
  professionals: ProfessionalView[]
  onDone: () => Promise<void>
}) {
  const [professionalId, setProfessionalId] = useState('')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [reason, setReason] = useState('')
  const { busy, error, onSubmit } = useSubmit(async () => {
    await schedule.createTimeOff(tenantId, { professionalId: professionalId || undefined, start, end, reason: reason.trim() || undefined })
    setStart('')
    setEnd('')
    setReason('')
    await onDone()
  }, 'Não foi possível salvar o bloqueio.')

  return (
    <form onSubmit={onSubmit} className="space-y-3 rounded-xl bg-white p-4 ring-1 ring-slate-200">
      <h3 className="font-semibold text-slate-900">Novo bloqueio</h3>
      <ProfessionalPicker professionals={professionals} value={professionalId} onChange={setProfessionalId}
        label="Quem" extra="Todo o estabelecimento (feriado)" />
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Início" type="datetime-local" step={300} required value={start} onChange={(e) => setStart(e.target.value)} />
        <Field label="Fim" type="datetime-local" step={300} required value={end} onChange={(e) => setEnd(e.target.value)} />
      </div>
      <Field label="Motivo (opcional)" maxLength={100} value={reason} onChange={(e) => setReason(e.target.value)} />
      {error && <Alert>{error}</Alert>}
      <div className="w-48"><Submit busy={busy}>Adicionar bloqueio</Submit></div>
    </form>
  )
}
