import { useCallback, useState } from 'react'
import { catalog } from '../api/catalog'
import type { ServiceView } from '../api/types'
import { Alert, Button, Check, Field, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { centsToInput, formatCents, parseCents } from '../lib/money'
import { useTenant } from './TenantLayout'

export function ServicesPage() {
  const tenant = useTenant()
  const owner = tenant.role === 'OWNER'
  const load = useCallback(() => catalog.services(tenant.id), [tenant.id])
  const { data, error, reload } = useLoad(load, 'Não foi possível carregar os serviços.')
  const [editing, setEditing] = useState<ServiceView | 'new' | null>(null)

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-slate-900">Serviços</h2>
        {owner && editing === null && <Button onClick={() => setEditing('new')}>Novo serviço</Button>}
      </div>
      {editing !== null && (
        <ServiceForm
          tenantId={tenant.id}
          service={editing === 'new' ? null : editing}
          onDone={async () => {
            setEditing(null)
            await reload()
          }}
          onCancel={() => setEditing(null)}
        />
      )}
      {error && <Alert>{error}</Alert>}
      {data?.length === 0 && <p className="text-slate-500">Nenhum serviço cadastrado ainda.</p>}
      <ul className="divide-y divide-slate-200 rounded-xl bg-white ring-1 ring-slate-200">
        {data?.map((s) => (
          <li key={s.id} className="flex items-center gap-4 p-4">
            <div className="flex-1">
              <p className="font-medium text-slate-900">
                {s.name} {!s.active && <span className="ml-2 rounded bg-slate-100 px-1.5 text-xs text-slate-500">inativo</span>}
              </p>
              <p className="text-sm text-slate-500">
                {s.durationMinutes} min{s.bufferMinutes > 0 ? ` + ${s.bufferMinutes} min de intervalo` : ''} · {formatCents(s.priceCents)}
              </p>
            </div>
            {owner && <Button aria-label={`Editar ${s.name}`} onClick={() => setEditing(s)}>Editar</Button>}
          </li>
        ))}
      </ul>
    </div>
  )
}

function ServiceForm({ tenantId, service, onDone, onCancel }: {
  tenantId: string
  service: ServiceView | null
  onDone: () => Promise<void>
  onCancel: () => void
}) {
  const [name, setName] = useState(service?.name ?? '')
  const [duration, setDuration] = useState(String(service?.durationMinutes ?? 30))
  const [buffer, setBuffer] = useState(String(service?.bufferMinutes ?? 0))
  const [price, setPrice] = useState(service ? centsToInput(service.priceCents) : '')
  const [active, setActive] = useState(service?.active ?? true)
  const { busy, error, setError, onSubmit } = useSubmit(async () => {
    const priceCents = parseCents(price)
    if (priceCents === null) {
      setError('Preço inválido. Use, por exemplo, 45,00.')
      return
    }
    const input = { name: name.trim(), durationMinutes: Number(duration), bufferMinutes: Number(buffer), priceCents, active }
    await (service ? catalog.updateService(tenantId, service.id, input) : catalog.createService(tenantId, input))
    await onDone()
  }, 'Não foi possível salvar o serviço.')

  return (
    <form onSubmit={onSubmit} className="space-y-4 rounded-xl bg-white p-4 ring-1 ring-slate-200">
      <h3 className="font-semibold text-slate-900">{service ? `Editar ${service.name}` : 'Novo serviço'}</h3>
      <Field label="Nome" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Duração (min)" type="number" required min={5} max={480} step={5} value={duration} onChange={(e) => setDuration(e.target.value)} />
        <Field label="Intervalo depois (min)" type="number" required min={0} max={120} step={5} value={buffer} onChange={(e) => setBuffer(e.target.value)}
          hint="Preparo ou limpeza antes do próximo cliente." />
        <Field label="Preço (R$)" inputMode="decimal" required value={price} onChange={(e) => setPrice(e.target.value)} />
      </div>
      {service && <Check label="Ativo (aparece para agendamento)" checked={active} onChange={(e) => setActive(e.target.checked)} />}
      {error && <Alert>{error}</Alert>}
      <div className="flex gap-2">
        <div className="w-40"><Submit busy={busy}>Salvar</Submit></div>
        <Button onClick={onCancel}>Cancelar</Button>
      </div>
    </form>
  )
}
