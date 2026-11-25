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
