import { api } from './client'
import type { AuthResponse, Me, RegisterInput } from './types'

export const auth = {
  register: (input: RegisterInput) => api.post<AuthResponse>('/api/auth/register', input),
  login: (email: string, password: string) => api.post<AuthResponse>('/api/auth/login', { email, password }),
  logout: () => api.post<void>('/api/auth/logout'),
  me: () => api.get<Me>('/api/me'),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.put<AuthResponse>('/api/me/password', { currentPassword, newPassword }),
}
