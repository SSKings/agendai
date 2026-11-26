import { useCallback, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { publicPage } from '../api/publicPage'
import type { HoldView, PendingView, PublicBookingView, PublicProfessional, PublicService } from '../api/types'
import { Alert, Button, Field, Submit } from '../components/fields'
import { useLoad } from '../components/useLoad'
import { useSubmit } from '../components/useSubmit'
import { addDays, formatDay, timeOf, todayIn } from '../lib/dates'
import { formatCents } from '../lib/money'

/** Página do estabelecimento para o cliente (sem login): serviço → profissional → dia → horário. */
export function PublicBookingPage() {
  const { slug = '' } = useParams()
  const load = useCallback(() => Promise.all([publicPage.business(slug), publicPage.services(slug)]), [slug])
  const { data, error } = useLoad(load, 'Não foi possível abrir a página.')
  const [service, setService] = useState<PublicService | null>(null)

  if (error) return <Shell><Alert>{error}</Alert></Shell>
  if (!data) return <Shell><p className="text-slate-500">Carregando…</p></Shell>
  const [business, services] = data
  return (
    <Shell title={business.name}>
      <section aria-labelledby="servicos" className="space-y-2">
        <h2 id="servicos" className="font-semibold text-slate-900">1. Escolha o serviço</h2>
        {services.length === 0 && <p className="text-slate-500">Nenhum serviço disponível no momento.</p>}
        <div className="grid gap-2 sm:grid-cols-2">
          {services.map((s) => (
            <button key={s.id} type="button" aria-pressed={service?.id === s.id} onClick={() => setService(s)}
              className={`rounded-xl p-3 text-left ring-1 ${service?.id === s.id ? 'bg-indigo-600 text-white ring-indigo-600' : 'bg-white ring-slate-200 hover:ring-indigo-300'}`}>
              <span className="block font-medium">{s.name}</span>
              <span className="text-sm opacity-80">{s.durationMinutes} min · {formatCents(s.priceCents)}</span>
            </button>
          ))}
        </div>
      </section>
      {service && <Professionals key={service.id} slug={slug} service={service} timeZone={business.timeZone} />}
    </Shell>
  )
}

function Professionals({ slug, service, timeZone }: { slug: string; service: PublicService; timeZone: string }) {
  const load = useCallback(() => publicPage.professionals(slug, service.id), [slug, service.id])
  const { data, error } = useLoad(load, 'Não foi possível carregar os profissionais.')
  const [professional, setProfessional] = useState<PublicProfessional | null>(null)
  if (error) return <Alert>{error}</Alert>
  if (!data) return null
  return (
    <>
      <section aria-labelledby="profissionais" className="space-y-2">
        <h2 id="profissionais" className="font-semibold text-slate-900">2. Com quem</h2>
        {data.length === 0 && <p className="text-slate-500">Ninguém disponível para esse serviço no momento.</p>}
        <div className="flex flex-wrap gap-2">
          {data.map((p) => (
            <button key={p.id} type="button" aria-pressed={professional?.id === p.id} onClick={() => setProfessional(p)}
              className={`rounded-full px-4 py-1.5 text-sm font-medium ring-1 ${professional?.id === p.id ? 'bg-indigo-600 text-white ring-indigo-600' : 'bg-white text-slate-700 ring-slate-200'}`}>
              {p.name}
            </button>
          ))}
        </div>
      </section>
      {professional && <Days key={professional.id} slug={slug} service={service} professional={professional} timeZone={timeZone} />}
    </>
  )
}

/** Os próximos 14 dias, a partir de hoje no fuso do estabelecimento. */
const DAYS = 14

function Days({ slug, service, professional, timeZone }: { slug: string; service: PublicService; professional: PublicProfessional; timeZone: string }) {
  const today = todayIn(timeZone)
  const [date, setDate] = useState(today)
  const [chosen, setChosen] = useState<string | null>(null)
  const load = useCallback(() => publicPage.availability(slug, professional.id, service.id, date), [slug, professional.id, service.id, date])
  const { data, error, reload } = useLoad(load, 'Não foi possível consultar os horários.')
  if (chosen) {
    return (
      <Checkout slug={slug} service={service} professional={professional} slot={chosen} timeZone={timeZone}
        onBack={() => { setChosen(null); void reload() }} />
    )
  }
  return (
    <section aria-labelledby="dia" className="space-y-3">
      <h2 id="dia" className="font-semibold text-slate-900">3. Dia e horário</h2>
      <div className="flex gap-2 overflow-x-auto pb-1">
        {Array.from({ length: DAYS }, (_, i) => addDays(today, i)).map((d) => (
          <button key={d} type="button" aria-pressed={date === d} onClick={() => setDate(d)}
            className={`shrink-0 rounded-lg px-3 py-1.5 text-sm capitalize ring-1 ${date === d ? 'bg-indigo-600 text-white ring-indigo-600' : 'bg-white text-slate-700 ring-slate-200'}`}>
            {formatDay(d)}
          </button>
        ))}
      </div>
      {error && <Alert>{error}</Alert>}
      {data && (data.slots.length === 0 ? (
        <p className="text-slate-500">Nenhum horário livre nesse dia. Escolha outro.</p>
      ) : (
        <ul aria-label="Horários livres" className="flex flex-wrap gap-2">
          {data.slots.map((s) => (
            <li key={s}>
              <button type="button" onClick={() => setChosen(s)}
                className="rounded-lg bg-indigo-50 px-3 py-1.5 text-sm font-medium text-indigo-700 hover:bg-indigo-100">
                {timeOf(s)}
              </button>
            </li>
          ))}
        </ul>
      ))}
    </section>
  )
}

/**
 * Agendar o horário escolhido: segura por 5 min (Redis), pede os dados, manda o código por e-mail e confirma.
 * Os tokens ficam só na memória desta tela.
 */
function Checkout({ slug, service, professional, slot, timeZone, onBack }: {
  slug: string
  service: PublicService
  professional: PublicProfessional
  slot: string
  timeZone: string
  onBack: () => void
}) {
  // reservar é uma escrita (gasta uma das reservas da origem): uma vez só por horário escolhido, mesmo com o efeito
  // rodando duas vezes (StrictMode em desenvolvimento)
  const once = useRef<Promise<HoldView> | null>(null)
  const load = useCallback(() => (once.current ??= publicPage.hold(slug, service.id, professional.id, slot.slice(0, 16))),
    [slug, service.id, professional.id, slot])
  const { data: hold, error: holdError } = useLoad(load, 'Não foi possível reservar o horário.')
  const [pending, setPending] = useState<PendingView | null>(null)
  const [done, setDone] = useState<PublicBookingView | null>(null)
  const when = `${formatDay(slot.slice(0, 10))} às ${timeOf(slot)}`
  const back = <Button onClick={onBack}>Escolher outro horário</Button>

  if (done) {
    return (
      <section aria-labelledby="pronto" className="space-y-2 rounded-xl bg-emerald-50 p-4 ring-1 ring-emerald-200">
        <h2 id="pronto" className="font-semibold text-emerald-800">Agendamento confirmado!</h2>
        <p className="text-emerald-800">{done.serviceName} com {done.professionalName}, {formatDay(done.start.slice(0, 10))} às {timeOf(done.start)}.</p>
      </section>
    )
  }
  return (
    <section aria-labelledby="checkout" className="space-y-3 rounded-xl bg-white p-4 ring-1 ring-slate-200">
      <h2 id="checkout" className="font-semibold text-slate-900">{service.name} com {professional.name}, {when}</h2>
      {holdError && <><Alert>{holdError}</Alert>{back}</>}
      {hold && !pending && (
        <ClientForm slug={slug} holdToken={hold.token} until={TIME(timeZone).format(new Date(hold.expiresAt))} onSent={setPending} onBack={onBack} />
      )}
      {pending && <CodeForm slug={slug} token={pending.token} onDone={setDone} onBack={onBack} />}
    </section>
  )
}

const TIME = (timeZone: string) => new Intl.DateTimeFormat('pt-BR', { timeZone, hour: '2-digit', minute: '2-digit' })

function ClientForm({ slug, holdToken, until, onSent, onBack }: {
  slug: string
  holdToken: string
  until: string
  onSent: (p: PendingView) => void
  onBack: () => void
}) {
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const { busy, error, onSubmit } = useSubmit(async () => {
    onSent(await publicPage.book(slug, holdToken, { name: name.trim(), phone: phone.trim(), email: email.trim() }))
  }, 'Não foi possível enviar o código.')
  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <p role="status" className="text-sm text-slate-600">Horário reservado para você até {until}.</p>
      <Field label="Seu nome" required maxLength={100} autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} />
      <Field label="Telefone" type="tel" required maxLength={25} autoComplete="tel" hint="Com DDD." value={phone} onChange={(e) => setPhone(e.target.value)} />
      <Field label="E-mail" type="email" required maxLength={254} autoComplete="email" hint="Enviamos um código para confirmar."
        value={email} onChange={(e) => setEmail(e.target.value)} />
      {error && <Alert>{error}</Alert>}
      <div className="flex gap-2">
        <div className="w-48"><Submit busy={busy}>Receber o código</Submit></div>
        <Button onClick={onBack}>Voltar</Button>
      </div>
    </form>
  )
}

function CodeForm({ slug, token, onDone, onBack }: {
  slug: string
  token: string
  onDone: (b: PublicBookingView) => void
  onBack: () => void
}) {
  const [code, setCode] = useState('')
  const { busy, error, onSubmit } = useSubmit(async () => {
    onDone(await publicPage.confirm(slug, token, code.trim()))
  }, 'Não foi possível confirmar.')
  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <p role="status" className="text-sm text-slate-600">Enviamos um código de 6 dígitos para o seu e-mail. Ele vale por 10 minutos.</p>
      <Field label="Código" inputMode="numeric" autoComplete="one-time-code" required pattern="\d{6}" maxLength={6}
        value={code} onChange={(e) => setCode(e.target.value)} />
      {error && <Alert>{error}</Alert>}
      <div className="flex gap-2">
        <div className="w-40"><Submit busy={busy}>Confirmar</Submit></div>
        <Button onClick={onBack}>Escolher outro horário</Button>
      </div>
    </form>
  )
}

function Shell({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <main className="mx-auto min-h-screen max-w-2xl space-y-6 bg-slate-50 px-4 py-8">
      <p className="text-sm font-bold text-indigo-600">Agendaí</p>
      {title && <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>}
      {children}
    </main>
  )
}
