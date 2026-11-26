import { api } from './client'
import type { Policy } from './types'

const url = (t: string) => `/api/tenants/${encodeURIComponent(t)}/policy`

export const policies = {
  get: (t: string) => api.get<Policy>(url(t)),
  update: (t: string, p: Policy) => api.put<Policy>(url(t), p),
}
