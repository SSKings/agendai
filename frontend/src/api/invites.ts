import { api } from './client'
import type { AcceptedInvite, InviteView } from './types'

const url = (t: string, professionalId: string) =>
  `/api/tenants/${encodeURIComponent(t)}/professionals/${encodeURIComponent(professionalId)}/invite`

export const invites = {
  create: (t: string, professionalId: string) => api.post<InviteView>(url(t, professionalId)),
  revoke: (t: string, professionalId: string) => api.del(url(t, professionalId)),
  /** Tira da equipe a conta vinculada ao profissional (ele saiu do estabelecimento). */
  removeAccount: (t: string, professionalId: string) =>
    api.del(`/api/tenants/${encodeURIComponent(t)}/professionals/${encodeURIComponent(professionalId)}/account`),
  // o código vai no corpo, nunca na URL (log de acesso, histórico)
  accept: (code: string) => api.post<AcceptedInvite>('/api/invites/accept', { code }),
}
