export function calculateExpression(expression: string, variables: Record<string, number>): number {
  if (!expression.trim() || expression.length > 120) throw new Error('Expresión vacía o demasiado larga')
  const source = expression.trim()
  const tokens: string[] = []
  const tokenPattern = /\s*(\d+(?:\.\d+)?|[A-Za-z_][A-Za-z0-9_]*|[()+\-*/%])/gy
  let offset = 0
  while (offset < source.length) {
    tokenPattern.lastIndex = offset
    const match = tokenPattern.exec(source)
    if (!match) throw new Error('La expresión contiene caracteres no permitidos')
    tokens.push(match[1])
    offset = tokenPattern.lastIndex
  }
  let position = 0
  function primary(): number {
    const token = tokens[position++]
    if (token === undefined) throw new Error('Expresión incompleta')
    if (token === '(') {
      const value = sum()
      if (tokens[position++] !== ')') throw new Error('Falta cerrar el paréntesis')
      return value
    }
    if (token === '+' || token === '-') {
      const value = primary()
      return token === '-' ? -value : value
    }
    if (/^\d/.test(token)) return Number(token)
    if (!Object.hasOwn(variables, token) || !Number.isFinite(variables[token])) throw new Error(`Variable no permitida: ${token}`)
    return variables[token]
  }
  function product(): number {
    let value = primary()
    while (['*', '/', '%'].includes(tokens[position] ?? '')) {
      const operator = tokens[position++]
      const next = primary()
      if ((operator === '/' || operator === '%') && next === 0) throw new Error('No se puede dividir por cero')
      value = operator === '*' ? value * next : operator === '/' ? value / next : value % next
    }
    return value
  }
  function sum(): number {
    let value = product()
    while (['+', '-'].includes(tokens[position] ?? '')) {
      const operator = tokens[position++]
      const next = product()
      value = operator === '+' ? value + next : value - next
    }
    return value
  }
  const value = sum()
  if (position !== tokens.length) throw new Error('La expresión contiene elementos adicionales')
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error('El cálculo debe producir un valor positivo válido')
  }
  return Math.round(value)
}

export function calculateOrderTotal(items: Array<{ unitPrice: number; qty: number }>): number {
  return items.reduce((sum, item) => {
    if (!Number.isSafeInteger(item.unitPrice) || item.unitPrice < 0) throw new Error('Precio inválido')
    if (!Number.isSafeInteger(item.qty) || item.qty < 0) throw new Error('Cantidad inválida')
    return sum + calculateExpression('price * qty', { price: item.unitPrice, qty: item.qty })
  }, 0)
}

export const formatCOP = (amount: number) =>
  `$ ${new Intl.NumberFormat('es-CO', { maximumFractionDigits: 0 }).format(amount)}`

export const slugify = (value: string) =>
  value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 42)
