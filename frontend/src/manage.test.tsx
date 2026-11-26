import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import type { Me } from './api/types'
import { ME, mockApi, session } from './test/mockApi'
import { renderApp } from './test/render'

beforeAll(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-24T15:00:00Z'))
})
afterAll(() => vi.useRealTimers())

const P = '/api/public/barbearia-da-ana'
const TOKEN = 't'.repeat(43)
const BUSINESS = { name: 'Barbearia da Ana', timeZone: 'America/Sao_Paulo', bookingWindowDays: 60, cancelUntilHours: 24 }
const BOOKING = { start: '2026-11-30T09:00:00-03:00', end: '2026-11-30T09:30:00-03:00', serviceId: 's1', serviceName: 'Corte',
  professionalId: 'p1', professionalName: 'João', status: 'CONFIRMED' }
const managed = (over: object = {}, changeable = true) => ({ booking: { ...BOOKING, ...over }, changeable, changeUntil: '2026-11-29T12:00:00Z' })

describe('link pessoal do cliente', () => {
  it('mostra o agendamento e o prazo; cancela com confirmação e recarrega', async () => {
    let state = managed()
    const m = mockApi({
      [`GET ${P}`]: BUSINESS,
      [`POST ${P}/bookings/lookup`]: () => state,
      [`POST ${P}/bookings/cancel`]: () => ((state = managed({ status: 'CANCELLED' }, false)), { ...BOOKING, status: 'CANCELLED' }),
    })
    renderApp(`/agendar/barbearia-da-ana/gerenciar#${TOKEN}`)
    expect(await screen.findByText('Corte com João, segunda, 30/11 às 09:00')).toBeInTheDocument()
    expect(screen.getByText('Situação: Confirmado')).toBeInTheDocument()
    expect(screen.getByText(/até 29\/11\/2026.*09:00/)).toBeInTheDocument()
    expect(m.count('POST', '/api/auth/refresh')).toBe(0)
    expect(m.last('POST', `${P}/bookings/lookup`)!.body).toEqual({ token: TOKEN })
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar agendamento' }))
    await userEvent.click(screen.getByRole('button', { name: 'Voltar' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar agendamento' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sim, cancelar' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Agendamento cancelado.')
    expect(await screen.findByText('Situação: Cancelado')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remarcar' })).toBeNull()
  })

  it('remarca: escolhe um horário, reserva e troca; erro aparece traduzido', async () => {
    let fail = true
    const m = mockApi({
      [`GET ${P}`]: BUSINESS,
      [`POST ${P}/bookings/lookup`]: managed(),
      [`GET ${P}/availability`]: { date: '2026-11-24', timeZone: 'America/Sao_Paulo', slots: ['2026-11-24T14:00:00-03:00'] },
      [`POST ${P}/holds`]: [201, { token: 'h'.repeat(43), expiresAt: '2026-11-24T15:05:00Z' }],
      [`POST ${P}/bookings/reschedule`]: () => (fail ? ((fail = false), [409, { status: 409, message: 'Too late to change' }])
        : { ...BOOKING, start: '2026-11-24T14:00:00-03:00' }),
    })
    renderApp(`/agendar/barbearia-da-ana/gerenciar#${TOKEN}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Remarcar' }))
    await userEvent.click(await screen.findByRole('button', { name: '14:00' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('O prazo para cancelar ou remarcar por aqui acabou.')
    await userEvent.click(screen.getByRole('button', { name: '14:00' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Remarcado para terça, 24/11 às 14:00.')
    expect(m.last('POST', `${P}/bookings/reschedule`)!.body).toEqual({ token: TOKEN, holdToken: 'h'.repeat(43) })
    expect(m.last('POST', `${P}/holds`)!.body).toEqual({ serviceId: 's1', professionalId: 'p1', start: '2026-11-24T14:00' })
  })

  it('fora do prazo: sem botões e com aviso; status de agendamento já finalizado; voltar da remarcação', async () => {
    mockApi({ [`GET ${P}`]: BUSINESS, [`POST ${P}/bookings/lookup`]: managed({}, false) })
    const { unmount } = renderApp(`/agendar/barbearia-da-ana/gerenciar#${TOKEN}`)
    expect(await screen.findByText(/O prazo para cancelar ou remarcar por aqui acabou/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agendamento' })).toBeNull()
    unmount()
    mockApi({ [`GET ${P}`]: BUSINESS, [`POST ${P}/bookings/lookup`]: managed({ status: 'COMPLETED' }, false) })
    const second = renderApp(`/agendar/barbearia-da-ana/gerenciar#${TOKEN}`)
    expect(await screen.findByText('Situação: Atendido')).toBeInTheDocument()
    expect(screen.queryByText(/prazo/)).toBeNull()
    second.unmount()
    mockApi({ [`GET ${P}`]: BUSINESS, [`POST ${P}/bookings/lookup`]: managed(),
      [`GET ${P}/availability`]: { date: '2026-11-24', timeZone: 'America/Sao_Paulo', slots: [] } })
    renderApp(`/agendar/barbearia-da-ana/gerenciar#${TOKEN}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Remarcar' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Voltar' }))
    expect(screen.getByRole('button', { name: 'Remarcar' })).toBeInTheDocument()
  })

  it('link sem token ou que não existe mais', async () => {
    mockApi({ [`GET ${P}`]: BUSINESS, [`POST ${P}/bookings/lookup`]: [404, { status: 404, message: 'Booking not found' }] })
    const { unmount } = renderApp('/agendar/barbearia-da-ana/gerenciar')
    expect(await screen.findByRole('alert')).toHaveTextContent('Link incompleto.')
    unmount()
    renderApp(`/agendar/barbearia-da-ana/gerenciar#${TOKEN}`)
    expect(await screen.findByRole('alert')).toHaveTextContent('Agendamento não encontrado.')
  })
})

const T = '/api/tenants/t1'
const AS_PRO: Me = { ...ME, tenants: [{ ...ME.tenants[0], role: 'PROFESSIONAL' }] }
const logged = (routes: Record<string, unknown>, me: Me = ME) => ({ 'POST /api/auth/refresh': session('tok'), 'GET /api/me': me, ...routes })

describe('política do estabelecimento (painel)', () => {
  it('dono altera e salva; mexer de novo tira o aviso; erro aparece', async () => {
    let first = true
    const m = mockApi(logged({
      [`GET ${T}/policy`]: { minNoticeMinutes: 60, cancelUntilHours: 24, bookingWindowDays: 60 },
      [`PUT ${T}/policy`]: (b: unknown) => (first ? ((first = false), [400, { status: 400, message: 'Validation failed' }]) : b),
    }))
    renderApp('/e/t1/servicos')
    await userEvent.click(await screen.findByRole('link', { name: 'Política' }))
    const notice = await screen.findByLabelText('Antecedência mínima para agendar (minutos)')
    expect(notice).toHaveValue(60)
    fireEvent.change(notice, { target: { value: '120' } })
    fireEvent.change(screen.getByLabelText('Cliente cancela ou remarca até (horas antes)'), { target: { value: '2' } })
    fireEvent.change(screen.getByLabelText('Agenda aberta para quantos dias à frente'), { target: { value: '30' } })
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Confira os campos.')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Política salva.')
    expect(m.last('PUT', `${T}/policy`)!.body).toEqual({ minNoticeMinutes: 120, cancelUntilHours: 2, bookingWindowDays: 30 })
    fireEvent.change(notice, { target: { value: '125' } })
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull())
  })

  it('profissional só vê; erro ao carregar aparece', async () => {
    mockApi(logged({ [`GET ${T}/policy`]: { minNoticeMinutes: 60, cancelUntilHours: 24, bookingWindowDays: 60 } }, AS_PRO))
    const { unmount } = renderApp('/e/t1/politica')
    expect(await screen.findByLabelText('Antecedência mínima para agendar (minutos)')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Salvar' })).toBeNull()
    unmount()
    mockApi(logged({ [`GET ${T}/policy`]: [500, undefined] }))
    renderApp('/e/t1/politica')
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado.')
  })
})

