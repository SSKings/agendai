import { api } from './client'
import type { AgendaView, BookingInput, BookingStatus, BookingView } from './types'

const base = (tenantId: string) => `/api/tenants/${encodeURIComponent(tenantId)}/bookings`

export const bookings = {
  create: (t: string, input: BookingInput) => api.post<BookingView>(base(t), input),
  agenda: (t: string, from: string, to: string, professionalId?: string) =>
    api.get<AgendaView>(`${base(t)}?${new URLSearchParams({ from, to, ...(professionalId ? { professionalId } : {}) })}`),
  changeStatus: (t: string, id: string, status: BookingStatus) =>
    api.patch<BookingView>(`${base(t)}/${encodeURIComponent(id)}`, { status }),
}
