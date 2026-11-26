import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { messageOf } from './errors'
import { GENERIC_ERROR, PASSWORD_RULE, translate } from './messages'

describe('mensagens', () => {
  it('traduz as conhecidas; desconhecida ou vazia vira texto genérico (nada técnico na tela)', () => {
    expect(translate('Email already in use')).toBe('Este e-mail já tem cadastro.')
    expect(translate('org.postgresql.util.PSQLException')).toBe(GENERIC_ERROR)
    expect(translate(undefined)).toBe(GENERIC_ERROR)
  })

  it('mensagens da agenda têm tradução própria (chave errada cairia no texto genérico)', () => {
    for (const m of ['End must be after start', 'Overlapping working hours', 'Times must be multiples of 5 minutes',
      'Time off not found', 'Time off already ended', 'Time off longer than 92 days', 'Time off limit reached',
      'Date out of range', 'Professional does not perform this service', 'Booking not found', 'Service or professional inactive',
      'Invalid phone', 'Start in the past', 'Outside working hours', 'Time slot not available', 'Invalid status change',
      'Booking has not started', 'Booking limit reached', 'Phone registered with another name',
      'Time does not exist or repeats on this day', 'Busy, try again', 'Only your own agenda', 'Professional already has an account',
      'Invalid or expired invite', 'Already a member of this business', 'Professional is inactive',
      'Professional has no linked account', 'Business not found', 'Too many holds', 'Hold expired', 'Invalid code',
      'Booking expired', 'Too many attempts', 'Email unavailable, try again', 'Too late to change', 'Reschedule keeps the service',
      'Too many pending bookings, try again later', 'Reschedule limit reached', 'Customer has upcoming bookings', 'Customer not found']) {
      expect(translate(m), m).not.toBe(GENERIC_ERROR)
    }
  })

  it('erro da API mostra a mensagem dela; outro erro mostra o texto do contexto', () => {
    expect(messageOf(new ApiError(409, 'Já existe.'), 'padrão')).toBe('Já existe.')
    expect(messageOf(new Error('stack interno'), 'padrão')).toBe('padrão')
  })

  it('regra de senha igual à do backend: 8 a 72, com letra (inclusive acentuada) e número', () => {
    expect(PASSWORD_RULE.test('Cadeira77')).toBe(true)
    expect(PASSWORD_RULE.test('ção12345')).toBe(true)
    expect(PASSWORD_RULE.test('Curta7')).toBe(false)
    expect(PASSWORD_RULE.test('semnumeros')).toBe(false)
    expect(PASSWORD_RULE.test('12345678')).toBe(false)
    expect(PASSWORD_RULE.test('a1'.repeat(36))).toBe(true)
    expect(PASSWORD_RULE.test('a1'.repeat(36) + 'x')).toBe(false)
  })
})
