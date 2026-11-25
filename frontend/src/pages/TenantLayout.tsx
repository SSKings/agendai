import { NavLink, Outlet, useOutletContext, useParams } from 'react-router-dom'
import type { Tenant } from '../api/types'
import { useAuth } from '../auth/AuthContext'

/** Área de um estabelecimento: só os da lista do usuário (a API confere de novo em toda rota). */
export function TenantLayout() {
  const { tenantId } = useParams()
  const { me } = useAuth()
  const tenant = me?.tenants.find((t) => t.id === tenantId)
  if (!tenant) return <p role="alert" className="text-slate-600">Estabelecimento não encontrado.</p>
  const tab = ({ isActive }: { isActive: boolean }) =>
    `rounded-lg px-3 py-1.5 text-sm font-medium ${isActive ? 'bg-indigo-600 text-white' : 'text-slate-700 hover:bg-slate-100'}`
  return (
    <section>
      <h1 className="text-2xl font-semibold text-slate-900">{tenant.name}</h1>
      <nav aria-label="Seções do estabelecimento" className="mt-3 flex flex-wrap gap-2">
        <NavLink to="agenda" className={tab}>Agenda</NavLink>
        <NavLink to="servicos" className={tab}>Serviços</NavLink>
        <NavLink to="equipe" className={tab}>Equipe</NavLink>
        <NavLink to="expediente" className={tab}>Expediente</NavLink>
        <NavLink to="bloqueios" className={tab}>Bloqueios</NavLink>
        <NavLink to="horarios" className={tab}>Horários livres</NavLink>
      </nav>
      {/* key: ao trocar de estabelecimento a tela é recriada, e uma resposta atrasada do anterior não a sobrescreve */}
      <div className="mt-6" key={tenant.id}>
        <Outlet context={tenant} />
      </div>
    </section>
  )
}

export function useTenant(): Tenant {
  return useOutletContext<Tenant>()
}
