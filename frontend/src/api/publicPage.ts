import { api } from './client'
import type { AvailabilityView, ClientInput, ManagedView, HoldView, PendingView, PublicBookingView, PublicBusiness, PublicProfessional, PublicService } from './types'

// Página pública do estabelecimento (sem login): o link (slug) identifica o estabelecimento.
const base = (slug: string) => `/api/public/${encodeURIComponent(slug)}`

export const publicPage = {
  business: (slug: string) => api.get<PublicBusiness>(base(slug)),
  services: (slug: string) => api.get<PublicService[]>(`${base(slug)}/services`),
  professionals: (slug: string, serviceId: string) =>
    api.get<PublicProfessional[]>(`${base(slug)}/services/${encodeURIComponent(serviceId)}/professionals`),
  availability: (slug: string, professionalId: string, serviceId: string, date: string) =>
    api.get<AvailabilityView>(`${base(slug)}/availability?${new URLSearchParams({ professionalId, serviceId, date })}`),
  hold: (slug: string, serviceId: string, professionalId: string, start: string) =>
    api.post<HoldView>(`${base(slug)}/holds`, { serviceId, professionalId, start }),
  book: (slug: string, holdToken: string, customer: ClientInput) => api.post<PendingView>(`${base(slug)}/bookings`, { holdToken, customer }),
  confirm: (slug: string, token: string, code: string) => api.post<PublicBookingView>(`${base(slug)}/bookings/confirm`, { token, code }),
  // link pessoal: o token sempre no corpo, nunca na URL
  lookup: (slug: string, token: string) => api.post<ManagedView>(`${base(slug)}/bookings/lookup`, { token }),
  cancel: (slug: string, token: string) => api.post<PublicBookingView>(`${base(slug)}/bookings/cancel`, { token }),
  reschedule: (slug: string, token: string, holdToken: string) =>
    api.post<PublicBookingView>(`${base(slug)}/bookings/reschedule`, { token, holdToken }),
}
