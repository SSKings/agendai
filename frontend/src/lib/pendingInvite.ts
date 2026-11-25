// Código de convite aberto antes do login: guardado só nesta aba (sessionStorage) para sobreviver ao "entrar" ou
// "criar conta"; some ao aceitar, ao recusar ou ao fechar a aba. Sem armazenamento (aba privada), o fluxo só pede
// para abrir o link de novo depois de entrar.

const KEY = 'agendai.invite'

export function pendingInvite(): string | null {
  try {
    return sessionStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function keepInvite(code: string): void {
  try {
    sessionStorage.setItem(KEY, code)
  } catch {
    /* sem armazenamento: segue sem lembrar */
  }
}

export function forgetInvite(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    /* idem */
  }
}
