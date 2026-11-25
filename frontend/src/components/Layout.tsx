import { CalendarDays, LogOut, UserRound } from 'lucide-react'
import { Link, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export function Layout() {
  const { me, logout } = useAuth()
  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <nav className="mx-auto flex max-w-4xl items-center gap-4 px-4 py-3">
          <Link to="/" className="flex items-center gap-2 text-lg font-bold text-indigo-600">
            <CalendarDays aria-hidden className="h-5 w-5" /> Agendaí
          </Link>
          <span className="flex-1" />
          <Link to="/conta" className="flex items-center gap-1 text-sm text-slate-700 hover:text-indigo-600">
            <UserRound aria-hidden className="h-4 w-4" /> {me?.name}
          </Link>
          <button onClick={() => void logout()} className="flex items-center gap-1 text-sm text-slate-700 hover:text-red-600">
            <LogOut aria-hidden className="h-4 w-4" /> Sair
          </button>
        </nav>
      </header>
      <main className="mx-auto max-w-4xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
