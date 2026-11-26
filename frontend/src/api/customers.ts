import { api } from './client'
import type { CustomerExport } from './types'

const base = (t: string, id: string) => `/api/tenants/${encodeURIComponent(t)}/customers/${encodeURIComponent(id)}`

/** Pedidos do titular dos dados (LGPD): só o dono do estabelecimento. */
export const customers = {
  exportData: (t: string, id: string) => api.get<CustomerExport>(`${base(t, id)}/export`),
  forget: (t: string, id: string) => api.del(base(t, id)),
}
