import { api } from './client'
import type { AvailabilityView, PublicBusiness, PublicProfessional, PublicService } from './types'

// Página pública do estabelecimento (sem login): o link (slug) identifica o estabelecimento.
const base = (slug: string) => `/api/public/${encodeURIComponent(slug)}`

export const publicPage = {
  business: (slug: string) => api.get<PublicBusiness>(base(slug)),
  services: (slug: string) => api.get<PublicService[]>(`${base(slug)}/services`),
  professionals: (slug: string, serviceId: string) =>
    api.get<PublicProfessional[]>(`${base(slug)}/services/${encodeURIComponent(serviceId)}/professionals`),
  availability: (slug: string, professionalId: string, serviceId: string, date: string) =>
    api.get<AvailabilityView>(`${base(slug)}/availability?${new URLSearchParams({ professionalId, serviceId, date })}`),
}
