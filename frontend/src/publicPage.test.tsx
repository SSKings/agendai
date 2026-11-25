import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { mockApi, NO_SESSION } from './test/mockApi'
import { renderApp } from './test/render'

// segunda, 30/11/2026, 12:00 em São Paulo
beforeAll(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-30T15:00:00Z'))
})
afterAll(() => vi.useRealTimers())

const P = '/api/public/barbearia-da-ana'
const BUSINESS = { name: 'Barbearia da Ana', timeZone: 'America/Sao_Paulo' }
const CORTE = { id: 's1', name: 'Corte', durationMinutes: 30, priceCents: 4500 }
const BARBA = { id: 's2', name: 'Barba', durationMinutes: 20, priceCents: 3000 }
const free = (date: string, times: string[]) => ({ date, timeZone: 'America/Sao_Paulo', slots: times.map((t) => `${date}T${t}:00-03:00`) })

describe('página pública do estabelecimento (sem login)', () => {
  it('serviço → profissional → dia → horários livres, no fuso do estabelecimento', async () => {
    const m = mockApi({
      'POST /api/auth/refresh': NO_SESSION,
      [`GET ${P}`]: BUSINESS,
      [`GET ${P}/services`]: [CORTE, BARBA],
      [`GET ${P}/services/s1/professionals`]: [{ id: 'p1', name: 'João' }, { id: 'p2', name: 'Beto' }],
      [`GET ${P}/services/s2/professionals`]: [],
      [`GET ${P}/availability`]: free('2026-11-30', ['09:00', '14:15']),
    })
    renderApp('/agendar/barbearia-da-ana')
    expect(await screen.findByRole('heading', { name: 'Barbearia da Ana' })).toBeInTheDocument()
    // sem tentar renovar sessão: não gasta o limite de login do IP (Wi-Fi do salão compartilhado)
    expect(m.count('POST', '/api/auth/refresh')).toBe(0)
    const corte = screen.getByRole('button', { name: /Corte/ })
    expect(corte).toHaveTextContent(/30 min · R\$\s45,00/)
    await userEvent.click(screen.getByRole('button', { name: /Barba/ }))
    expect(await screen.findByText('Ninguém disponível para esse serviço no momento.')).toBeInTheDocument()
    await userEvent.click(corte)
    expect(corte).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(await screen.findByRole('button', { name: 'João' }))
    const days = screen.getAllByRole('button', { name: /^(segunda|terça|quarta|quinta|sexta|sábado|domingo), / })
    expect(days).toHaveLength(14)
    expect(days[0]).toHaveTextContent('segunda, 30/11')
    expect(days[0]).toHaveAttribute('aria-pressed', 'true')
    const list = await screen.findByRole('list', { name: 'Horários livres' })
    expect(within(list).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['09:00', '14:15'])
    expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith('availability?professionalId=p1&serviceId=s1&date=2026-11-30'))).toBe(true)
    await userEvent.click(days[1])
    await waitFor(() => expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith('date=2026-12-01'))).toBe(true))
  })

  it('dia sem horário e estabelecimento sem serviços dizem isso', async () => {
    mockApi({
      'POST /api/auth/refresh': NO_SESSION,
      [`GET ${P}`]: BUSINESS,
      [`GET ${P}/services`]: [CORTE],
      [`GET ${P}/services/s1/professionals`]: [{ id: 'p1', name: 'João' }],
      [`GET ${P}/availability`]: free('2026-11-30', []),
    })
    const { unmount } = renderApp('/agendar/barbearia-da-ana')
    await userEvent.click(await screen.findByRole('button', { name: /Corte/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'João' }))
    expect(await screen.findByText('Nenhum horário livre nesse dia. Escolha outro.')).toBeInTheDocument()
    unmount()
    mockApi({ 'POST /api/auth/refresh': NO_SESSION, [`GET ${P}`]: BUSINESS, [`GET ${P}/services`]: [] })
    renderApp('/agendar/barbearia-da-ana')
    expect(await screen.findByText('Nenhum serviço disponível no momento.')).toBeInTheDocument()
  })

  it('link que não existe e erros de carregamento aparecem traduzidos', async () => {
    mockApi({ 'POST /api/auth/refresh': NO_SESSION, [`GET /api/public/nao-existe`]: [404, { status: 404, message: 'Business not found' }] })
    const { unmount } = renderApp('/agendar/nao-existe')
    expect(await screen.findByRole('alert')).toHaveTextContent('Estabelecimento não encontrado. Confira o link.')
    unmount()
    mockApi({
      'POST /api/auth/refresh': NO_SESSION,
      [`GET ${P}`]: BUSINESS,
      [`GET ${P}/services`]: [CORTE],
      [`GET ${P}/services/s1/professionals`]: [{ id: 'p1', name: 'João' }],
      [`GET ${P}/availability`]: [400, { status: 400, message: 'Date out of range' }],
    })
    const second = renderApp('/agendar/barbearia-da-ana')
    await userEvent.click(await screen.findByRole('button', { name: /Corte/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'João' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Escolha uma data entre hoje e 180 dias à frente.')
    second.unmount()
    mockApi({
      'POST /api/auth/refresh': NO_SESSION,
      [`GET ${P}`]: BUSINESS,
      [`GET ${P}/services`]: [CORTE],
      [`GET ${P}/services/s1/professionals`]: [500, undefined],
    })
    renderApp('/agendar/barbearia-da-ana')
    await userEvent.click(await screen.findByRole('button', { name: /Corte/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado.')
  })

  it('o início do painel leva à página pública de cada estabelecimento', async () => {
    mockApi({
      'POST /api/auth/refresh': { accessToken: 'tok', user: { id: 'u1', name: 'Ana Souza', email: 'ana@teste.com' } },
      'GET /api/me': (await import('./test/mockApi')).ME,
      [`GET ${P}`]: BUSINESS, [`GET ${P}/services`]: [],
    })
    renderApp('/')
    await userEvent.click(await screen.findByRole('link', { name: 'Página pública: /agendar/barbearia-da-ana' }))
    expect(await screen.findByRole('heading', { name: 'Barbearia da Ana' })).toBeInTheDocument()
  })
})
