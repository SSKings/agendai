// Dinheiro em centavos (inteiro) na API; na tela, reais no formato brasileiro.

const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

export function formatCents(cents: number): string {
  return BRL.format(cents / 100)
}

/** "45", "45,5", "1.234,90" ou "45.90" → centavos; null se não for um valor válido (até R$ 100.000,00). */
export function parseCents(text: string): number | null {
  const t = text.trim().replace(/^R\$\s*/, '')
  const m = /^(\d{1,3}(?:\.\d{3})+|\d+)(?:[,.](\d{1,2}))?$/.exec(t)
  if (!m) return null
  const cents = Number(m[1].replaceAll('.', '')) * 100 + Number((m[2] ?? '0').padEnd(2, '0'))
  return cents <= 10_000_000 ? cents : null
}

/** Centavos → texto do campo ("45,00"). */
export function centsToInput(cents: number): string {
  return (cents / 100).toFixed(2).replace('.', ',')
}
