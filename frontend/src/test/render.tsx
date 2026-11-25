import { render } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { App } from '../App'

/** Mostra o endereço atual (caminho + fragmento) para o teste conferir o que a tela fez com a URL. */
function LocationProbe() {
  const { pathname, hash } = useLocation()
  return <span hidden data-testid="location">{pathname + hash}</span>
}

/** Renderiza o app inteiro numa rota, como no navegador (a sessão vem do refresh simulado no mockApi). */
export function renderApp(path = '/') {
  return render(<MemoryRouter initialEntries={[path]}><App /><LocationProbe /></MemoryRouter>)
}
