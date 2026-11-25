import { api } from './client'
import type { AvailabilityView, TimeOffInput, TimeOffView, WorkingHoursView, WorkingInterval } from './types'

const base = (tenantId: string) => `/api/tenants/${encodeURIComponent(tenantId)}`
const hours = (t: string, professionalId: string) => `${base(t)}/professionals/${encodeURIComponent(professionalId)}/working-hours`

export const schedule = {
  workingHours: (t: string, professionalId: string) => api.get<WorkingHoursView>(hours(t, professionalId)),
  replaceWorkingHours: (t: string, professionalId: string, intervals: WorkingInterval[]) =>
    api.put<WorkingHoursView>(hours(t, professionalId), { intervals }),
  timeOff: (t: string, from: string, to: string) =>
    api.get<TimeOffView[]>(`${base(t)}/time-off?${new URLSearchParams({ from, to })}`),
  createTimeOff: (t: string, input: TimeOffInput) => api.post<TimeOffView>(`${base(t)}/time-off`, input),
  deleteTimeOff: (t: string, id: string) => api.del(`${base(t)}/time-off/${encodeURIComponent(id)}`),
  availability: (t: string, professionalId: string, serviceId: string, date: string) =>
    api.get<AvailabilityView>(`${base(t)}/availability?${new URLSearchParams({ professionalId, serviceId, date })}`),
}
