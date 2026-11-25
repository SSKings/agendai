import { useCallback, useState } from 'react'
import { useParams } from 'react-router-dom'
import { publicPage } from '../api/publicPage'
import type { PublicService } from '../api/types'
import { Alert } from '../components/fields'
import { useLoad } from '../components/useLoad'
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
  const [professionalId, setProfessionalId] = useState<string | null>(null)
  if (error) return <Alert>{error}</Alert>
  if (!data) return null
  return (
    <>
      <section aria-labelledby="profissionais" className="space-y-2">
        <h2 id="profissionais" className="font-semibold text-slate-900">2. Com quem</h2>
        {data.length === 0 && <p className="text-slate-500">Ninguém disponível para esse serviço no momento.</p>}
        <div className="flex flex-wrap gap-2">
          {data.map((p) => (
            <button key={p.id} type="button" aria-pressed={professionalId === p.id} onClick={() => setProfessionalId(p.id)}
              className={`rounded-full px-4 py-1.5 text-sm font-medium ring-1 ${professionalId === p.id ? 'bg-indigo-600 text-white ring-indigo-600' : 'bg-white text-slate-700 ring-slate-200'}`}>
              {p.name}
            </button>
          ))}
        </div>
      </section>
      {professionalId && <Days key={professionalId} slug={slug} serviceId={service.id} professionalId={professionalId} timeZone={timeZone} />}
    </>
  )
}

/** Os próximos 14 dias, a partir de hoje no fuso do estabelecimento. */
const DAYS = 14

function Days({ slug, serviceId, professionalId, timeZone }: { slug: string; serviceId: string; professionalId: string; timeZone: string }) {
  const today = todayIn(timeZone)
  const [date, setDate] = useState(today)
  const load = useCallback(() => publicPage.availability(slug, professionalId, serviceId, date), [slug, professionalId, serviceId, date])
  const { data, error } = useLoad(load, 'Não foi possível consultar os horários.')
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
        <>
          <ul aria-label="Horários livres" className="flex flex-wrap gap-2">
            {data.slots.map((s) => (
              <li key={s} className="rounded-lg bg-indigo-50 px-3 py-1.5 text-sm font-medium text-indigo-700">{timeOf(s)}</li>
            ))}
          </ul>
          <p className="text-sm text-slate-500">A reserva online chega em breve. Por enquanto, fale com o estabelecimento para agendar.</p>
        </>
      ))}
    </section>
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
