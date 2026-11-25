import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './api/client'
import type { Me, ProfessionalView, ServiceView, TimeOffView } from './api/types'
import { addDays, formatLocal, fromEndInput, timeOf, todayIn, toInput } from './lib/dates'
import { ME, mockApi, session } from './test/mockApi'
import { renderApp } from './test/render'

const T = '/api/tenants/t1'
const CORTE: ServiceView = { id: 's1', name: 'Corte', durationMinutes: 30, bufferMinutes: 10, priceCents: 4500, active: true }
const BARBA: ServiceView = { id: 's2', name: 'Barba', durationMinutes: 20, bufferMinutes: 0, priceCents: 3000, active: true }
const UNHA: ServiceView = { id: 's3', name: 'Unha', durationMinutes: 40, bufferMinutes: 0, priceCents: 3000, active: false }
const JOAO: ProfessionalView = { id: 'p1', name: 'João', active: true, serviceIds: ['s1', 's2'] }
const BETO: ProfessionalView = { id: 'p2', name: 'Beto', active: false, serviceIds: ['s1'] }
const CAIO: ProfessionalView = { id: 'p3', name: 'Caio', active: true, serviceIds: ['s1'] }
const AS_PROFESSIONAL: Me = { ...ME, tenants: [{ ...ME.tenants[0], role: 'PROFESSIONAL' }] }
const TODAY = todayIn('America/Sao_Paulo')

const logged = (routes: Record<string, unknown>, me: Me = ME) => ({ 'POST /api/auth/refresh': session('tok'), 'GET /api/me': me, ...routes })

describe('datas da agenda', () => {
  it('"hoje" no fuso do estabelecimento, não no do navegador nem em UTC', () => {
    const lateNight = new Date('2026-12-01T01:10:00Z')     // segunda 22:10 em São Paulo
    expect(todayIn('America/Sao_Paulo', lateNight)).toBe('2026-11-30')
    expect(todayIn('UTC', lateNight)).toBe('2026-12-01')
    expect(todayIn('UTC')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })

  it('soma dias no calendário, virando mês e ano', () => {
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01')
    expect(addDays('2026-12-01', 91)).toBe('2027-03-02')
  })

  it('formata data-hora local, horário de um início livre e o fim do dia no campo de hora', () => {
    expect(formatLocal('2026-12-25T09:30:00')).toBe('25/12/2026 09:30')
    expect(timeOf('2026-11-30T21:15:00-03:00')).toBe('21:15')
    expect(toInput('24:00')).toBe('00:00')
    expect(toInput('18:00')).toBe('18:00')
    expect(fromEndInput('00:00')).toBe('24:00')
    expect(fromEndInput('18:00')).toBe('18:00')
  })

  it('DELETE da API: 204 sem corpo', async () => {
    const m = mockApi({ 'DELETE /api/x': [204] })
    await expect(api.del('/api/x')).resolves.toBeUndefined()
    expect(m.last('DELETE', '/api/x')!.method).toBe('DELETE')
  })
})

describe('expediente', () => {
  it('abas do estabelecimento levam às telas da agenda', async () => {
    mockApi(logged({ [`GET ${T}/services`]: [], [`GET ${T}/professionals`]: [], [`GET ${T}/time-off`]: [] }))
    renderApp('/e/t1/servicos')
    await userEvent.click(await screen.findByRole('link', { name: 'Expediente' }))
    expect(await screen.findByText('Cadastre a equipe primeiro.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('link', { name: 'Bloqueios' }))
    expect(await screen.findByText('Nenhum bloqueio nos próximos 3 meses.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('link', { name: 'Horários livres' }))
    expect(await screen.findByText('Cadastre um serviço ativo primeiro.')).toBeInTheDocument()
  })

  it('dono vê a semana do primeiro profissional, acrescenta, altera e remove faixas, e salva a semana inteira', async () => {
    const m = mockApi(logged({
      [`GET ${T}/professionals`]: [JOAO, BETO],
      [`GET ${T}/professionals/p1/working-hours`]: { intervals: [{ weekday: 1, start: '09:00', end: '12:00' }, { weekday: 1, start: '13:00', end: '24:00' }] },
      [`PUT ${T}/professionals/p1/working-hours`]: (b: unknown) => b,
    }))
    renderApp('/e/t1/expediente')
    expect(await screen.findByLabelText('Segunda, faixa 1: início')).toHaveValue('09:00')
    expect(screen.getByLabelText('Segunda, faixa 2: fim')).toHaveValue('00:00')
    expect(screen.getByRole('option', { name: 'Beto (inativo)' })).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Terça' })).getByText('Folga')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Adicionar faixa: Terça' }))
    fireEvent.change(screen.getByLabelText('Terça, faixa 1: início'), { target: { value: '08:00' } })
    fireEvent.change(screen.getByLabelText('Terça, faixa 1: fim'), { target: { value: '00:00' } })
    fireEvent.change(screen.getByLabelText('Segunda, faixa 1: fim'), { target: { value: '11:30' } })
    await userEvent.click(screen.getByRole('button', { name: 'Remover Segunda, faixa 2' }))
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Expediente salvo.')
    expect(m.last('PUT', `${T}/professionals/p1/working-hours`)!.body).toEqual({
      intervals: [{ weekday: 1, start: '09:00', end: '11:30' }, { weekday: 2, start: '08:00', end: '24:00' }],
    })
    fireEvent.change(screen.getByLabelText('Segunda, faixa 1: início'), { target: { value: '09:05' } })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Adicionar faixa: Domingo' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Remover Domingo, faixa 1' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('troca de profissional carrega a semana dele; erro da API aparece', async () => {
    mockApi(logged({
      [`GET ${T}/professionals`]: [JOAO, CAIO],
      [`GET ${T}/professionals/p1/working-hours`]: { intervals: [] },
      [`GET ${T}/professionals/p3/working-hours`]: { intervals: [{ weekday: 3, start: '10:00', end: '11:00' }] },
      [`PUT ${T}/professionals/p3/working-hours`]: [400, { status: 400, message: 'Overlapping working hours' }],
    }))
    renderApp('/e/t1/expediente')
    expect(await screen.findAllByText('Folga')).toHaveLength(7)
    await userEvent.selectOptions(screen.getByLabelText('Profissional'), 'p3')
    expect(await screen.findByLabelText('Quarta, faixa 1: início')).toHaveValue('10:00')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Há faixas sobrepostas no mesmo dia.')
  })

  it('profissional da equipe só vê (campos travados, sem botões)', async () => {
    mockApi(logged({
      [`GET ${T}/professionals`]: [JOAO],
      [`GET ${T}/professionals/p1/working-hours`]: { intervals: [{ weekday: 5, start: '09:00', end: '18:00' }] },
    }, AS_PROFESSIONAL))
    renderApp('/e/t1/expediente')
    expect(await screen.findByLabelText('Sexta, faixa 1: início')).toBeDisabled()
    expect(screen.queryByRole('button', { name: /Salvar|Adicionar|Remover/ })).toBeNull()
  })

  it('erros ao carregar a equipe ou o expediente aparecem', async () => {
    mockApi(logged({ [`GET ${T}/professionals`]: [500, undefined] }))
    const { unmount } = renderApp('/e/t1/expediente')
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado.')
    unmount()
    mockApi(logged({ [`GET ${T}/professionals`]: [JOAO], [`GET ${T}/professionals/p1/working-hours`]: [404, { status: 404, message: 'Professional not found' }] }))
    renderApp('/e/t1/expediente')
    expect(await screen.findByRole('alert')).toHaveTextContent('Profissional não encontrado.')
  })
})

describe('bloqueios', () => {
  const HOLIDAY: TimeOffView = { id: 'o1', start: '2026-12-25T00:00:00', end: '2026-12-26T00:00:00', reason: 'Natal' }
  const LUNCH: TimeOffView = { id: 'o2', professionalId: 'p1', start: '2026-12-01T12:00:00', end: '2026-12-01T13:00:00' }
  const GONE: TimeOffView = { id: 'o3', professionalId: 'p9', start: '2026-12-02T12:00:00', end: '2026-12-02T13:00:00' }

  it('lista os próximos 3 meses no fuso do estabelecimento, com quem e o motivo', async () => {
    const m = mockApi(logged({ [`GET ${T}/time-off`]: [LUNCH, GONE, HOLIDAY], [`GET ${T}/professionals`]: [JOAO] }))
    renderApp('/e/t1/bloqueios')
    const items = await screen.findAllByRole('listitem')
    expect(items[0]).toHaveTextContent('01/12/2026 12:00 → 01/12/2026 13:00João')
    expect(items[0]).not.toHaveTextContent('·')
    expect(items[1]).toHaveTextContent('Profissional removido')
    expect(items[2]).toHaveTextContent('Todo o estabelecimento · Natal')
    expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith(`time-off?from=${TODAY}&to=${addDays(TODAY, 91)}`))).toBe(true)
  })

  it('dono cria bloqueio do estabelecimento (sem profissional nem motivo) e de um profissional', async () => {
    let list: TimeOffView[] = []
    const m = mockApi(logged({
      [`GET ${T}/time-off`]: () => list,
      [`GET ${T}/professionals`]: [JOAO],
      [`POST ${T}/time-off`]: (b: unknown) => ((list = [...list, { ...(b as TimeOffView), id: `o${list.length}` }]), [201, list[0]]),
    }))
    renderApp('/e/t1/bloqueios')
    expect(await screen.findByText('Nenhum bloqueio nos próximos 3 meses.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-12-25T00:00' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-12-26T00:00' } })
    await userEvent.type(screen.getByLabelText('Motivo (opcional)'), '   ')
    await userEvent.click(screen.getByRole('button', { name: 'Adicionar bloqueio' }))
    expect(await screen.findByText('25/12/2026 00:00 → 26/12/2026 00:00')).toBeInTheDocument()
    expect(m.last('POST', `${T}/time-off`)!.body).toEqual({ start: '2026-12-25T00:00', end: '2026-12-26T00:00' })
    expect(screen.getByLabelText('Início')).toHaveValue('')

    await userEvent.selectOptions(screen.getByLabelText('Quem'), 'p1')
    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-12-01T12:00' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-12-01T13:00' } })
    await userEvent.type(screen.getByLabelText('Motivo (opcional)'), ' Médico ')
    await userEvent.click(screen.getByRole('button', { name: 'Adicionar bloqueio' }))
    await waitFor(() => expect(m.count('POST', `${T}/time-off`)).toBe(2))
    expect(m.last('POST', `${T}/time-off`)!.body).toEqual({ professionalId: 'p1', start: '2026-12-01T12:00', end: '2026-12-01T13:00', reason: 'Médico' })
  })

  it('erro ao criar aparece traduzido', async () => {
    mockApi(logged({
      [`GET ${T}/time-off`]: [], [`GET ${T}/professionals`]: [],
      [`POST ${T}/time-off`]: [400, { status: 400, message: 'Time off already ended' }],
    }))
    renderApp('/e/t1/bloqueios')
    fireEvent.change(await screen.findByLabelText('Início'), { target: { value: '2020-01-01T00:00' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2020-01-02T00:00' } })
    await userEvent.click(screen.getByRole('button', { name: 'Adicionar bloqueio' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Esse período já terminou.')
  })

  it('dono remove; se já não existir, avisa', async () => {
    let list = [HOLIDAY, LUNCH]
    let gone = false
    const m = mockApi(logged({
      [`GET ${T}/time-off`]: () => list,
      [`GET ${T}/professionals`]: [JOAO],
      [`DELETE ${T}/time-off/o1`]: () => ((list = [LUNCH]), [204]),
      [`DELETE ${T}/time-off/o2`]: () => (gone ? [404, { status: 404, message: 'Time off not found' }] : ((gone = true), [500, undefined])),
    }))
    renderApp('/e/t1/bloqueios')
    await userEvent.click(await screen.findByRole('button', { name: 'Remover bloqueio de 25/12/2026 00:00' }))
    await waitFor(() => expect(screen.queryByText('Todo o estabelecimento · Natal')).not.toBeInTheDocument())
    expect(m.count('DELETE', `${T}/time-off/o1`)).toBe(1)
    await userEvent.click(screen.getByRole('button', { name: 'Remover bloqueio de 01/12/2026 12:00' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado.')
    await userEvent.click(screen.getByRole('button', { name: 'Remover bloqueio de 01/12/2026 12:00' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Bloqueio não encontrado.')
  })

  it('remoção sem resposta da API mostra o texto padrão', async () => {
    mockApi(logged({ [`GET ${T}/time-off`]: [HOLIDAY], [`GET ${T}/professionals`]: [] }))
    renderApp('/e/t1/bloqueios')
    const button = await screen.findByRole('button', { name: 'Remover bloqueio de 25/12/2026 00:00' })
    const { vi } = await import('vitest')
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('rede') }))
    await userEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('Sem conexão com o servidor.')
  })

  it('profissional da equipe só vê; erro ao carregar aparece', async () => {
    mockApi(logged({ [`GET ${T}/time-off`]: [HOLIDAY], [`GET ${T}/professionals`]: [] }, AS_PROFESSIONAL))
    const { unmount } = renderApp('/e/t1/bloqueios')
    expect(await screen.findByText('Todo o estabelecimento · Natal')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remover|Adicionar/ })).toBeNull()
    expect(screen.queryByText('Novo bloqueio')).toBeNull()
    unmount()
    mockApi(logged({ [`GET ${T}/time-off`]: [500, undefined], [`GET ${T}/professionals`]: [] }))
    renderApp('/e/t1/bloqueios')
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado.')
  })
})

describe('horários livres', () => {
  const SLOTS = { date: TODAY, timeZone: 'America/Sao_Paulo', slots: [`${TODAY}T09:00:00-03:00`, `${TODAY}T21:15:00-03:00`] }

  it('escolhe serviço ativo, profissional ativo que faz o serviço e o dia; mostra os horários na hora local', async () => {
    const m = mockApi(logged({
      [`GET ${T}/services`]: [UNHA, CORTE, BARBA],
      [`GET ${T}/professionals`]: [BETO, JOAO, CAIO],
      [`GET ${T}/availability`]: SLOTS,
    }))
    renderApp('/e/t1/horarios')
    const service = await screen.findByLabelText('Serviço')
    expect(within(service).queryByRole('option', { name: /Unha/ })).toBeNull()
    expect(within(screen.getByLabelText('Profissional')).getAllByRole('option').map((o) => o.textContent)).toEqual(['João', 'Caio'])
    expect(screen.getByLabelText('Dia')).toHaveValue(TODAY)
    await userEvent.selectOptions(screen.getByLabelText('Profissional'), 'p3')
    await userEvent.click(screen.getByRole('button', { name: 'Ver horários' }))
    const list = await screen.findByRole('list', { name: 'Horários livres' })
    expect(within(list).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['09:00', '21:15'])
    expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith(`availability?professionalId=p3&serviceId=s1&date=${TODAY}`))).toBe(true)

    fireEvent.change(screen.getByLabelText('Dia'), { target: { value: '2026-12-31' } })
    expect(screen.queryByRole('list', { name: 'Horários livres' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Ver horários' }))
    await screen.findByRole('list', { name: 'Horários livres' })
    await userEvent.selectOptions(screen.getByLabelText('Profissional'), 'p1')
    expect(screen.queryByRole('list', { name: 'Horários livres' })).toBeNull()

    // Barba: só o João faz; o Caio escolhido antes some e o primeiro da lista assume
    await userEvent.selectOptions(screen.getByLabelText('Profissional'), 'p3')
    await userEvent.selectOptions(screen.getByLabelText('Serviço'), 's2')
    expect(within(screen.getByLabelText('Profissional')).getAllByRole('option').map((o) => o.textContent)).toEqual(['João'])
    await userEvent.click(screen.getByRole('button', { name: 'Ver horários' }))
    await screen.findByRole('list', { name: 'Horários livres' })
    expect(m.fetch.mock.calls.some(([u]) => String(u).includes('professionalId=p1&serviceId=s2&date=2026-12-31'))).toBe(true)
  })

  it('dia sem horário diz isso; data fora do período mostra o erro traduzido', async () => {
    let n = 0
    mockApi(logged({
      [`GET ${T}/services`]: [CORTE],
      [`GET ${T}/professionals`]: [JOAO],
      [`GET ${T}/availability`]: () => (n++ === 0 ? { ...SLOTS, slots: [] } : [400, { status: 400, message: 'Date out of range' }]),
    }))
    renderApp('/e/t1/horarios')
    await userEvent.click(await screen.findByRole('button', { name: 'Ver horários' }))
    expect(await screen.findByText('Nenhum horário livre nesse dia.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Ver horários' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Escolha uma data entre hoje e 180 dias à frente.')
    expect(screen.queryByText('Nenhum horário livre nesse dia.')).toBeNull()
  })

  it('serviço sem ninguém ativo que o faça: aviso e sem botão; erro ao carregar o catálogo aparece', async () => {
    mockApi(logged({ [`GET ${T}/services`]: [CORTE], [`GET ${T}/professionals`]: [BETO] }))
    const { unmount } = renderApp('/e/t1/horarios')
    expect(await screen.findByText('Ninguém ativo da equipe faz esse serviço.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ver horários' })).toBeNull()
    unmount()
    mockApi(logged({ [`GET ${T}/services`]: [500, undefined], [`GET ${T}/professionals`]: [] }))
    renderApp('/e/t1/horarios')
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado.')
  })
})

describe('"hoje" das telas no fuso do estabelecimento', () => {
  afterEach(() => vi.useRealTimers())

  it('22:10 de segunda em São Paulo (já terça em UTC): o dia e o período começam na segunda', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-12-01T01:10:00Z'))
    const m = mockApi(logged({ [`GET ${T}/time-off`]: [], [`GET ${T}/professionals`]: [JOAO], [`GET ${T}/services`]: [CORTE] }))
    const { unmount } = renderApp('/e/t1/bloqueios')
    await screen.findByText('Nenhum bloqueio nos próximos 3 meses.')
    expect(m.fetch.mock.calls.some(([u]) => String(u).endsWith('time-off?from=2026-11-30&to=2027-03-01'))).toBe(true)
    unmount()
    renderApp('/e/t1/horarios')
    expect(await screen.findByLabelText('Dia')).toHaveValue('2026-11-30')
  })
})
