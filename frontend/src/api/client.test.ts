import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, getAccessToken, onSessionEnded, refreshSession, setAccessToken } from './client'
import { NO_SESSION, mockApi, session } from '../test/mockApi'

const EXPIRED: [number, unknown] = [401, { status: 401, message: 'Authentication required' }]

describe('cliente da API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('manda o access token, JSON e cookies só da mesma origem; nada fica no armazenamento do navegador', async () => {
    const m = mockApi({ 'POST /api/x': (body: unknown) => ({ got: body }) })
    setAccessToken('tok')
    await expect(api.post('/api/x', { a: 1 })).resolves.toEqual({ got: { a: 1 } })
    expect(m.calls[0].headers).toEqual({ Accept: 'application/json', 'Content-Type': 'application/json', Authorization: 'Bearer tok' })
    expect(m.calls[0].credentials).toBe('same-origin')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('sem token e sem corpo: nem Authorization nem Content-Type; 204 vira undefined', async () => {
    const m = mockApi({ 'GET /api/y': [204] })
    await expect(api.get('/api/y')).resolves.toBeUndefined()
    expect(m.calls[0].headers).toEqual({ Accept: 'application/json' })
    expect(m.calls[0].body).toBeUndefined()
  })

  it('erro da API: mensagem traduzida e lista de erros; resposta sem JSON vira mensagem genérica', async () => {
    mockApi({
      'PUT /api/a': [400, { status: 400, message: 'Validation failed', errors: ['email: invalid'] }],
      'GET /api/b': [500, undefined],
      'GET /api/c': [409, { status: 409, message: 'Something new' }],
    })
    const a = await api.put('/api/a', {}).catch((e: ApiError) => e)
    expect(a).toBeInstanceOf(ApiError)
    expect(a).toMatchObject({ status: 400, message: 'Confira os campos.', errors: ['email: invalid'] })
    await expect(api.get('/api/b')).rejects.toMatchObject({ status: 500, message: 'Algo deu errado. Tente de novo.', errors: [] })
    await expect(api.get('/api/c')).rejects.toMatchObject({ message: 'Algo deu errado. Tente de novo.' })
  })

  it('sem conexão: erro claro com status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(api.get('/api/z')).rejects.toMatchObject({ status: 0, message: expect.stringContaining('Sem conexão') })
  })

  it('access token vencido: renova pelo cookie (com o cabeçalho anti-CSRF) e repete a chamada uma vez', async () => {
    let first = true
    const m = mockApi({
      'GET /api/me': () => (first ? ((first = false), EXPIRED) : { ok: true }),
      'POST /api/auth/refresh': session('novo'),
    })
    setAccessToken('velho')
    await expect(api.get('/api/me')).resolves.toEqual({ ok: true })
    const refresh = m.last('POST', '/api/auth/refresh')!
    expect(refresh.headers).toEqual({ Accept: 'application/json', 'X-Requested-With': 'agendai' })
    expect(refresh.credentials).toBe('same-origin')
    expect(m.last('GET', '/api/me')!.headers.Authorization).toBe('Bearer novo')
    expect(getAccessToken()).toBe('novo')
  })

  it('refresh recusado: a sessão acaba (token apagado, ouvintes avisados) e o erro chega a quem chamou', async () => {
    mockApi({ 'GET /api/me': EXPIRED, 'POST /api/auth/refresh': NO_SESSION })
    const ended = vi.fn()
    const off = onSessionEnded(ended)
    setAccessToken('velho')
    await expect(api.get('/api/me')).rejects.toMatchObject({ status: 401, message: 'Sua sessão terminou. Entre de novo.' })
    expect(ended).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
    off()
    setAccessToken('outro')
    await api.get('/api/me').catch(() => null)
    expect(ended).toHaveBeenCalledTimes(1)
  })

  it('401 mesmo com o token renovado: não tenta de novo em laço, encerra a sessão', async () => {
    const m = mockApi({ 'GET /api/me': EXPIRED, 'POST /api/auth/refresh': session('novo') })
    const ended = vi.fn()
    const off = onSessionEnded(ended)
    setAccessToken('velho')
    await expect(api.get('/api/me')).rejects.toMatchObject({ status: 401 })
    expect(m.count('POST', '/api/auth/refresh')).toBe(1)
    expect(m.count('GET', '/api/me')).toBe(2)
    expect(ended).toHaveBeenCalledTimes(1)
    off()
  })

  it('sem conexão no refresh: trata como sessão encerrada nesta chamada', async () => {
    const fetch = vi.fn(async (url: string) => {
      if (url === '/api/auth/refresh') throw new TypeError('offline')
      return new Response(JSON.stringify({ status: 401, message: 'Authentication required' }), { status: 401 })
    })
    vi.stubGlobal('fetch', fetch)
    setAccessToken('velho')
    await expect(api.get('/api/me')).rejects.toMatchObject({ status: 401 })
    expect(getAccessToken()).toBeNull()
  })

  it('401 sem token (senha errada no login) não tenta renovar', async () => {
    const m = mockApi({ 'POST /api/auth/login': [401, { status: 401, message: 'Invalid email or password' }] })
    await expect(api.post('/api/auth/login', {})).rejects.toMatchObject({ message: 'E-mail ou senha incorretos.' })
    expect(m.count('POST', '/api/auth/refresh')).toBe(0)
  })

  it('várias chamadas vencidas ao mesmo tempo fazem UM refresh só (senão a 2ª pareceria reuso e derrubaria a sessão)', async () => {
    const valid = new Set<string>()
    const m = mockApi({
      'GET /api/a': (_b: unknown, c: { headers: Record<string, string> }) => (valid.has(c.headers.Authorization) ? 'a' : EXPIRED),
      'GET /api/b': (_b: unknown, c: { headers: Record<string, string> }) => (valid.has(c.headers.Authorization) ? 'b' : EXPIRED),
      'POST /api/auth/refresh': () => (valid.add('Bearer novo'), session('novo')),
    })
    setAccessToken('velho')
    await expect(Promise.all([api.get('/api/a'), api.get('/api/b')])).resolves.toEqual(['a', 'b'])
    expect(m.count('POST', '/api/auth/refresh')).toBe(1)
    // terminado, um refresh novo é outro pedido
    await refreshSession()
    expect(m.count('POST', '/api/auth/refresh')).toBe(2)
  })

  it('entre abas: o refresh roda dentro de uma trava do navegador (Web Locks)', async () => {
    const request = vi.fn((_name: string, fn: () => Promise<unknown>) => fn())
    vi.stubGlobal('navigator', { ...navigator, locks: { request } })
    mockApi({ 'POST /api/auth/refresh': session('novo') })
    await expect(refreshSession()).resolves.toEqual(session('novo'))
    expect(request).toHaveBeenCalledWith('agendai-refresh', expect.any(Function))
  })

  it('refresh sem sessão (sem cookie) devolve null e apaga o token', async () => {
    mockApi({ 'POST /api/auth/refresh': NO_SESSION })
    setAccessToken('x')
    await expect(refreshSession()).resolves.toBeNull()
    expect(getAccessToken()).toBeNull()
  })
})
