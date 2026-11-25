import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Alert, Field, Submit } from '../components/fields'
import { useSubmit } from '../components/useSubmit'
import { PASSWORD_HINT, PASSWORD_RULE } from '../lib/messages'
import { AuthCard } from './AuthCard'

export function RegisterPage() {
  const { register } = useAuth()
  const [form, setForm] = useState({ name: '', email: '', password: '', businessName: '' })
  const set = (field: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [field]: e.target.value })
  const { busy, error, setError, onSubmit } = useSubmit(async () => {
    if (!PASSWORD_RULE.test(form.password)) {
      setError(`Senha fraca. ${PASSWORD_HINT}`)
      return
    }
    await register({ name: form.name.trim(), email: form.email.trim(), password: form.password, businessName: form.businessName.trim() })
  }, 'Não foi possível concluir o cadastro.')

  return (
    <AuthCard title="Cadastre seu negócio" footer={<>Já tem conta? <Link className="font-medium text-indigo-600" to="/entrar">Entrar</Link></>}>
      <form className="space-y-4" onSubmit={onSubmit}>
        <Field label="Seu nome" autoComplete="name" required maxLength={100} value={form.name} onChange={set('name')} />
        <Field label="Nome do negócio" autoComplete="organization" required maxLength={100} value={form.businessName} onChange={set('businessName')} />
        <Field label="E-mail" type="email" autoComplete="email" required maxLength={150} value={form.email} onChange={set('email')} />
        <Field label="Senha" type="password" autoComplete="new-password" required maxLength={72} hint={PASSWORD_HINT} value={form.password} onChange={set('password')} />
        {error && <Alert>{error}</Alert>}
        <Submit busy={busy}>Criar conta</Submit>
      </form>
    </AuthCard>
  )
}
