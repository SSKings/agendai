import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { api } from './api/client'
import type { AgendaView, BookingView, Me, ProfessionalView, ServiceView } from './api/types'
import { addDays, dayOf, formatDay } from './lib/dates'
import { ME, mockApi, session } from './test/mockApi'
import { renderApp } from './test/render'

const T = '/api/tenants/t1'
const CORTE: ServiceView = { id: 's1', name: 'Corte', durationMinutes: 30, bufferMinutes: 10, priceCents: 4500, active: true }
const JOAO: ProfessionalView = { id: 'p1', name: 'João', active: true, serviceIds: ['s1'] }
const BETO: ProfessionalView = { id: 'p2', name: 'Beto', active: true, serviceIds: ['s1'] }
// relógio fixo (só a data): nada depende de rodar perto da meia-noite em São Paulo
beforeAll(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-30T15:00:00Z'))
})
afterAll(() => vi.useRealTimers())
const TODAY = '2026-11-30'
const AS_PROFESSIONAL: Me = { ...ME, tenants: [{ ...ME.tenants[0], role: 'PROFESSIONAL' }] }
const logged = (routes: Record<string, unknown>, me: Me = ME) => ({ 'POST /api/auth/refresh': session('tok'), 'GET /api/me': me, ...routes })

const booking = (over: Partial<BookingView>): BookingView => ({
  id: 'b1', professionalId: 'p1', serviceId: 's1',
  customer: { id: 'c1', name: 'Carla Dias', phone: '11987654321' },
  start: `${TODAY}T09:30:00-03:00`, end: `${TODAY}T10:00:00-03:00`,
  status: 'CONFIRMED', priceCents: 4500, outsideHours: false, ...over,
})

describe('datas da agenda (agendamentos)', () => {
  it('dia da semana por extenso e a data local de um instante', () => {
    expect(formatDay('2026-11-30')).toBe('segunda, 30/11')
    expect(formatDay('2026-12-06')).toBe('domingo, 06/12')
    expect(dayOf('2026-11-30T23:00:00-03:00')).toBe('2026-11-30')
  })

  it('PATCH da API manda o corpo', async () => {
    const m = mockApi({ 'PATCH /api/x': { ok: true } })
    await expect(api.patch('/api/x', { a: 1 })).resolves.toEqual({ ok: true })
    expect(m.last('PATCH', '/api/x')!.body).toEqual({ a: 1 })
  })
})

describe('agendar pelos horários livres', () => {
  const free = (slots: string[]) => ({ date: TODAY, timeZone: 'America/Sao_Paulo', slots: slots.map((t) => `${TODAY}T${t}:00-03:00`) })

  it('escolhe o horário, preenche o cliente, agenda e vê o horário sumir da lista', async () => {
    let taken = false
    const m = mockApi(logged({
      [`GET ${T}/services`]: [CORTE],
      [`GET ${T}/professionals`]: [JOAO],
      [`GET ${T}/availability`]: () => free(taken ? ['10:15'] : ['09:30', '10:15']),
      [`POST ${T}/bookings`]: () => ((taken = true), [201, booking({})]),
    }))
    renderApp('/e/t1/horarios')
    await userEvent.click(await screen.findByRole('button', { name: 'Ver horários' }))
    const slot = await screen.findByRole('button', { name: '09:30' })
    expect(slot).toHaveAttribute('aria-pressed', 'false')
    await userEvent.click(slot)
    expect(slot).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('heading', { name: 'Agendar às 09:30' })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Nome do cliente'), ' Carla Dias ')
    await userEvent.type(screen.getByLabelText('Telefone'), ' (11) 98765-4321 ')
    await userEvent.type(screen.getByLabelText('E-mail (opcional)'), '  ')
    await userEvent.type(screen.getByLabelText('Observação (opcional)'), ' Primeira vez ')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Agendado: 09:30 com João.')
    expect(m.last('POST', `${T}/bookings`)!.body).toEqual({
      professionalId: 'p1', serviceId: 's1', start: `${TODAY}T09:30`,
      customer: { name: 'Carla Dias', phone: '(11) 98765-4321' }, note: 'Primeira vez',
    })
    expect(screen.queryByRole('button', { name: '09:30' })).toBeNull()
    expect(screen.queryByRole('heading', { name: /Agendar às/ })).toBeNull()

    // nova consulta tira o aviso; trocar o dia também
    await userEvent.click(screen.getByRole('button', { name: 'Ver horários' }))
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull())
  })

  it('horário ocupado por outro enquanto preenchia: erro traduzido; cancelar fecha o formulário', async () => {
    const m = mockApi(logged({
      [`GET ${T}/services`]: [CORTE],
      [`GET ${T}/professionals`]: [JOAO],
      [`GET ${T}/availability`]: free(['09:30']),
      [`POST ${T}/bookings`]: [409, { status: 409, message: 'Time slot not available' }],
    }))
    renderApp('/e/t1/horarios')
    await userEvent.click(await screen.findByRole('button', { name: 'Ver horários' }))
    await userEvent.click(await screen.findByRole('button', { name: '09:30' }))
    await userEvent.type(screen.getByLabelText('Nome do cliente'), 'Carla')
    await userEvent.type(screen.getByLabelText('Telefone'), '11987654321')
    await userEvent.type(screen.getByLabelText('E-mail (opcional)'), 'carla@exemplo.com')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Esse horário acabou de ser ocupado. Escolha outro.')
    expect(m.last('POST', `${T}/bookings`)!.body).toMatchObject({ customer: { name: 'Carla', phone: '11987654321', email: 'carla@exemplo.com' } })
    expect((m.last('POST', `${T}/bookings`)!.body as { note?: string }).note).toBeUndefined()
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByLabelText('Nome do cliente')).toBeNull()
  })

  it('depois de agendar, mudar o dia apaga o aviso', async () => {
    mockApi(logged({
      [`GET ${T}/services`]: [CORTE],
      [`GET ${T}/professionals`]: [JOAO],
      [`GET ${T}/availability`]: free(['09:30']),
      [`POST ${T}/bookings`]: [201, booking({})],
    }))
    renderApp('/e/t1/horarios')
    await userEvent.click(await screen.findByRole('button', { name: 'Ver horários' }))
    await userEvent.click(await screen.findByRole('button', { name: '09:30' }))
    await userEvent.type(screen.getByLabelText('Nome do cliente'), 'Carla')
    await userEvent.type(screen.getByLabelText('Telefone'), '11987654321')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }))
    await screen.findByRole('status')
    fireEvent.change(screen.getByLabelText('Dia'), { target: { value: '2026-12-31' } })
    expect(screen.queryByRole('status')).toBeNull()
  })
})

describe('agenda', () => {
  const day: AgendaView = {
    truncated: false,
    bookings: [
      booking({ note: 'Primeira vez' }),
      booking({ id: 'b2', professionalId: 'p2', start: `${TODAY}T10:00:00-03:00`, end: `${TODAY}T10:30:00-03:00`, outsideHours: true }),
      booking({ id: 'b3', status: 'CANCELLED', outsideHours: true, customer: { id: 'c2', name: 'Davi', phone: '11900000000' } }),
      booking({ id: 'b4', status: 'COMPLETED', serviceId: 's9', professionalId: 'p9', customer: { id: 'c3', name: 'Eva', phone: '11911111111' } }),
      booking({ id: 'b5', status: 'NO_SHOW', customer: { id: 'c4', name: 'Fábio', phone: '11922222222' } }),
    ],
  }

  it('aba Agenda mostra o dia de hoje: horário, cliente, serviço, profissional, status e "fora do expediente"', async () => {
    const m = mockApi(logged({ [`GET ${T}/bookings`]: day, [`GET ${T}/services`]: [CORTE], [`GET ${T}/professionals`]: [JOAO, BETO] }))
    renderApp('/e/t1/servicos')
    await userEvent.click(await screen.findByRole('link', { name: 'Agenda' }))
    const items = await screen.findAllByRole('listitem')
    expect(items[0]).toHaveTextContent('09:30–10:00 · Carla DiasConfirmado')
    expect(items[0]).toHaveTextContent('Corte com João · 11987654321 · Primeira vez')
    expect(items[0]).not.toHaveTextContent('fora do expediente')
    expect(items[1]).toHaveTextContent('Corte com Beto')
    expect(within(items[1]).getByText('fora do expediente')).toBeInTheDocument()
    expect(items[2]).toHaveTextContent('Cancelado')
    expect(items[2]).not.toHaveTextContent('fora do expediente')
    expect(within(items[2]).queryByRole('button')).toBeNull()
    expect(items[3]).toHaveTextContent('AtendidoServiço com profissional')
    expect(items[4]).toHaveTextContent('Faltou')
    expect(screen.queryByRole('heading', { name: formatDay(TODAY) })).toBeNull()
    expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith(`bookings?from=${TODAY}&to=${TODAY}`))).toBe(true)
  })

  it('atendido, falta e cancelar mudam o status e recarregam; erro da API aparece', async () => {
    let list = day
    const m = mockApi(logged({
      [`GET ${T}/bookings`]: () => list,
      [`GET ${T}/services`]: [CORTE],
      [`GET ${T}/professionals`]: [JOAO, BETO],
      [`PATCH ${T}/bookings/b1`]: (b: unknown) => ((list = { ...day, bookings: [booking({ ...(b as object) })] }), booking({})),
      [`PATCH ${T}/bookings/b2`]: [409, { status: 409, message: 'Booking has not started' }],
    }))
    renderApp('/e/t1/agenda')
    await userEvent.click(await screen.findByRole('button', { name: 'Faltou: Carla Dias às 10:00' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Só dá para marcar atendido ou falta depois do horário de início.')
    await userEvent.click(screen.getByRole('button', { name: 'Atendido: Carla Dias às 09:30' }))
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(1))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(m.last('PATCH', `${T}/bookings/b1`)!.body).toEqual({ status: 'COMPLETED' })
    expect(screen.getByRole('listitem')).toHaveTextContent('Atendido')
  })

  it('cancelar pede o status certo', async () => {
    const m = mockApi(logged({
      [`GET ${T}/bookings`]: { truncated: false, bookings: [booking({})] },
      [`GET ${T}/services`]: [CORTE], [`GET ${T}/professionals`]: [JOAO],
      [`PATCH ${T}/bookings/b1`]: booking({ status: 'CANCELLED' }),
    }))
    renderApp('/e/t1/agenda')
    await userEvent.click(await screen.findByRole('button', { name: 'Cancelar: Carla Dias às 09:30' }))
    await waitFor(() => expect(m.count('PATCH', `${T}/bookings/b1`)).toBe(1))
    expect(m.last('PATCH', `${T}/bookings/b1`)!.body).toEqual({ status: 'CANCELLED' })
  })

  it('semana: 7 dias a partir do escolhido, agrupada por dia; filtro por profissional; aviso de lista cortada', async () => {
    const tomorrow = addDays(TODAY, 1)
    const week: AgendaView = {
      truncated: true,
      bookings: [booking({}), booking({ id: 'b2', start: `${tomorrow}T09:00:00-03:00`, end: `${tomorrow}T09:30:00-03:00` })],
    }
    const m = mockApi(logged({ [`GET ${T}/bookings`]: week, [`GET ${T}/services`]: [CORTE], [`GET ${T}/professionals`]: [JOAO, BETO] }))
    renderApp('/e/t1/agenda')
    await screen.findAllByRole('listitem')
    await userEvent.selectOptions(screen.getByLabelText('Visão'), 'week')
    expect(await screen.findByRole('heading', { name: formatDay(tomorrow) })).toBeInTheDocument()
    expect(screen.getByLabelText('A partir de')).toHaveValue(TODAY)
    expect(within(screen.getByRole('region', { name: formatDay(TODAY) })).getAllByRole('listitem')).toHaveLength(1)
    expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith(`bookings?from=${TODAY}&to=${addDays(TODAY, 6)}`))).toBe(true)
    expect(screen.getByRole('alert')).toHaveTextContent('Há mais agendamentos do que cabem na tela')
    await userEvent.selectOptions(screen.getByLabelText('Profissional'), 'p2')
    await waitFor(() => expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith('professionalId=p2'))).toBe(true))
    await userEvent.selectOptions(screen.getByLabelText('Visão'), 'day')
    expect(screen.getByLabelText('Dia')).toBeInTheDocument()
  })

  it('outro dia; campo de data apagado não consulta; dia vazio diz isso; erro ao carregar aparece', async () => {
    const m = mockApi(logged({ [`GET ${T}/bookings`]: { truncated: false, bookings: [] }, [`GET ${T}/services`]: [], [`GET ${T}/professionals`]: [] }, AS_PROFESSIONAL))
    const { unmount } = renderApp('/e/t1/agenda')
    expect(await screen.findByText('Nenhum agendamento no período.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Dia'), { target: { value: '2026-12-31' } })
    await waitFor(() => expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith('from=2026-12-31&to=2026-12-31'))).toBe(true))
    const before = m.count('GET', `${T}/bookings`)
    fireEvent.change(screen.getByLabelText('Dia'), { target: { value: '' } })
    expect(screen.getByLabelText('Dia')).toHaveValue('2026-12-31')
    expect(m.count('GET', `${T}/bookings`)).toBe(before)
    unmount()
    mockApi(logged({ [`GET ${T}/bookings`]: [500, undefined], [`GET ${T}/services`]: [], [`GET ${T}/professionals`]: [] }))
    renderApp('/e/t1/agenda')
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado.')
  })
})
