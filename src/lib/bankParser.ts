function compile(pattern: string) {
  const insensitive = pattern.startsWith('(?i)')
  return new RegExp(insensitive ? pattern.slice(4) : pattern, insensitive ? 'i' : '')
}

export function normalizeCopAmount(raw: string): number | null {
  let value = raw.replace(/[^\d.,]/g, '')
  if (!value || !/\d/.test(value)) return null
  const lastDot = value.lastIndexOf('.')
  const lastComma = value.lastIndexOf(',')
  const decimalSeparator = Math.max(lastDot, lastComma)
  const hasBoth = lastDot >= 0 && lastComma >= 0
  if (hasBoth) {
    const decimals = value.length - decimalSeparator - 1
    value = decimals === 2
      ? value.slice(0, decimalSeparator).replace(/[.,]/g, '')
      : value.replace(/[.,]/g, '')
  } else if (decimalSeparator >= 0) {
    const decimals = value.length - decimalSeparator - 1
    if (decimals === 3) value = value.replace(/[.,]/g, '')
    else if (value[decimalSeparator] === ',') value = value.slice(0, decimalSeparator) + value.slice(decimalSeparator + 1)
    else value = value.slice(0, decimalSeparator)
  }
  const amount = Number(value)
  return Number.isSafeInteger(amount) && amount > 0 ? amount : null
}

export function parseNotification(amountPattern: string, senderPattern: string, text: string) {
  const amountMatch = compile(amountPattern).exec(text)
  if (!amountMatch?.[1]) return null
  const amount = normalizeCopAmount(amountMatch[1])
  if (amount === null) return null
  const sender = compile(senderPattern).exec(text)?.[1]?.trim().replace(/[.!?,;]+$/, '') ?? ''
  return { amount, sender }
}
