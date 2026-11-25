import { useCallback, useState } from 'react'
import { catalog } from '../api/catalog'
import { invites } from '../api/invites'
import type { InviteView, ProfessionalView, ServiceView } from '../api/types'
import { Alert, Button, Check, Field, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { messageOf } from '../lib/errors'
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
                {p.hasAccount && <span className="ml-2 rounded bg-emerald-50 px-1.5 text-xs text-emerald-700">{p.self ? 'você' : 'com conta'}</span>}
              </p>
              <p className="text-sm text-slate-500">
                {servicesOf(p, serviceName)}
              </p>
              {owner && !p.hasAccount && <InvitePanel tenantId={tenant.id} professional={p} />}
              {owner && p.hasAccount && <RemoveAccount tenantId={tenant.id} professional={p} onDone={reload} />}
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

const EXPIRES = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

/**
 * Convite de uso único para o profissional criar (ou usar) a conta dele e entrar na equipe. O link só aparece aqui,
 * uma vez: gerar outro ou cancelar invalida o anterior.
 */
function InvitePanel({ tenantId, professional }: { tenantId: string; professional: ProfessionalView }) {
  const [invite, setInvite] = useState<InviteView | null>(null)
  const [copied, setCopied] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const link = invite && `${window.location.origin}/convite#${invite.code}`
  const run = async (action: () => Promise<void>) => {
    setError(null)
    setCopied(false)
    try {
      await action()
    } catch (err) {
      setError(messageOf(err, 'Não foi possível concluir. Tente de novo.'))
    }
  }
  return (
    <div className="mt-2 space-y-2">
      {!invite ? (
        <Button aria-label={`Convidar ${professional.name}`} onClick={() => void run(async () => setInvite(await invites.create(tenantId, professional.id)))}>
          Convidar para a equipe
        </Button>
      ) : (
        <div className="space-y-2 rounded-lg bg-slate-50 p-3 text-sm">
          <p className="text-slate-600">
            Envie este link para {professional.name} (vale até {EXPIRES.format(new Date(invite.expiresAt))}, uma vez só):
          </p>
          <input readOnly aria-label={`Link do convite de ${professional.name}`} value={link!} onFocus={(e) => e.target.select()}
            className="w-full rounded border border-slate-300 bg-white px-2 py-1 font-mono text-xs" />
          <div className="flex gap-2">
            <Button onClick={() => void run(async () => { await navigator.clipboard.writeText(link!); setCopied(true) })}>
              {copied ? 'Copiado!' : 'Copiar link'}
            </Button>
            <Button onClick={() => void run(async () => { await invites.revoke(tenantId, professional.id); setInvite(null) })}>
              Cancelar convite
            </Button>
          </div>
        </div>
      )}
      {error && <Alert>{error}</Alert>}
    </div>
  )
}

/** Quem saiu do estabelecimento perde o acesso; o profissional (e os agendamentos dele) continuam no cadastro. */
function RemoveAccount({ tenantId, professional, onDone }: { tenantId: string; professional: ProfessionalView; onDone: () => Promise<void> }) {
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const remove = async () => {
    try {
      setError(null)
      await invites.removeAccount(tenantId, professional.id)
      await onDone()
    } catch (err) {
      setError(messageOf(err, 'Não foi possível remover o acesso.'))
    }
  }
  return (
    <div className="mt-2 space-y-2">
      {!confirming ? (
        <Button aria-label={`Remover ${professional.name} da equipe`} onClick={() => setConfirming(true)}>Remover da equipe</Button>
      ) : (
        <div className="flex flex-wrap items-center gap-2 text-sm text-slate-600">
          {professional.name} perde o acesso ao estabelecimento.
          <Button onClick={() => void remove()}>Confirmar</Button>
          <Button onClick={() => setConfirming(false)}>Voltar</Button>
        </div>
      )}
      {error && <Alert>{error}</Alert>}
    </div>
  )
}
