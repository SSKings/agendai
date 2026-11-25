import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth, type Status } from './auth/AuthContext'
import { Layout } from './components/Layout'
import { AccountPage } from './pages/AccountPage'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'

/** Libera a rota só no estado pedido: área logada exige sessão; entrar/cadastro só sem sessão. */
function Gate({ need, children }: { need: Exclude<Status, 'loading'>; children: ReactNode }) {
  const { status } = useAuth()
  if (status === 'loading') return <p className="p-6 text-center text-slate-500">Carregando…</p>
  if (status !== need) return <Navigate to={need === 'in' ? '/entrar' : '/'} replace />
  return children
}

export function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/entrar" element={<Gate need="out"><LoginPage /></Gate>} />
        <Route path="/cadastro" element={<Gate need="out"><RegisterPage /></Gate>} />
        <Route element={<Gate need="in"><Layout /></Gate>}>
          <Route index element={<HomePage />} />
          <Route path="conta" element={<AccountPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  )
}
