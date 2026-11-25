import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { auth } from '../api/agendai'
import { onSessionEnded, refreshSession, setAccessToken } from '../api/client'
import type { Me, RegisterInput } from '../api/types'

export type Status = 'loading' | 'in' | 'out'

interface AuthState {
  status: Status
  me: Me | null
  login: (email: string, password: string) => Promise<void>
  register: (input: RegisterInput) => Promise<void>
  logout: () => Promise<void>
  changePassword: (current: string, next: string) => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>('loading')
  const [me, setMe] = useState<Me | null>(null)

  const clear = useCallback(() => {
    setAccessToken(null)
    setMe(null)
    setStatus('out')
  }, [])

  const enter = useCallback(async (token: string) => {
    setAccessToken(token)
    setMe(await auth.me())
    setStatus('in')
  }, [])

  useEffect(() => onSessionEnded(clear), [clear])

  // Página aberta ou recarregada: o token sumiu da memória; o cookie de refresh (se houver) traz a sessão de volta.
  useEffect(() => {
    refreshSession()
      .then((s) => (s ? enter(s.accessToken) : clear()))
      .catch(clear)
  }, [enter, clear])

  const value: AuthState = {
    status,
    me,
    login: async (email, password) => enter((await auth.login(email.trim(), password)).accessToken),
    register: async (input) => enter((await auth.register(input)).accessToken),
    logout: async () => {
      try {
        await auth.logout()
      } catch {
        /* sessão já encerrada no servidor */
      }
      clear()
    },
    // a API revoga todas as sessões e devolve uma nova só para este aparelho
    changePassword: async (current, next) => setAccessToken((await auth.changePassword(current, next)).accessToken),
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth fora do AuthProvider')
  return ctx
}
