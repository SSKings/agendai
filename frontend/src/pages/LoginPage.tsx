import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Alert, Field, Submit } from '../components/fields'
import { useSubmit } from '../components/useSubmit'
import { AuthCard } from './AuthCard'

export function LoginPage() {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const { busy, error, onSubmit } = useSubmit(() => login(email, password), 'Não foi possível entrar.')

  return (
    <AuthCard title="Entrar" footer={<>Novo por aqui? <Link className="font-medium text-indigo-600" to="/cadastro">Cadastre seu negócio</Link></>}>
      <form className="space-y-4" onSubmit={onSubmit}>
        <Field label="E-mail" type="email" autoComplete="email" required maxLength={150} value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label="Senha" type="password" autoComplete="current-password" required maxLength={200} value={password} onChange={(e) => setPassword(e.target.value)} />
        {error && <Alert>{error}</Alert>}
        <Submit busy={busy}>Entrar</Submit>
      </form>
    </AuthCard>
  )
}
