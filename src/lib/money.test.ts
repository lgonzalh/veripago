import { describe, expect, it } from 'vitest'
import { calculateExpression, calculateOrderTotal, formatCOP } from './money'
import { normalizeCopAmount, parseNotification } from './bankParser'

describe('order calculator', () => {
  it('sums multiple products and quantities in whole Colombian pesos', () => {
    expect(calculateOrderTotal([{ unitPrice: 12500, qty: 2 }, { unitPrice: 8000, qty: 1 }])).toBe(33000)
  })

  it('allows zero quantities but rejects negative and fractional input', () => {
    expect(calculateOrderTotal([{ unitPrice: 15000, qty: 0 }])).toBe(0)
    expect(() => calculateOrderTotal([{ unitPrice: 1000, qty: -1 }])).toThrow('Cantidad inválida')
    expect(() => calculateOrderTotal([{ unitPrice: 1000, qty: 1.5 }])).toThrow('Cantidad inválida')
  })

  it('evaluates a restricted arithmetic grammar without JavaScript execution', () => {
    expect(calculateExpression('price * qty + 500', { price: 1000, qty: 3 })).toBe(3500)
    expect(calculateExpression(' (price + 2) * qty ', { price: 3, qty: 2 })).toBe(10)
    expect(() => calculateExpression('process.exit()', {})).toThrow()
    expect(() => calculateExpression('1 2', {})).toThrow()
    expect(() => calculateExpression('price / 0', { price: 1 })).toThrow()
  })

  it('formats whole COP amounts without decimals', () => {
    expect(formatCOP(15000)).toBe('$ 15.000')
    expect(formatCOP(15000)).not.toContain('COP')
  })
})

describe('bank notification parsing', () => {
  it.each([
    ['$15.000', 15000],
    ['$15,000', 15000],
    ['15.000,00', 15000],
    ['15,000.00', 15000],
    ['1.250.000', 1250000],
    ['$ 800', 800],
  ])('normalizes COP amount %s', (raw, expected) => {
    expect(normalizeCopAmount(raw)).toBe(expected)
  })

  it('extracts a Nequi transfer amount and sender', () => {
    const parsed = parseNotification(
      '(?i)(?:te\\s+)?(?:lleg(?:aron|ó|o)|recibiste|recib[ií]).*?\\$?\\s*([0-9][0-9.,]*)\\s*(?:COP|pesos)?',
      '(?i)(?:de|enviado\\s+por|remitente)\\s*[:\\-]?\\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑa-záéíóúñ ]{2,60})',
      '¡Listo! Recibiste $15.000 de Maria Perez',
    )
    expect(parsed).toEqual({ amount: 15000, sender: 'Maria Perez' })
  })

  it('extracts a Daviplata transfer with decimal-formatted thousands', () => {
    const parsed = parseNotification(
      '(?i)(?:abono|recibiste|transferencia\\s+recibida).*?\\$?\\s*([0-9][0-9.,]*)\\s*(?:COP|pesos)?',
      '(?i)(?:de|remitente|origen)\\s*[:\\-]?\\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑa-záéíóúñ ]{2,60})',
      'Transferencia recibida por $ 23.500,00 de Juan Carlos Rojas',
    )
    expect(parsed).toEqual({ amount: 23500, sender: 'Juan Carlos Rojas' })
  })

  it('recognizes the Nequi "te llegaron" notification wording', () => {
    const parsed = parseNotification(
      '(?i)(?:te\\s+)?(?:lleg(?:aron|ó|o)|recibiste|recib[ií]).*?\\$?\\s*([0-9][0-9.,]*)\\s*(?:COP|pesos)?',
      '(?i)(?:de|enviado\\s+por|remitente)\\s*[:\\-]?\\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑa-záéíóúñ ]{2,60})',
      'Te llegaron hace un momento $8.500 de Familia Gómez',
    )
    expect(parsed).toEqual({ amount: 8500, sender: 'Familia Gómez' })
  })
})
