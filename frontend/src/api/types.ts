import type { components } from './schema'

// Tipos da API derivados do contrato (docs/openapi.json → schema.d.ts, gerado por `npm run contract`).
// Não escreva tipos de resposta à mão: se o backend mudar um campo, o tsc aponta aqui e em quem usa.

type S = components['schemas']

export type AuthResponse = S['AuthResponse']
export type User = S['UserView']
export type Me = S['MeResponse']
export type Tenant = S['TenantView']
export type Role = Tenant['role']
export type RegisterInput = S['RegisterRequest']

export type ServiceView = S['ServiceView']
export type ServiceInput = S['ServiceRequest']
export type ProfessionalView = S['ProfessionalView']
export type ProfessionalInput = S['ProfessionalRequest']

export type WorkingInterval = S['WorkingInterval']
export type WorkingHoursView = S['WorkingHoursView']
export type TimeOffView = S['TimeOffView']
export type TimeOffInput = S['TimeOffRequest']
export type AvailabilityView = S['AvailabilityView']

export type BookingView = S['BookingView']
export type BookingInput = S['BookingRequest']
export type AgendaView = S['AgendaView']
export type BookingStatus = BookingView['status']
export type InviteView = S['InviteView']
export type AcceptedInvite = S['AcceptedView']
