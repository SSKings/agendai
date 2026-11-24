import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../App'

/** Renderiza o app inteiro numa rota, como no navegador (a sessão vem do refresh simulado no mockApi). */
export function renderApp(path = '/') {
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
}
