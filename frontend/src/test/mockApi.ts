import { vi } from 'vitest'
import type { AuthResponse, Me } from '../api/types'

// Simulador da API para os testes: "MÉTODO /caminho" → resposta. Registra cada chamada (corpo decodificado,
// cabeçalhos e credentials) para o teste conferir exatamente o que o front mandou.

type Reply = unknown | [number, unknown?]
type Handler = Reply | ((body: unknown, call: Call) => Reply)
export type Call = { method: string; path: string; body: unknown; headers: Record<string, string>; credentials?: RequestCredentials }

export function mockApi(routes: Record<string, Handler>) {
  const calls: Call[] = []
  const fetch = vi.fn(async (input: string, init: RequestInit = {}) => {
    const url = new URL(input, 'http://localhost')
    const call: Call = {
      method: init.method ?? 'GET',
      path: url.pathname,
      body: init.body ? JSON.parse(String(init.body)) : undefined,
      headers: (init.headers ?? {}) as Record<string, string>,
      credentials: init.credentials,
    }
    calls.push(call)
    const h = routes[`${call.method} ${call.path}`]
    if (h === undefined) return new Response(JSON.stringify({ status: 404, message: 'Resource not found' }), { status: 404 })
    // a resposta pode ser uma Promise: o teste segura a requisição "em andamento" e solta quando quiser
    const out = typeof h === 'function' ? await (h as (b: unknown, c: Call) => Reply | Promise<Reply>)(call.body, call) : h
    const [status, payload] = Array.isArray(out) && typeof out[0] === 'number' ? (out as [number, unknown?]) : [200, out]
    return new Response(status === 204 || payload === undefined ? null : JSON.stringify(payload), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetch)
  return {
    calls,
    fetch,
    last: (method: string, path: string) => [...calls].reverse().find((c) => c.method === method && c.path === path),
    count: (method: string, path: string) => calls.filter((c) => c.method === method && c.path === path).length,
  }
}

export const USER = { id: 'u1', name: 'Ana Souza', email: 'ana@teste.com' }
export const ME: Me = {
  ...USER,
  tenants: [
    { id: 't1', name: 'Barbearia da Ana', slug: 'barbearia-da-ana', timeZone: 'America/Sao_Paulo', role: 'OWNER' },
    { id: 't2', name: 'Studio Bela', slug: 'studio-bela', timeZone: 'America/Sao_Paulo', role: 'PROFESSIONAL' },
  ],
}
export const session = (token: string): AuthResponse => ({ accessToken: token, user: USER })
export const NO_SESSION: [number, unknown] = [401, { status: 401, message: 'Invalid session' }]
