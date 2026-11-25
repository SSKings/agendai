import { useCallback, useState } from 'react'
import { catalog } from '../api/catalog'
import type { ProfessionalView, ServiceView } from '../api/types'
import { Alert, Button, Check, Field, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { useTenant } from './TenantLayout'

export function TeamPage() {
  const tenant = useTenant()
  const owner = tenant.role === 'OWNER'
  const load = useCallback(
    () => Promise.all([catalog.professionals(tenant.id), catalog.services(tenant.id)]),
    [tenant.id],
  )
  const { data, error, reload } = useLoad(load, 'Não foi possível carregar a equipe.')
  const [editing, setEditing] = useState<ProfessionalView | 'new' | null>(null)
  const [professionals, services] = data ?? [null, []]
  const serviceName = new Map(services.map((s) => [s.id, s.name]))

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-slate-900">Equipe</h2>
        {owner && editing === null && <Button onClick={() => setEditing('new')}>Novo profissional</Button>}
      </div>
      {editing !== null && (
        <ProfessionalForm
          tenantId={tenant.id}
          professional={editing === 'new' ? null : editing}
          services={services}
          onDone={async () => {
            setEditing(null)
            await reload()
          }}
          onCancel={() => setEditing(null)}
        />
      )}
      {error && <Alert>{error}</Alert>}
      {professionals?.length === 0 && <p className="text-slate-500">Nenhum profissional cadastrado ainda.</p>}
      <ul className="divide-y divide-slate-200 rounded-xl bg-white ring-1 ring-slate-200">
        {professionals?.map((p) => (
          <li key={p.id} className="flex items-center gap-4 p-4">
            <div className="flex-1">
              <p className="font-medium text-slate-900">
                {p.name} {!p.active && <span className="ml-2 rounded bg-slate-100 px-1.5 text-xs text-slate-500">inativo</span>}
              </p>
              <p className="text-sm text-slate-500">
                {servicesOf(p, serviceName)}
              </p>
            </div>
            {owner && <Button aria-label={`Editar ${p.name}`} onClick={() => setEditing(p)}>Editar</Button>}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Nomes dos serviços na ordem do catálogo; id que sumiu (serviço apagado em outra aba) não vira texto vazio. */
function servicesOf(p: ProfessionalView, serviceName: Map<string, string>): string {
  const names = [...serviceName].filter(([id]) => p.serviceIds.includes(id)).map(([, name]) => name)
  return names.length === 0 ? 'Nenhum serviço' : names.join(', ')
}

function ProfessionalForm({ tenantId, professional, services, onDone, onCancel }: {
  tenantId: string
  professional: ProfessionalView | null
  services: ServiceView[]
  onDone: () => Promise<void>
  onCancel: () => void
}) {
  const [name, setName] = useState(professional?.name ?? '')
  const [active, setActive] = useState(professional?.active ?? true)
  const [chosen, setChosen] = useState(new Set(professional?.serviceIds ?? []))
  const toggle = (id: string) => {
    const next = new Set(chosen)
    if (!next.delete(id)) next.add(id)
    setChosen(next)
  }
  const { busy, error, onSubmit } = useSubmit(async () => {
    const input = { name: name.trim(), active, serviceIds: [...chosen] }
    await (professional ? catalog.updateProfessional(tenantId, professional.id, input) : catalog.createProfessional(tenantId, input))
    await onDone()
  }, 'Não foi possível salvar o profissional.')

  return (
    <form onSubmit={onSubmit} className="space-y-4 rounded-xl bg-white p-4 ring-1 ring-slate-200">
      <h3 className="font-semibold text-slate-900">{professional ? `Editar ${professional.name}` : 'Novo profissional'}</h3>
      <Field label="Nome" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium text-slate-700">Serviços que faz</legend>
        {services.length === 0 && <p className="text-sm text-slate-500">Cadastre os serviços primeiro.</p>}
        {services.map((s) => (
          <Check key={s.id} label={s.name} checked={chosen.has(s.id)} onChange={() => toggle(s.id)} />
        ))}
      </fieldset>
      {professional && <Check label="Ativo (recebe agendamentos)" checked={active} onChange={(e) => setActive(e.target.checked)} />}
      {error && <Alert>{error}</Alert>}
      <div className="flex gap-2">
        <div className="w-40"><Submit busy={busy}>Salvar</Submit></div>
        <Button onClick={onCancel}>Cancelar</Button>
      </div>
    </form>
  )
}
