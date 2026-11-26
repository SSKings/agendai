import { useCallback, useState } from 'react'
import { useLocation, useParams } from 'react-router-dom'
import { publicPage } from '../api/publicPage'
import type { PublicBookingView } from '../api/types'
import { Alert, Button } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { messageOf } from '../lib/errors'
import { formatDay, timeOf } from '../lib/dates'
import { Days } from './PublicBookingPage'

const STATUS: Record<string, string> = {
  CONFIRMED: 'Confirmado',
  CANCELLED: 'Cancelado',
  COMPLETED: 'Atendido',
  NO_SHOW: 'Não compareceu',
  PENDING: 'Aguardando o código',
  EXPIRED: 'Expirado',
}

/**
 * Link pessoal do cliente (/agendar/{link}/gerenciar#token): ver, cancelar e remarcar, sem login, dentro do prazo da
 * política. O token vem do fragmento da URL (nunca vai ao servidor no endereço) e só segue no corpo das chamadas.
 */
export function ManageBookingPage() {
  const { slug = '' } = useParams()
  const token = useLocation().hash.slice(1)
  const load = useCallback(() => Promise.all([publicPage.business(slug), publicPage.lookup(slug, token)]), [slug, token])
  const { data, error, reload } = useLoad(load, 'Não foi possível abrir o agendamento.')
  const [mode, setMode] = useState<'view' | 'cancel' | 'reschedule'>('view')
  const [done, setDone] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const run = async (action: () => Promise<PublicBookingView>, message: (b: PublicBookingView) => string) => {
    setActionError(null)
    try {
      const b = await action()
      setDone(message(b))
      setMode('view')
      await reload()
    } catch (err) {
      setActionError(messageOf(err, 'Não foi possível concluir.'))
    }
  }

  if (!token) return <Shell><Alert>Link incompleto. Abra o link exatamente como chegou no e-mail.</Alert></Shell>
  if (error) return <Shell><Alert>{error}</Alert></Shell>
  if (!data) return <Shell><p className="text-slate-500">Carregando…</p></Shell>
  const [business, managed] = data
  const b = managed.booking
  const until = new Intl.DateTimeFormat('pt-BR', { timeZone: business.timeZone, dateStyle: 'short', timeStyle: 'short' })
    .format(new Date(managed.changeUntil))
  return (
    <Shell title={business.name}>
      <section aria-labelledby="seu" className="space-y-1 rounded-xl bg-white p-4 ring-1 ring-slate-200">
        <h2 id="seu" className="font-semibold text-slate-900">Seu agendamento</h2>
        <p>{b.serviceName} com {b.professionalName}, {formatDay(b.start.slice(0, 10))} às {timeOf(b.start)}</p>
        <p className="text-sm text-slate-600">Situação: {STATUS[b.status]}</p>
        {managed.changeable
          ? <p className="text-sm text-slate-600">Dá para cancelar ou remarcar até {until}.</p>
          : b.status === 'CONFIRMED' && <p className="text-sm text-slate-600">O prazo para cancelar ou remarcar por aqui acabou. Fale com o estabelecimento.</p>}
      </section>
      {done && <Alert kind="success">{done}</Alert>}
      {actionError && <Alert>{actionError}</Alert>}
      {managed.changeable && mode === 'view' && (
        <div className="flex gap-2">
          <Button onClick={() => setMode('reschedule')}>Remarcar</Button>
          <Button onClick={() => setMode('cancel')}>Cancelar agendamento</Button>
        </div>
      )}
      {mode === 'cancel' && (
        <div className="flex flex-wrap items-center gap-2 text-sm text-slate-700">
          Cancelar mesmo? O horário fica livre para outra pessoa.
          <Button onClick={() => void run(() => publicPage.cancel(slug, token), () => 'Agendamento cancelado.')}>Sim, cancelar</Button>
          <Button onClick={() => setMode('view')}>Voltar</Button>
        </div>
      )}
      {mode === 'reschedule' && (
        <>
          <Days slug={slug} service={{ id: b.serviceId, name: b.serviceName }} professional={{ id: b.professionalId, name: b.professionalName }}
            timeZone={business.timeZone} windowDays={business.bookingWindowDays}
            onPick={(slot) => void run(async () => {
              const hold = await publicPage.hold(slug, b.serviceId, b.professionalId, slot.slice(0, 16))
              return publicPage.reschedule(slug, token, hold.token)
            }, (n) => `Remarcado para ${formatDay(n.start.slice(0, 10))} às ${timeOf(n.start)}.`)} />
          <Button onClick={() => setMode('view')}>Voltar</Button>
        </>
      )}
    </Shell>
  )
}

function Shell({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <main className="mx-auto min-h-screen max-w-2xl space-y-4 bg-slate-50 px-4 py-8">
      <p className="text-sm font-bold text-indigo-600">Agendaí</p>
      {title && <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>}
      {children}
    </main>
  )
}
