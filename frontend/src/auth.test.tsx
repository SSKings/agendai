import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp } from './test/render'
import { ME, NO_SESSION, USER, mockApi, session } from './test/mockApi'
import { getAccessToken } from './api/client'
import { render } from '@testing-library/react'
import { useAuth } from './auth/AuthContext'

const logged = (extra: Record<string, unknown> = {}) => ({ 'POST /api/auth/refresh': session('tok'), 'GET /api/me': ME, ...extra })

describe('sessão ao abrir o app', () => {
  it('com cookie de refresh válido: entra direto, sem pedir senha, e mostra os estabelecimentos', async () => {
    const m = mockApi(logged())
    renderApp('/')
    expect(screen.getByText('Carregando…')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Olá, Ana!' })).toBeInTheDocument()
    const cards = screen.getAllByRole('listitem')
    expect(within(cards[0]).getByText('Barbearia da Ana')).toBeInTheDocument()
    expect(within(cards[0]).getByText('agendai.app/barbearia-da-ana')).toBeInTheDocument()
    expect(within(cards[0]).getByText('Dono')).toBeInTheDocument()
    expect(within(cards[1]).getByText('Profissional')).toBeInTheDocument()
    expect(m.last('GET', '/api/me')!.headers.Authorization).toBe('Bearer tok')
  })

  it('sem sessão: área logada manda para Entrar', async () => {
    mockApi({ 'POST /api/auth/refresh': NO_SESSION })
    renderApp('/conta')
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('sem conexão ao abrir: tela de entrar (não fica carregando para sempre)', async () => {
    mockApi({})
    globalThis.fetch = (() => Promise.reject(new TypeError('offline'))) as typeof fetch
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('sessão renovada mas /api/me falha: volta para Entrar', async () => {
    mockApi({ 'POST /api/auth/refresh': session('tok'), 'GET /api/me': [500, undefined] })
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
  })

  it.each(['/entrar', '/cadastro'])('logado, %s leva para o início', async (path) => {
    mockApi(logged())
    renderApp(path)
    expect(await screen.findByRole('heading', { name: 'Olá, Ana!' })).toBeInTheDocument()
  })

  it('rota desconhecida vai para o início', async () => {
    mockApi(logged())
    renderApp('/qualquer/coisa')
    expect(await screen.findByRole('heading', { name: 'Olá, Ana!' })).toBeInTheDocument()
  })

  it('sessão encerrada em outro aparelho: a próxima chamada recusada leva para Entrar', async () => {
    let refreshes = 0
    mockApi({
      'POST /api/auth/refresh': () => (refreshes++ === 0 ? session('tok') : NO_SESSION),
      'GET /api/me': ME,
      'PUT /api/me/password': [401, { status: 401, message: 'Authentication required' }],
    })
    renderApp('/conta')
    await userEvent.type(await screen.findByLabelText('Senha atual'), 'Antiga123')
    await userEvent.type(screen.getByLabelText('Nova senha'), 'Cadeira-Azul-77')
    await userEvent.click(screen.getByRole('button', { name: 'Trocar senha' }))
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('useAuth fora do provedor é erro de programação, com mensagem clara', () => {
    function Bad() {
      useAuth()
      return null
    }
    const err = console.error
    console.error = () => {}
    try {
      expect(() => render(<Bad />)).toThrow('useAuth fora do AuthProvider')
    } finally {
      console.error = err
    }
  })
})

describe('entrar', () => {
  it('e-mail e senha certos: entra; o e-mail vai sem espaços; nada de token no armazenamento', async () => {
    const m = mockApi({ 'POST /api/auth/refresh': NO_SESSION, 'POST /api/auth/login': session('tok'), 'GET /api/me': ME })
    renderApp('/entrar')
    await userEvent.type(await screen.findByLabelText('E-mail'), '  ana@teste.com')
    await userEvent.type(screen.getByLabelText('Senha'), 'Cadeira-Azul-77')
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByRole('heading', { name: 'Olá, Ana!' })).toBeInTheDocument()
    expect(m.last('POST', '/api/auth/login')!.body).toEqual({ email: 'ana@teste.com', password: 'Cadeira-Azul-77' })
    expect(localStorage.length).toBe(0)
  })

  it('senha errada: mensagem traduzida e o botão volta', async () => {
    mockApi({ 'POST /api/auth/refresh': NO_SESSION, 'POST /api/auth/login': [401, { status: 401, message: 'Invalid email or password' }] })
    renderApp('/entrar')
    await userEvent.type(await screen.findByLabelText('E-mail'), 'ana@teste.com')
    await userEvent.type(screen.getByLabelText('Senha'), 'errada123')
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha incorretos.')
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled()
  })

  it('erro inesperado (não da API): mensagem padrão do formulário', async () => {
    mockApi({ 'POST /api/auth/refresh': NO_SESSION, 'POST /api/auth/login': session('tok'), 'GET /api/me': ME })
    renderApp('/entrar')
    await screen.findByLabelText('E-mail')
    globalThis.fetch = (() => Promise.resolve({ status: 200, ok: true, json: () => Promise.reject(new Error('x')) })) as unknown as typeof fetch
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@teste.com')
    await userEvent.type(screen.getByLabelText('Senha'), 'Cadeira-Azul-77')
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível entrar.')
  })

  it('link para o cadastro', async () => {
    mockApi({ 'POST /api/auth/refresh': NO_SESSION })
    renderApp('/entrar')
    await userEvent.click(await screen.findByRole('link', { name: 'Cadastre seu negócio' }))
    expect(screen.getByRole('heading', { name: 'Cadastre seu negócio' })).toBeInTheDocument()
  })
})

describe('cadastro', () => {
  async function fill(password: string) {
    await userEvent.type(await screen.findByLabelText('Seu nome'), ' Ana Souza ')
    await userEvent.type(screen.getByLabelText('Nome do negócio'), ' Barbearia da Ana ')
    await userEvent.type(screen.getByLabelText('E-mail'), ' ana@teste.com ')
    await userEvent.type(screen.getByLabelText('Senha'), password)
    await userEvent.click(screen.getByRole('button', { name: 'Criar conta' }))
  }

  it('cria dono e estabelecimento e já entra; campos sem espaços nas pontas', async () => {
    const m = mockApi({ 'POST /api/auth/refresh': NO_SESSION, 'POST /api/auth/register': [201, session('tok')], 'GET /api/me': ME })
    renderApp('/cadastro')
    await fill('Cadeira-Azul-77')
    expect(await screen.findByRole('heading', { name: 'Olá, Ana!' })).toBeInTheDocument()
    expect(m.last('POST', '/api/auth/register')!.body).toEqual({
      name: 'Ana Souza', businessName: 'Barbearia da Ana', email: 'ana@teste.com', password: 'Cadeira-Azul-77',
    })
  })

  it('senha fora da regra: avisa antes de enviar', async () => {
    const m = mockApi({ 'POST /api/auth/refresh': NO_SESSION })
    renderApp('/cadastro')
    expect(await screen.findByLabelText('Senha')).toHaveAccessibleDescription(/De 8 a 72 caracteres/)
    await fill('semnumero')
    expect(await screen.findByRole('alert')).toHaveTextContent('Senha fraca. De 8 a 72 caracteres')
    expect(m.count('POST', '/api/auth/register')).toBe(0)
  })

  it('e-mail já cadastrado: mensagem da API traduzida', async () => {
    mockApi({ 'POST /api/auth/refresh': NO_SESSION, 'POST /api/auth/register': [409, { status: 409, message: 'Email already in use' }] })
    renderApp('/cadastro')
    await fill('Cadeira-Azul-77')
    expect(await screen.findByRole('alert')).toHaveTextContent('Este e-mail já tem cadastro.')
  })

  it('link para entrar', async () => {
    mockApi({ 'POST /api/auth/refresh': NO_SESSION })
    renderApp('/cadastro')
    await userEvent.click(await screen.findByRole('link', { name: 'Entrar' }))
    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })
})

describe('área logada', () => {
  it('sair: avisa a API (revoga as sessões) e volta para Entrar', async () => {
    const m = mockApi(logged({ 'POST /api/auth/logout': [204] }))
    renderApp('/')
    await userEvent.click(await screen.findByRole('button', { name: 'Sair' }))
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(m.last('POST', '/api/auth/logout')!.headers.Authorization).toBe('Bearer tok')
    expect(getAccessToken()).toBeNull()
  })

  it('sair com a API fora do ar: encerra a sessão local mesmo assim', async () => {
    mockApi(logged({ 'POST /api/auth/logout': [500, undefined] }))
    renderApp('/')
    await userEvent.click(await screen.findByRole('button', { name: 'Sair' }))
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('menu: nome leva à conta; logo volta ao início', async () => {
    mockApi(logged())
    renderApp('/')
    await userEvent.click(await screen.findByRole('link', { name: USER.name }))
    expect(screen.getByRole('heading', { name: 'Sua conta' })).toBeInTheDocument()
    expect(screen.getByText(USER.email)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('link', { name: 'Agendaí' }))
    expect(screen.getByRole('heading', { name: 'Olá, Ana!' })).toBeInTheDocument()
  })

  it('trocar senha: usa o novo token devolvido, limpa o formulário e confirma', async () => {
    const m = mockApi(logged({ 'PUT /api/me/password': session('tok2') }))
    renderApp('/conta')
    await userEvent.type(await screen.findByLabelText('Senha atual'), 'Antiga123')
    await userEvent.type(screen.getByLabelText('Nova senha'), 'Cadeira-Azul-77')
    await userEvent.click(screen.getByRole('button', { name: 'Trocar senha' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Senha trocada')
    expect(m.last('PUT', '/api/me/password')!.body).toEqual({ currentPassword: 'Antiga123', newPassword: 'Cadeira-Azul-77' })
    expect(getAccessToken()).toBe('tok2')
    expect(screen.getByLabelText('Senha atual')).toHaveValue('')
    expect(screen.getByLabelText('Nova senha')).toHaveValue('')
  })

  it('trocar senha: nova fora da regra avisa sem enviar; senha atual errada mostra o erro da API', async () => {
    const m = mockApi(logged({ 'PUT /api/me/password': [400, { status: 400, message: 'Invalid password' }] }))
    renderApp('/conta')
    await userEvent.type(await screen.findByLabelText('Senha atual'), 'Antiga123')
    await userEvent.type(screen.getByLabelText('Nova senha'), 'curta1')
    await userEvent.click(screen.getByRole('button', { name: 'Trocar senha' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Senha fraca.')
    expect(m.count('PUT', '/api/me/password')).toBe(0)
    await userEvent.clear(screen.getByLabelText('Nova senha'))
    await userEvent.type(screen.getByLabelText('Nova senha'), 'Cadeira-Azul-77')
    await userEvent.click(screen.getByRole('button', { name: 'Trocar senha' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Senha atual incorreta.'))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
