import { describe, expect, it } from 'vitest'
import { centsToInput, formatCents, parseCents } from './money'

describe('dinheiro', () => {
  it('formata centavos em reais', () => {
    expect(formatCents(4500)).toBe('R$ 45,00')
    expect(formatCents(123456)).toBe('R$ 1.234,56')
  })

  it('lê o que a pessoa digita: vírgula ou ponto decimal, milhar com ponto, R$ opcional', () => {
    expect(parseCents('45')).toBe(4500)
    expect(parseCents('45,5')).toBe(4550)
    expect(parseCents('45,90')).toBe(4590)
    expect(parseCents('45.90')).toBe(4590)
    expect(parseCents(' R$ 1.234,56 ')).toBe(123456)
    expect(parseCents('0')).toBe(0)
    expect(parseCents('100000')).toBe(10_000_000)
    expect(parseCents('1.23')).toBe(123)
    // ponto com 3 dígitos depois é milhar (como se escreve no Brasil), com 1 ou 2 é decimal
    expect(parseCents('1.234')).toBe(123400)
    expect(parseCents('100.000,00')).toBe(10_000_000)
  })

  it('recusa o que não é valor: negativo, letras, três casas, vazio, acima de R$ 100 mil', () => {
    for (const bad of ['-5', 'abc', '4,555', '', '1.2345', '100000,01', '12,3,4', '1.000.001', '45,', ',50', '1.2.3']) expect(parseCents(bad)).toBeNull()
  })

  it('ida e volta: o que o formulário mostra é lido de novo como o mesmo valor', () => {
    for (const c of [0, 5, 1005, 123456, 10_000_000]) expect(parseCents(centsToInput(c))).toBe(c)
  })

  it('centavos para o campo de texto', () => {
    expect(centsToInput(4550)).toBe('45,50')
    expect(centsToInput(0)).toBe('0,00')
  })
})
