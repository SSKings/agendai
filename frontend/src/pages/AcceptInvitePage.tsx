import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { invites } from '../api/invites'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/fields'
import { messageOf } from '../lib/errors'
import { forgetInvite, keepInvite, pendingInvite } from '../lib/pendingInvite'
import { AuthCard } from './AuthCard'

/**
 * /convite#<código>: o código vem no fragmento da URL (o navegador não o envia ao servidor nem o põe no Referer); é
 * guardado nesta aba e tirado da barra de endereço logo ao abrir.
 */
export function AcceptInvitePage() {
  const { status, reload } = useAuth()
  const { hash } = useLocation()
  const navigate = useNavigate()
  const [code] = useState(() => {
    const fromLink = hash.slice(1)
    if (fromLink) keepInvite(fromLink)
    return fromLink || pendingInvite()
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (hash) navigate('/convite', { replace: true })
  }, [hash, navigate])

  const accept = async () => {
    setBusy(true)
    setError(null)
    try {
      const { tenantId } = await invites.accept(code!)
      forgetInvite()
      await reload()
      navigate(`/e/${tenantId}/agenda`, { replace: true })
    } catch (err) {
      forgetInvite()
      setError(messageOf(err, 'Não foi possível aceitar o convite.'))
      setBusy(false)
    }
  }

  const footer = <Link className="font-medium text-indigo-600" to="/" onClick={forgetInvite}>Agora não</Link>
  if (status === 'loading') return <p className="p-6 text-center text-slate-500">Carregando…</p>
  if (!code) {
    return <AuthCard title="Convite" footer={footer}><Alert>Link de convite inválido ou incompleto.</Alert></AuthCard>
  }
  if (status === 'out') {
    return (
      <AuthCard title="Você foi convidado para uma equipe" footer={footer}>
        <p className="text-sm text-slate-600">Entre com a sua conta (ou crie uma) para aceitar o convite.</p>
        <div className="mt-4 flex gap-3">
          <Link to="/entrar" className="flex-1 rounded-lg bg-indigo-600 px-4 py-2 text-center font-semibold text-white">Entrar</Link>
          <Link to="/cadastro" className="flex-1 rounded-lg border border-slate-300 px-4 py-2 text-center font-semibold text-slate-700">Criar conta</Link>
        </div>
      </AuthCard>
    )
  }
  return (
    <AuthCard title="Você foi convidado para uma equipe" footer={footer}>
      <p className="text-sm text-slate-600">Ao aceitar, você entra na equipe como profissional e vê a sua agenda lá.</p>
      {error && <div className="mt-3"><Alert>{error}</Alert></div>}
      {!error && (
        <button type="button" disabled={busy} onClick={() => void accept()}
          className="mt-4 w-full rounded-lg bg-indigo-600 px-4 py-2 font-semibold text-white hover:bg-indigo-700 disabled:opacity-60">
          {busy ? 'Aguarde…' : 'Aceitar convite'}
        </button>
      )}
    </AuthCard>
  )
}
