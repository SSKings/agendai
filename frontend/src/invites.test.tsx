import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Me, ProfessionalView, ServiceView, TimeOffView } from './api/types'
import { forgetInvite, keepInvite, pendingInvite } from './lib/pendingInvite'
import { ME, NO_SESSION, mockApi, session } from './test/mockApi'
import { renderApp } from './test/render'

const T = '/api/tenants/t1'
const CORTE: ServiceView = { id: 's1', name: 'Corte', durationMinutes: 30, bufferMinutes: 0, priceCents: 0, active: true }
const JOAO: ProfessionalView = { id: 'p1', name: 'João', active: true, serviceIds: ['s1'], hasAccount: true, self: false }
const BETO: ProfessionalView = { id: 'p2', name: 'Beto', active: true, serviceIds: ['s1'], hasAccount: false, self: false }
const AS_PRO: Me = { ...ME, tenants: [{ ...ME.tenants[0], role: 'PROFESSIONAL' }] }
const CODE = 'a'.repeat(43)
const logged = (routes: Record<string, unknown>, me: Me = ME) => ({ 'POST /api/auth/refresh': session('tok'), 'GET /api/me': me, ...routes })

afterEach(() => forgetInvite())

describe('convite guardado na aba', () => {
  it('guarda, lê e esquece; sem armazenamento (aba privada) segue sem lembrar e sem quebrar', () => {
    keepInvite(CODE)
    expect(pendingInvite()).toBe(CODE)
    forgetInvite()
    expect(pendingInvite()).toBeNull()
    const fail = () => { throw new Error('bloqueado') }
    const spies = [vi.spyOn(Storage.prototype, 'getItem').mockImplementation(fail), vi.spyOn(Storage.prototype, 'setItem').mockImplementation(fail),
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(fail)]
    expect(() => keepInvite(CODE)).not.toThrow()
    expect(pendingInvite()).toBeNull()
    expect(() => forgetInvite()).not.toThrow()
    spies.forEach((s) => s.mockRestore())
  })
})

describe('convidar para a equipe', () => {
  it('dono gera o link (uma vez), copia e cancela; quem já tem conta aparece como tal', async () => {
    const writeText = vi.fn(async () => {})
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const m = mockApi(logged({
      [`GET ${T}/professionals`]: [BETO, JOAO, { ...JOAO, id: 'p3', name: 'Caio', self: true }],
      [`GET ${T}/services`]: [CORTE],
      [`POST ${T}/professionals/p2/invite`]: [201, { code: CODE, expiresAt: '2026-12-03T12:00:00Z' }],
      [`DELETE ${T}/professionals/p2/invite`]: [204],
    }))
    renderApp('/e/t1/equipe')
    const items = await screen.findAllByRole('listitem')
    expect(within(items[1]).getByText('com conta')).toBeInTheDocument()
    expect(within(items[2]).getByText('você')).toBeInTheDocument()
    expect(within(items[1]).queryByRole('button', { name: /Convidar/ })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Convidar Beto' }))
    const link = await screen.findByLabelText('Link do convite de Beto')
    expect(link).toHaveValue(`${window.location.origin}/convite#${CODE}`)
    expect(screen.getByText(/vale até \d{2}\/12\/2026/)).toBeInTheDocument()
    await userEvent.click(link)
    await userEvent.click(screen.getByRole('button', { name: 'Copiar link' }))
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/convite#${CODE}`)
    expect(await screen.findByRole('button', { name: 'Copiado!' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar convite' }))
    expect(await screen.findByRole('button', { name: 'Convidar Beto' })).toBeInTheDocument()
    expect(m.count('DELETE', `${T}/professionals/p2/invite`)).toBe(1)
  })

  it('erro ao convidar aparece traduzido; profissional da equipe não vê o botão', async () => {
    mockApi(logged({
      [`GET ${T}/professionals`]: [BETO], [`GET ${T}/services`]: [],
      [`POST ${T}/professionals/p2/invite`]: [409, { status: 409, message: 'Professional already has an account' }],
    }))
    const { unmount } = renderApp('/e/t1/equipe')
    await userEvent.click(await screen.findByRole('button', { name: 'Convidar Beto' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Esse profissional já tem conta vinculada.')
    unmount()
    mockApi(logged({ [`GET ${T}/professionals`]: [BETO], [`GET ${T}/services`]: [] }, AS_PRO))
    renderApp('/e/t1/equipe')
    await screen.findByText('Beto')
    expect(screen.queryByRole('button', { name: /Convidar/ })).toBeNull()
  })
})

describe('aceitar o convite', () => {
  it('logado: tira o código da URL, aceita, recarrega os estabelecimentos e abre a agenda', async () => {
    let me: Me = { ...ME, tenants: [] }
    const m = mockApi({
      'POST /api/auth/refresh': session('tok'),
      'GET /api/me': () => me,
      'POST /api/invites/accept': () => ((me = AS_PRO), { tenantId: 't1' }),
      [`GET ${T}/bookings`]: { truncated: false, bookings: [] },
      [`GET ${T}/services`]: [CORTE],
      [`GET ${T}/professionals`]: [{ ...JOAO, self: true }],
    })
    renderApp(`/convite#${CODE}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Aceitar convite' }))
    expect(await screen.findByRole('heading', { name: 'Agenda' })).toBeInTheDocument()
    expect(m.last('POST', '/api/invites/accept')!.body).toEqual({ code: CODE })
    expect(m.count('GET', '/api/me')).toBe(2)
    expect(pendingInvite()).toBeNull()
    expect(screen.queryByLabelText('Profissional')).toBeNull()   // profissional: sem filtro, só a própria agenda
  })

  it('código inválido ou usado: mensagem clara, sem botão, e o código é esquecido', async () => {
    mockApi(logged({ 'POST /api/invites/accept': [400, { status: 400, message: 'Invalid or expired invite' }] }))
    renderApp(`/convite#${CODE}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Aceitar convite' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Convite inválido, já usado ou vencido.')
    expect(screen.queryByRole('button', { name: 'Aceitar convite' })).toBeNull()
    expect(pendingInvite()).toBeNull()
  })

  it('sem sessão: pede para entrar e, depois do login, volta ao convite guardado', async () => {
    let logged = false
    mockApi({
      'POST /api/auth/refresh': NO_SESSION,
      'POST /api/auth/login': () => ((logged = true), session('tok')),
      'GET /api/me': () => (logged ? ME : [401, { status: 401, message: 'Invalid session' }]),
    })
    renderApp(`/convite#${CODE}`)
    expect(await screen.findByRole('heading', { name: 'Você foi convidado para uma equipe' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/cadastro')
    expect(pendingInvite()).toBe(CODE)
    await userEvent.click(screen.getByRole('link', { name: 'Entrar' }))
    await userEvent.type(await screen.findByLabelText('E-mail'), 'carlos@teste.com')
    await userEvent.type(screen.getByLabelText('Senha'), 'Cadeira7781')
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByRole('button', { name: 'Aceitar convite' })).toBeInTheDocument()
  })

  it('sem código: link inválido; "Agora não" esquece e volta ao início', async () => {
    mockApi(logged({}))
    renderApp('/convite')
    expect(await screen.findByRole('alert')).toHaveTextContent('Link de convite inválido ou incompleto.')
    keepInvite(CODE)
    await userEvent.click(screen.getByRole('link', { name: 'Agora não' }))
    expect(pendingInvite()).toBeNull()
    expect(await screen.findByText(/Olá/)).toBeInTheDocument()
  })
})

describe('aceitar o convite: URL e espera', () => {
  it('o código sai da barra de endereço logo ao abrir; clicar duas vezes manda um pedido só', async () => {
    let release: (v: unknown) => void = () => {}
    const m = mockApi(logged({ 'POST /api/invites/accept': () => new Promise((r) => { release = r }) }))
    const { container } = renderApp(`/convite#${CODE}`)
    const button = await screen.findByRole('button', { name: 'Aceitar convite' })
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/convite$/)
    await userEvent.click(button)
    expect(await screen.findByRole('button', { name: 'Aguarde…' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Aguarde…' }))
    expect(m.count('POST', '/api/invites/accept')).toBe(1)
    expect(m.last('POST', '/api/invites/accept')!.body).toEqual({ code: CODE })
    release([400, { status: 400, message: 'Invalid or expired invite' }])
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(container).toBeTruthy()
  })
})

describe('remover da equipe', () => {
  it('dono confirma e a lista recarrega; pode voltar atrás; erro aparece', async () => {
    let linked = true
    let fail = true
    const m = mockApi(logged({
      [`GET ${T}/professionals`]: () => [{ ...JOAO, hasAccount: linked }],
      [`GET ${T}/services`]: [],
      [`DELETE ${T}/professionals/p1/account`]: () => (fail ? ((fail = false), [500, undefined]) : ((linked = false), [204])),
    }))
    renderApp('/e/t1/equipe')
    await userEvent.click(await screen.findByRole('button', { name: 'Remover João da equipe' }))
    await userEvent.click(screen.getByRole('button', { name: 'Voltar' }))
    await userEvent.click(screen.getByRole('button', { name: 'Remover João da equipe' }))
    expect(screen.getByText(/João perde o acesso/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado.')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }))
    expect(await screen.findByRole('button', { name: 'Convidar João' })).toBeInTheDocument()
    expect(m.count('DELETE', `${T}/professionals/p1/account`)).toBe(2)
  })
})

describe('sair da conta esquece o convite da aba', () => {
  it('logout limpa o convite guardado', async () => {
    mockApi(logged({ 'POST /api/auth/logout': [204] }))
    keepInvite(CODE)
    renderApp('/convite')
    await screen.findByRole('button', { name: 'Aceitar convite' })
    renderApp('/')
    await userEvent.click((await screen.findAllByRole('button', { name: /Sair/ }))[0])
    await waitFor(() => expect(pendingInvite()).toBeNull())
  })
})

describe('profissional da equipe: só a própria agenda', () => {
  const OWN: TimeOffView = { id: 'o1', professionalId: 'p1', start: '2026-12-01T12:00:00', end: '2026-12-01T13:00:00', reason: 'Almoço' }
  const OTHER: TimeOffView = { id: 'o2', professionalId: 'p2', start: '2026-12-01T14:00:00', end: '2026-12-01T15:00:00' }

  it('bloqueios: o formulário só oferece ele mesmo (sem feriado) e só remove os próprios', async () => {
    const m = mockApi(logged({
      [`GET ${T}/time-off`]: [OWN, OTHER],
      [`GET ${T}/professionals`]: [{ ...JOAO, self: true }, BETO],
      [`POST ${T}/time-off`]: [201, OWN],
    }, AS_PRO))
    renderApp('/e/t1/bloqueios')
    const who = await screen.findByLabelText('Quem')
    expect(within(who).getAllByRole('option').map((o) => o.textContent)).toEqual(['João'])
    expect(screen.getByRole('button', { name: 'Remover bloqueio de 01/12/2026 12:00' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remover bloqueio de 01/12/2026 14:00' })).toBeNull()
    await userEvent.type(screen.getByLabelText('Início'), '2026-12-02T12:00')
    await userEvent.type(screen.getByLabelText('Fim'), '2026-12-02T13:00')
    await userEvent.click(screen.getByRole('button', { name: 'Adicionar bloqueio' }))
    await waitFor(() => expect(m.count('POST', `${T}/time-off`)).toBe(1))
    expect(m.last('POST', `${T}/time-off`)!.body).toMatchObject({ professionalId: 'p1' })
  })

  it('bloqueios sem vínculo: nem formulário nem botão de remover', async () => {
    mockApi(logged({ [`GET ${T}/time-off`]: [OWN], [`GET ${T}/professionals`]: [JOAO] }, AS_PRO))
    renderApp('/e/t1/bloqueios')
    await screen.findByText(/01\/12\/2026 12:00/)
    expect(screen.queryByText('Novo bloqueio')).toBeNull()
    expect(screen.queryByRole('button', { name: /Remover/ })).toBeNull()
  })

  it('horários livres: só ele mesmo na lista de profissionais', async () => {
    mockApi(logged({ [`GET ${T}/services`]: [CORTE], [`GET ${T}/professionals`]: [BETO, { ...JOAO, self: true }] }, AS_PRO))
    renderApp('/e/t1/horarios')
    const pro = await screen.findByLabelText('Profissional')
    expect(within(pro).getAllByRole('option').map((o) => o.textContent)).toEqual(['João'])
  })
})
