import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Me, ProfessionalView, ServiceView } from './api/types'
import { ME, mockApi, session } from './test/mockApi'
import { renderApp } from './test/render'

const T = '/api/tenants/t1'
const CORTE: ServiceView = { id: 's1', name: 'Corte', durationMinutes: 30, bufferMinutes: 10, priceCents: 4500, active: true }
const BARBA: ServiceView = { id: 's2', name: 'Barba', durationMinutes: 20, bufferMinutes: 0, priceCents: 3000, active: false }
const JOAO: ProfessionalView = { id: 'p1', name: 'João', active: true, serviceIds: ['s1', 's2'] }
const BETO: ProfessionalView = { id: 'p2', name: 'Beto', active: false, serviceIds: [] }
const AS_PROFESSIONAL: Me = { ...ME, tenants: [{ ...ME.tenants[0], role: 'PROFESSIONAL' }] }

const logged = (routes: Record<string, unknown>, me: Me = ME) => ({ 'POST /api/auth/refresh': session('tok'), 'GET /api/me': me, ...routes })

describe('área do estabelecimento', () => {
  it('do início, o nome do estabelecimento leva aos serviços; abas trocam entre serviços e equipe', async () => {
    mockApi(logged({ [`GET ${T}/services`]: [CORTE], [`GET ${T}/professionals`]: [JOAO] }))
    renderApp('/')
    await userEvent.click(await screen.findByRole('link', { name: 'Barbearia da Ana' }))
    expect(await screen.findByText('Corte')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Serviços' })).toHaveAttribute('aria-current', 'page')
    await userEvent.click(screen.getByRole('link', { name: 'Equipe' }))
    expect(await screen.findByText('João')).toBeInTheDocument()
  })

  it('endereço do estabelecimento sem seção abre os serviços', async () => {
    mockApi(logged({ [`GET ${T}/services`]: [] }))
    renderApp('/e/t1')
    expect(await screen.findByText('Nenhum serviço cadastrado ainda.')).toBeInTheDocument()
  })

  it('estabelecimento que não é do usuário: aviso, sem chamar a API dele', async () => {
    const m = mockApi(logged({}))
    renderApp('/e/outro/servicos')
    expect(await screen.findByRole('alert')).toHaveTextContent('Estabelecimento não encontrado.')
    expect(m.calls.some((c) => c.path.includes('/outro/'))).toBe(false)
  })
})

describe('serviços', () => {
  it('lista com duração, intervalo, preço e inativo', async () => {
    mockApi(logged({ [`GET ${T}/services`]: [CORTE, BARBA] }))
    renderApp('/e/t1/servicos')
    const items = await screen.findAllByRole('listitem')
    expect(items[0]).toHaveTextContent(/30 min \+ 10 min de intervalo · R\$\s45,00/)
    expect(within(items[0]).queryByText('inativo')).not.toBeInTheDocument()
    expect(items[1]).toHaveTextContent(/20 min · R\$\s30,00/)
    expect(within(items[1]).getByText('inativo')).toBeInTheDocument()
  })

  it('dono cria serviço: envia centavos e números, e a lista recarrega', async () => {
    let list: ServiceView[] = []
    const m = mockApi(logged({
      [`GET ${T}/services`]: () => list,
      [`POST ${T}/services`]: (b: unknown) => ((list = [{ ...(b as ServiceView), id: 's9' }]), [201, list[0]]),
    }))
    renderApp('/e/t1/servicos')
    await userEvent.click(await screen.findByRole('button', { name: 'Novo serviço' }))
    expect(screen.queryByLabelText('Ativo (aparece para agendamento)')).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Nome'), ' Escova ')
    await userEvent.clear(screen.getByLabelText('Duração (min)'))
    await userEvent.type(screen.getByLabelText('Duração (min)'), '45')
    await userEvent.clear(screen.getByLabelText('Intervalo depois (min)'))
    await userEvent.type(screen.getByLabelText('Intervalo depois (min)'), '5')
    await userEvent.type(screen.getByLabelText('Preço (R$)'), '60,5')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Escova')).toBeInTheDocument()
    expect(m.last('POST', `${T}/services`)!.body).toEqual({ name: 'Escova', durationMinutes: 45, bufferMinutes: 5, priceCents: 6050, active: true })
    expect(screen.queryByRole('heading', { name: 'Novo serviço' })).not.toBeInTheDocument()
  })

  it('preço inválido avisa sem enviar; cancelar fecha o formulário', async () => {
    const m = mockApi(logged({ [`GET ${T}/services`]: [] }))
    renderApp('/e/t1/servicos')
    await userEvent.click(await screen.findByRole('button', { name: 'Novo serviço' }))
    await userEvent.type(screen.getByLabelText('Nome'), 'Escova')
    await userEvent.type(screen.getByLabelText('Preço (R$)'), 'grátis')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Preço inválido')
    expect(m.count('POST', `${T}/services`)).toBe(0)
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByLabelText('Nome')).not.toBeInTheDocument()
  })

  it('dono edita: formulário preenchido, pode desativar; nome repetido mostra o erro da API', async () => {
    let first = true
    const m = mockApi(logged({
      [`GET ${T}/services`]: [CORTE],
      [`PUT ${T}/services/s1`]: () => (first ? ((first = false), [409, { status: 409, message: 'Service name already in use' }]) : CORTE),
    }))
    renderApp('/e/t1/servicos')
    await userEvent.click(await screen.findByRole('button', { name: 'Editar Corte' }))
    expect(screen.getByRole('heading', { name: 'Editar Corte' })).toBeInTheDocument()
    expect(screen.getByLabelText('Nome')).toHaveValue('Corte')
    expect(screen.getByLabelText('Duração (min)')).toHaveValue(30)
    expect(screen.getByLabelText('Intervalo depois (min)')).toHaveValue(10)
    expect(screen.getByLabelText('Preço (R$)')).toHaveValue('45,00')
    await userEvent.click(screen.getByLabelText('Ativo (aparece para agendamento)'))
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe um serviço com esse nome.')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Editar Corte' })).not.toBeInTheDocument())
    expect(m.last('PUT', `${T}/services/s1`)!.body).toEqual({ name: 'Corte', durationMinutes: 30, bufferMinutes: 10, priceCents: 4500, active: false })
  })

  it('profissional da equipe só vê (sem botões de criar ou editar)', async () => {
    mockApi(logged({ [`GET ${T}/services`]: [CORTE] }, AS_PROFESSIONAL))
    renderApp('/e/t1/servicos')
    expect(await screen.findByText('Corte')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Novo serviço' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar Corte' })).not.toBeInTheDocument()
  })

  it('erro ao carregar aparece na tela', async () => {
    mockApi(logged({ [`GET ${T}/services`]: [500, undefined] }))
    renderApp('/e/t1/servicos')
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado. Tente de novo.')
  })
})

describe('equipe', () => {
  it('lista com os serviços de cada um; inativo marcado; sem serviço diz isso', async () => {
    mockApi(logged({ [`GET ${T}/professionals`]: [JOAO, BETO], [`GET ${T}/services`]: [CORTE, BARBA] }))
    renderApp('/e/t1/equipe')
    const items = await screen.findAllByRole('listitem')
    expect(items[0]).toHaveTextContent('Corte, Barba')
    expect(items[1]).toHaveTextContent('Nenhum serviço')
    expect(within(items[1]).getByText('inativo')).toBeInTheDocument()
  })

  it('serviços na ordem do catálogo; id que não existe mais é ignorado (não vira texto vazio)', async () => {
    mockApi(logged({ [`GET ${T}/professionals`]: [{ ...JOAO, serviceIds: ['s9', 's2', 's1'] }, { ...BETO, serviceIds: ['s9'] }], [`GET ${T}/services`]: [CORTE, BARBA] }))
    renderApp('/e/t1/equipe')
    const items = await screen.findAllByRole('listitem')
    expect(items[0]).toHaveTextContent(/^João\s*Corte, Barba/)
    expect(items[1]).toHaveTextContent('Nenhum serviço')
  })

  it('dono cadastra profissional escolhendo serviços (marcar e desmarcar)', async () => {
    let list: ProfessionalView[] = []
    const m = mockApi(logged({
      [`GET ${T}/professionals`]: () => list,
      [`GET ${T}/services`]: [CORTE, BARBA],
      [`POST ${T}/professionals`]: (b: unknown) => ((list = [{ ...(b as ProfessionalView), id: 'p9' }]), [201, list[0]]),
    }))
    renderApp('/e/t1/equipe')
    expect(await screen.findByText('Nenhum profissional cadastrado ainda.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Novo profissional' }))
    await userEvent.type(screen.getByLabelText('Nome'), 'Caio')
    await userEvent.click(screen.getByLabelText('Corte'))
    await userEvent.click(screen.getByLabelText('Barba'))
    await userEvent.click(screen.getByLabelText('Barba'))
    expect(screen.queryByLabelText('Ativo (recebe agendamentos)')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Caio')).toBeInTheDocument()
    expect(m.last('POST', `${T}/professionals`)!.body).toEqual({ name: 'Caio', active: true, serviceIds: ['s1'] })
  })

  it('sem serviços cadastrados, o formulário orienta; cancelar fecha', async () => {
    mockApi(logged({ [`GET ${T}/professionals`]: [], [`GET ${T}/services`]: [] }))
    renderApp('/e/t1/equipe')
    await userEvent.click(await screen.findByRole('button', { name: 'Novo profissional' }))
    expect(screen.getByText('Cadastre os serviços primeiro.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByLabelText('Nome')).not.toBeInTheDocument()
  })

  it('dono edita: serviços marcados, desativa; erro da API aparece', async () => {
    let first = true
    const m = mockApi(logged({
      [`GET ${T}/professionals`]: [JOAO],
      [`GET ${T}/services`]: [CORTE, BARBA],
      [`PUT ${T}/professionals/p1`]: () => (first ? ((first = false), [400, { status: 400, message: 'Unknown service' }]) : JOAO),
    }))
    renderApp('/e/t1/equipe')
    await userEvent.click(await screen.findByRole('button', { name: 'Editar João' }))
    expect(screen.getByLabelText('Corte')).toBeChecked()
    expect(screen.getByLabelText('Barba')).toBeChecked()
    await userEvent.click(screen.getByLabelText('Barba'))
    await userEvent.click(screen.getByLabelText('Ativo (recebe agendamentos)'))
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Algum serviço escolhido não existe mais.')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Editar João' })).not.toBeInTheDocument())
    expect(m.last('PUT', `${T}/professionals/p1`)!.body).toEqual({ name: 'João', active: false, serviceIds: ['s1'] })
  })

  it('erro ao carregar a equipe aparece na tela (sem lista)', async () => {
    mockApi(logged({ [`GET ${T}/professionals`]: [500, undefined], [`GET ${T}/services`]: [CORTE] }))
    renderApp('/e/t1/equipe')
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado. Tente de novo.')
    expect(screen.queryByText('Nenhum profissional cadastrado ainda.')).not.toBeInTheDocument()
  })

  it('profissional da equipe só vê', async () => {
    mockApi(logged({ [`GET ${T}/professionals`]: [JOAO], [`GET ${T}/services`]: [CORTE, BARBA] }, AS_PROFESSIONAL))
    renderApp('/e/t1/equipe')
    expect(await screen.findByText('João')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Novo profissional' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar João' })).not.toBeInTheDocument()
  })
})
