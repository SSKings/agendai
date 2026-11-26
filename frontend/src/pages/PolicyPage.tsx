import { useCallback, useState } from 'react'
import { policies } from '../api/policy'
import type { Policy } from '../api/types'
import { Alert, Field, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { useTenant } from './TenantLayout'

/** Regras para o cliente na página pública e no link pessoal (o painel não segue: o dono agenda quando precisar). */
export function PolicyPage() {
  const tenant = useTenant()
  const load = useCallback(() => policies.get(tenant.id), [tenant.id])
  const { data, error } = useLoad(load, 'Não foi possível carregar a política.')
  if (error) return <Alert>{error}</Alert>
  return data && <PolicyForm tenantId={tenant.id} initial={data} owner={tenant.role === 'OWNER'} />
}

function PolicyForm({ tenantId, initial, owner }: { tenantId: string; initial: Policy; owner: boolean }) {
  const [p, setP] = useState(initial)
  const [saved, setSaved] = useState(false)
  const set = (k: keyof Policy) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setSaved(false)
    setP({ ...p, [k]: Number(e.target.value) })
  }
  const { busy, error, onSubmit } = useSubmit(async () => {
    setP(await policies.update(tenantId, p))
    setSaved(true)
  }, 'Não foi possível salvar a política.')
  return (
    <form onSubmit={onSubmit} className="max-w-md space-y-4 rounded-xl bg-white p-4 ring-1 ring-slate-200">
      <h2 className="text-lg font-semibold text-slate-900">Política para o cliente</h2>
      <Field label="Antecedência mínima para agendar (minutos)" type="number" min={0} max={10080} step={5} required disabled={!owner}
        value={p.minNoticeMinutes} onChange={set('minNoticeMinutes')} />
      <Field label="Cliente cancela ou remarca até (horas antes)" type="number" min={0} max={720} required disabled={!owner}
        value={p.cancelUntilHours} onChange={set('cancelUntilHours')} />
      <Field label="Agenda aberta para quantos dias à frente" type="number" min={1} max={180} required disabled={!owner}
        value={p.bookingWindowDays} onChange={set('bookingWindowDays')} />
      {error && <Alert>{error}</Alert>}
      {saved && <Alert kind="success">Política salva.</Alert>}
      {owner && <div className="w-40"><Submit busy={busy}>Salvar</Submit></div>}
    </form>
  )
}
