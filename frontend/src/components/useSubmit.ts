import { useState, type FormEvent } from 'react'
import { messageOf } from '../lib/errors'

/** Envio de formulário: trava o botão, mostra o erro da API (traduzido) ou a mensagem padrão. */
export function useSubmit(action: () => Promise<void>, fallback: string) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(messageOf(err, fallback))
    } finally {
      setBusy(false)
    }
  }
  return { busy, error, setError, onSubmit }
}
