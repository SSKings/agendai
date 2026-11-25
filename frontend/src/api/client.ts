import { GENERIC_ERROR, OFFLINE, translate } from '../lib/messages'
import type { AuthResponse } from './types'

// Cliente HTTP da API e sessão (ADR-005).
//
// O access token (15 min) fica SÓ em memória: nada em localStorage, onde um XSS o leria. O refresh token mora num
// cookie HttpOnly que o JavaScript nem enxerga e que o navegador só manda para /api/auth (SameSite=Strict). Ao abrir
// ou recarregar a página, e quando a API recusa um access token vencido, o app troca o cookie por um token novo.

/** Exigido pela API no refresh: um site de outra origem não consegue mandar este cabeçalho sem passar pelo CORS. */
export const CSRF_HEADER = { 'X-Requested-With': 'agendai' } as const

let accessToken: string | null = null
const endedListeners = new Set<() => void>()

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(value: string | null): void {
  accessToken = value
}

/** Avisado quando a sessão acaba sem o usuário pedir (refresh vencido, revogado por logout em outro aparelho...). */
export function onSessionEnded(listener: () => void): () => void {
  endedListeners.add(listener)
  return () => {
    endedListeners.delete(listener)
  }
}

export class ApiError extends Error {
  readonly status: number
  readonly errors: string[]

  constructor(status: number, message: string, errors: string[] = []) {
    super(message)
    this.status = status
    this.errors = errors
  }
}

let inFlight: Promise<AuthResponse | null> | null = null

/**
 * Troca o cookie de refresh por um access token novo; null se não há sessão. Uma troca por vez, inclusive entre
 * abas (Web Locks): duas abas mandando o mesmo cookie juntas pareceriam reuso, e a API revogaria a sessão inteira.
 */
export function refreshSession(): Promise<AuthResponse | null> {
  inFlight ??= withLock(renew).finally(() => {
    inFlight = null
  })
  return inFlight
}

function withLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = navigator.locks
  return locks ? locks.request('agendai-refresh', fn) : fn()
}

async function renew(): Promise<AuthResponse | null> {
  const res = await fetch('/api/auth/refresh', {
    method: 'POST',
    headers: { Accept: 'application/json', ...CSRF_HEADER },
    credentials: 'same-origin',
  })
  if (!res.ok) {
    accessToken = null
    return null
  }
  const session = (await res.json()) as AuthResponse
  accessToken = session.accessToken
  return session
}

function endSession(): void {
  accessToken = null
  endedListeners.forEach((l) => l())
}

async function request<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, OFFLINE)
  }
  if (res.status === 401 && accessToken) {
    // access token vencido: renova uma vez e repete; se não der, a sessão acabou
    if (!retried && (await refreshSession().catch(() => null))) return request<T>(method, path, body, true)
    endSession()
  }
  if (res.status === 204) return undefined as T
  const data = await res.json().catch(() => null)
  if (!res.ok) throw new ApiError(res.status, data?.message ? translate(data.message) : GENERIC_ERROR, data?.errors ?? [])
  return data as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  del: (path: string) => request<void>('DELETE', path),
}
