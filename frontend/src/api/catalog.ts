import { api } from './client'
import type { ProfessionalInput, ProfessionalView, ServiceInput, ServiceView } from './types'

const base = (tenantId: string) => `/api/tenants/${encodeURIComponent(tenantId)}`

export const catalog = {
  services: (t: string) => api.get<ServiceView[]>(`${base(t)}/services`),
  createService: (t: string, input: ServiceInput) => api.post<ServiceView>(`${base(t)}/services`, input),
  updateService: (t: string, id: string, input: ServiceInput) =>
    api.put<ServiceView>(`${base(t)}/services/${encodeURIComponent(id)}`, input),
  professionals: (t: string) => api.get<ProfessionalView[]>(`${base(t)}/professionals`),
  createProfessional: (t: string, input: ProfessionalInput) => api.post<ProfessionalView>(`${base(t)}/professionals`, input),
  updateProfessional: (t: string, id: string, input: ProfessionalInput) =>
    api.put<ProfessionalView>(`${base(t)}/professionals/${encodeURIComponent(id)}`, input),
}
