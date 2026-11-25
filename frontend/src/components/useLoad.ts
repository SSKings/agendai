import { useCallback, useEffect, useState } from 'react'
import { messageOf } from '../lib/errors'

/** Carrega dados da API ao montar (e quando `load` muda); `reload` busca de novo depois de uma alteração. */
export function useLoad<T>(load: () => Promise<T>, fallback: string) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const reload = useCallback(async () => {
    try {
      setData(await load())
      setError(null)
    } catch (err) {
      setError(messageOf(err, fallback))
    }
  }, [load, fallback])
  useEffect(() => {
    void reload()
  }, [reload])
  return { data, error, reload }
}
