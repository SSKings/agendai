import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth, type Status } from './auth/AuthContext'
import { Layout } from './components/Layout'
import { pendingInvite } from './lib/pendingInvite'
import { AcceptInvitePage } from './pages/AcceptInvitePage'
import { AccountPage } from './pages/AccountPage'
import { AgendaPage } from './pages/AgendaPage'
import { AvailabilityPage } from './pages/AvailabilityPage'
import { HomePage } from './pages/HomePage'
import { HoursPage } from './pages/HoursPage'
import { ManageBookingPage } from './pages/ManageBookingPage'
import { PolicyPage } from './pages/PolicyPage'
import { PublicBookingPage } from './pages/PublicBookingPage'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { ServicesPage } from './pages/ServicesPage'
import { TeamPage } from './pages/TeamPage'
import { TenantLayout } from './pages/TenantLayout'
import { TimeOffPage } from './pages/TimeOffPage'

/** Libera a rota só no estado pedido: área logada exige sessão; entrar/cadastro só sem sessão. */
function Gate({ need, children }: { need: Exclude<Status, 'loading'>; children: ReactNode }) {
  const { status } = useAuth()
  if (status === 'loading') return <p className="p-6 text-center text-slate-500">Carregando…</p>
  // depois de entrar ou criar a conta, um convite aberto antes volta para ser aceito
  if (status !== need) return <Navigate to={need === 'in' ? '/entrar' : pendingInvite() ? '/convite' : '/'} replace />
  return children
}

export function App() {
  return (
    <Routes>
      {/* página do cliente: fora do AuthProvider — não tenta renovar sessão da equipe (nem gasta o limite de login do IP) */}
      <Route path="/agendar/:slug" element={<PublicBookingPage />} />
      <Route path="/agendar/:slug/gerenciar" element={<ManageBookingPage />} />
      <Route path="*" element={<AuthProvider><StaffRoutes /></AuthProvider>} />
    </Routes>
  )
}

function StaffRoutes() {
  return (
    <Routes>
      <Route path="/entrar" element={<Gate need="out"><LoginPage /></Gate>} />
      <Route path="/cadastro" element={<Gate need="out"><RegisterPage /></Gate>} />
      <Route path="/convite" element={<AcceptInvitePage />} />
      <Route element={<Gate need="in"><Layout /></Gate>}>
        <Route index element={<HomePage />} />
        <Route path="conta" element={<AccountPage />} />
        <Route path="e/:tenantId" element={<TenantLayout />}>
          <Route index element={<Navigate to="servicos" replace />} />
          <Route path="agenda" element={<AgendaPage />} />
          <Route path="servicos" element={<ServicesPage />} />
          <Route path="equipe" element={<TeamPage />} />
          <Route path="expediente" element={<HoursPage />} />
          <Route path="bloqueios" element={<TimeOffPage />} />
          <Route path="horarios" element={<AvailabilityPage />} />
          <Route path="politica" element={<PolicyPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
