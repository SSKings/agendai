import { useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { Alert, Field, Submit } from '../components/fields'
import { useSubmit } from '../components/useSubmit'
import { PASSWORD_HINT, PASSWORD_RULE } from '../lib/messages'

export function AccountPage() {
  const { me, changePassword } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [done, setDone] = useState(false)
  const { busy, error, setError, onSubmit } = useSubmit(async () => {
    setDone(false)
    if (!PASSWORD_RULE.test(next)) {
      setError(`Senha fraca. ${PASSWORD_HINT}`)
      return
    }
    await changePassword(current, next)
    setCurrent('')
    setNext('')
    setDone(true)
  }, 'Não foi possível trocar a senha.')

  return (
    <section className="max-w-md">
      <h1 className="text-2xl font-semibold text-slate-900">Sua conta</h1>
      <p className="mt-1 text-slate-600">{me?.email}</p>
      <form className="mt-6 space-y-4 rounded-2xl bg-white p-6 shadow-sm ring-1 ring-slate-200" onSubmit={onSubmit}>
        <h2 className="font-semibold text-slate-900">Trocar senha</h2>
        <Field label="Senha atual" type="password" autoComplete="current-password" required maxLength={200} value={current} onChange={(e) => setCurrent(e.target.value)} />
        <Field label="Nova senha" type="password" autoComplete="new-password" required maxLength={72} hint={PASSWORD_HINT} value={next} onChange={(e) => setNext(e.target.value)} />
        {error && <Alert>{error}</Alert>}
        {done && <Alert kind="success">Senha trocada. As sessões em outros aparelhos foram encerradas.</Alert>}
        <Submit busy={busy}>Trocar senha</Submit>
      </form>
    </section>
  )
}
