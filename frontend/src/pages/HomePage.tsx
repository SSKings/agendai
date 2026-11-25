import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import type { Role } from '../api/types'

const ROLE: Record<Role, string> = { OWNER: 'Dono', PROFESSIONAL: 'Profissional' }

export function HomePage() {
  const { me } = useAuth()
  return (
    <section>
      <h1 className="text-2xl font-semibold text-slate-900">Olá, {me?.name.split(' ')[0]}!</h1>
      <p className="mt-1 text-slate-600">Seus estabelecimentos:</p>
      <ul className="mt-4 grid gap-3 sm:grid-cols-2">
        {me?.tenants.map((t) => (
          <li key={t.id} className="rounded-xl bg-white p-4 shadow-sm ring-1 ring-slate-200">
            <Link to={`/e/${t.id}/servicos`} className="font-semibold text-slate-900 hover:text-indigo-600">{t.name}</Link>
            <p className="text-sm text-slate-500">agendai.app/{t.slug}</p>
            <p className="mt-2 inline-block rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700">{ROLE[t.role]}</p>
          </li>
        ))}
      </ul>
      <p className="mt-6 text-sm text-slate-500">Expediente e agenda chegam nas próximas etapas.</p>
    </section>
  )
}
