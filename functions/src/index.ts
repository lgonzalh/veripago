import { createHash } from 'node:crypto'
import { initializeApp } from 'firebase-admin/app'
import { FieldValue, Timestamp, getFirestore } from 'firebase-admin/firestore'
import { HttpsError, onCall, type CallableRequest } from 'firebase-functions/v2/https'
import { onDocumentUpdated } from 'firebase-functions/v2/firestore'

initializeApp()
const db = getFirestore()
const region = 'us-central1'
const allowedChannels = ['MOBILE_IN_SITU_QR', 'WEB_IN_SITU_QR', 'WHATSAPP_LINK'] as const
const allowedPaymentProviders = ['NEQUI', 'DAVIPLATA', 'BRE_B', 'OTHER'] as const

type PaymentProvider = typeof allowedPaymentProviders[number]
type PaymentMethod = { provider: PaymentProvider; qrImageUrl: string; paymentKeyLabel: string; beneficiaryName: string }
type AdjustmentRule = { enabled: boolean; mode: 'FIXED' | 'PERCENT'; value: number }
type MerchantAdjustments = { perProduct: AdjustmentRule; perOrder: AdjustmentRule }
const emptyAdjustments: MerchantAdjustments = {
  perProduct: { enabled: false, mode: 'FIXED', value: 0 },
  perOrder: { enabled: false, mode: 'FIXED', value: 0 },
}

function fail(code: ConstructorParameters<typeof HttpsError>[0], message: string): never {
  throw new HttpsError(code, message)
}

function requireGoogle(request: CallableRequest<unknown>) {
  const auth = request.auth
  if (!auth || auth.token.firebase?.sign_in_provider !== 'google.com') {
    fail('unauthenticated', 'Inicia sesión con Google para continuar.')
  }
  return auth
}

export const provisionGoogleProfile = onCall({ region, enforceAppCheck: false }, async (request) => {
  const auth = requireGoogle(request)
  const email = String(auth.token.email ?? '').trim().toLowerCase()
  if (!email || auth.token.email_verified !== true) fail('unauthenticated', 'Verifica tu cuenta de Google para registrarte.')
  const profileRef = db.doc(`users/${auth.uid}`)
  const displayName = String(auth.token.name ?? 'Comerciante VeriPago').trim().slice(0, 100)
  const photoUrl = String(auth.token.picture ?? '').slice(0, 2048)

  const profile = await db.runTransaction(async (transaction) => {
    const existing = await transaction.get(profileRef)
    if (existing.exists) {
      const data = existing.data()!
      if (data.isActive === false) fail('permission-denied', 'Tu cuenta VeriPago está desactivada.')
      if (data.role !== 'COMERCIANTE' && data.role !== 'ADMINISTRADOR') {
        fail('permission-denied', 'Los compradores no inician sesión. Usa el enlace o el QR del comercio.')
      }
      transaction.update(profileRef, {
        lastLoginAt: FieldValue.serverTimestamp(),
      })
      return {
        uid: auth.uid,
        email: data.email ?? email,
        displayName: data.displayName ?? displayName,
        photoUrl: data.photoUrl ?? photoUrl,
        role: data.role,
        isActive: true,
        quotaMaxLinks: data.quotaMaxLinks ?? 10,
        loyaltyPoints: data.loyaltyPoints ?? 0,
      }
    }

    const newProfile = {
      uid: auth.uid,
      email,
      displayName,
      photoUrl,
      role: 'COMERCIANTE',
      isActive: true,
      quotaMaxLinks: 10,
      loyaltyPoints: 0,
      createdAt: FieldValue.serverTimestamp(),
      lastLoginAt: FieldValue.serverTimestamp(),
    }
    transaction.create(profileRef, newProfile)
    return {
      uid: newProfile.uid,
      email: newProfile.email,
      displayName: newProfile.displayName,
      photoUrl: newProfile.photoUrl,
      role: newProfile.role,
      isActive: newProfile.isActive,
      quotaMaxLinks: newProfile.quotaMaxLinks,
      loyaltyPoints: newProfile.loyaltyPoints,
    }
  })
  return { profile }
})

async function requireRole(uid: string, role: string) {
  const user = await db.doc(`users/${uid}`).get()
  if (!user.exists || user.get('role') !== role || user.get('isActive') !== true) {
    fail('permission-denied', 'Tu perfil no tiene permiso para esta acción.')
  }
  return user
}

function normalizeAdjustmentRule(value: unknown): AdjustmentRule {
  const data = value && typeof value === 'object' ? value as Record<string, unknown> : {}
  const mode = data.mode === 'PERCENT' ? 'PERCENT' : 'FIXED'
  const amount = typeof data.value === 'number' && Number.isFinite(data.value) ? data.value : 0
  if (mode === 'PERCENT' && (amount < 0 || amount > 100)) fail('invalid-argument', 'El porcentaje debe estar entre 0 y 100.')
  if (mode === 'FIXED' && (!Number.isSafeInteger(amount) || amount < 0 || amount > 1_000_000_000)) {
    fail('invalid-argument', 'El valor fijo debe ser COP entero hasta 1.000.000.000.')
  }
  return { enabled: data.enabled === true, mode, value: amount }
}

function normalizeMerchantAdjustments(value: unknown): MerchantAdjustments {
  const data = value && typeof value === 'object' ? value as Record<string, unknown> : {}
  return {
    perProduct: normalizeAdjustmentRule(data.perProduct),
    perOrder: normalizeAdjustmentRule(data.perOrder),
  }
}

async function loadMerchantAdjustments(uid: string): Promise<MerchantAdjustments> {
  const snapshot = await db.doc(`users/${uid}/settings/fees`).get()
  return snapshot.exists ? normalizeMerchantAdjustments(snapshot.data()) : emptyAdjustments
}

function calculateAdjustmentAmount(base: number, rule: AdjustmentRule) {
  if (!rule.enabled || base <= 0) return 0
  return rule.mode === 'PERCENT' ? Math.round(base * rule.value / 100) : rule.value
}

function cleanSlug(value: unknown) {
  if (typeof value !== 'string') fail('invalid-argument', 'El enlace necesita un nombre válido.')
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 42)
}

function validateProducts(value: unknown) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 50) {
    fail('invalid-argument', 'Agrega entre 1 y 50 productos al enlace.')
  }
  const ids = new Set<string>()
  return value.map((candidate: unknown) => {
    if (!candidate || typeof candidate !== 'object') fail('invalid-argument', 'Hay un producto inválido.')
    const product = candidate as Record<string, unknown>
    const id = typeof product.id === 'string' ? product.id : ''
    const name = typeof product.name === 'string' ? product.name.trim().slice(0, 100) : ''
    const unitPrice = product.unitPrice
    const defaultQty = product.defaultQty
    if (!id || ids.has(id) || !name || !Number.isSafeInteger(unitPrice) || Number(unitPrice) < 0
      || (defaultQty !== undefined && (!Number.isSafeInteger(defaultQty) || Number(defaultQty) < 1 || Number(defaultQty) > 100))) {
      fail('invalid-argument', 'Revisa el nombre y el precio de tus productos.')
    }
    ids.add(id)
    return { id, name, unitPrice: Number(unitPrice), ...(defaultQty !== undefined ? { defaultQty: Number(defaultQty) } : {}) }
  })
}

function normalizePaymentMethods(value: unknown, legacy: Record<string, unknown>): PaymentMethod[] {
  const candidates = Array.isArray(value) ? value : [legacy]
  if (candidates.length > 3) fail('invalid-argument', 'Configura máximo tres medios de pago.')
  const seen = new Set<string>()
  const methods = candidates.map((candidate: unknown) => {
    if (!candidate || typeof candidate !== 'object') fail('invalid-argument', 'Hay un medio de pago inválido.')
    const method = candidate as Record<string, unknown>
    const providerValue = method.provider ?? legacy.paymentProvider
    if (providerValue !== undefined && !allowedPaymentProviders.includes(providerValue as PaymentProvider)) {
      fail('invalid-argument', 'Selecciona un medio de pago válido.')
    }
    const provider = allowedPaymentProviders.includes(providerValue as PaymentProvider)
      ? providerValue as PaymentProvider
      : 'OTHER'
    if (seen.has(provider)) fail('invalid-argument', 'No repitas el mismo medio de pago.')
    seen.add(provider)
    const qrImageUrl = typeof (method.qrImageUrl ?? legacy.qrImageUrl) === 'string'
      ? String(method.qrImageUrl ?? legacy.qrImageUrl).trim().slice(0, 2048)
      : ''
    if (!qrImageUrl) fail('invalid-argument', `Falta el QR de ${provider}.`)
    return {
      provider,
      qrImageUrl,
      paymentKeyLabel: typeof (method.paymentKeyLabel ?? legacy.paymentKeyLabel) === 'string'
        ? String(method.paymentKeyLabel ?? legacy.paymentKeyLabel).trim().slice(0, 100)
        : '',
      beneficiaryName: typeof (method.beneficiaryName ?? legacy.beneficiaryName) === 'string'
        ? String(method.beneficiaryName ?? legacy.beneficiaryName).trim().slice(0, 100)
        : '',
    }
  })
  if (methods.length < 1) fail('invalid-argument', 'Agrega al menos un medio de pago.')
  return methods
}

function paymentMethodFields(methods: PaymentMethod[]) {
  const primary = methods[0]
  return {
    paymentMethods: methods,
    paymentProvider: primary.provider,
    qrImageUrl: primary.qrImageUrl,
    paymentKeyLabel: primary.paymentKeyLabel,
    beneficiaryName: primary.beneficiaryName,
  }
}

function selectPaymentMethod(link: Record<string, unknown>, requestedProvider: unknown): PaymentMethod {
  const methods = normalizePaymentMethods(link.paymentMethods, link)
  if (typeof requestedProvider === 'string' && requestedProvider) {
    const selected = methods.find((method) => method.provider === requestedProvider)
    if (!selected) fail('invalid-argument', 'El comercio no ofrece ese medio de pago.')
    return selected
  }
  return methods[0]
}

export const createPaymentLink = onCall({ region, enforceAppCheck: false }, async (request) => {
  const auth = requireGoogle(request)
  await requireRole(auth.uid, 'COMERCIANTE')
  const data = request.data as Record<string, unknown>
  const slug = cleanSlug(data.slug)
  if (!slug) fail('invalid-argument', 'Escribe un nombre para tu enlace.')
  const title = typeof data.title === 'string' ? data.title.trim().slice(0, 100) : ''
  if (!title) fail('invalid-argument', 'El enlace necesita un título.')
  if (!['IN_SITU_QR', 'WHATSAPP_LINK'].includes(String(data.defaultMode))) {
    fail('invalid-argument', 'Selecciona una modalidad de cobro válida.')
  }
  const products = validateProducts((data.schemaJson as Record<string, unknown> | undefined)?.products)
  const paymentMethods = normalizePaymentMethods(data.paymentMethods, data)
  const [contact, adjustments] = await Promise.all([
    db.doc(`users/${auth.uid}/settings/contact`).get(),
    loadMerchantAdjustments(auth.uid),
  ])
  const merchantWhatsapp = typeof contact.get('whatsappNumber') === 'string' ? contact.get('whatsappNumber') as string : ''
  if (merchantWhatsapp && !/^\+[1-9]\d{7,14}$/.test(merchantWhatsapp)) {
    fail('failed-precondition', 'Actualiza el WhatsApp del comercio en tu perfil antes de crear el enlace.')
  }
  const linkRef = db.collection('payment_links').doc()
  const slugRef = db.doc(`slug_reservations/${slug}`)
  const publicLinkRef = db.doc(`public_payment_links/${slug}`)
  const quota = Number((await db.doc(`users/${auth.uid}`).get()).get('quotaMaxLinks') ?? 10)
  await db.runTransaction(async (transaction) => {
    const [reservation, ownedLinks] = await Promise.all([
      transaction.get(slugRef),
      transaction.get(db.collection('payment_links').where('merchantId', '==', auth.uid)),
    ])
    if (reservation.exists) fail('already-exists', 'Ese enlace ya está ocupado. Prueba con otro nombre.')
    if (ownedLinks.size >= quota) fail('resource-exhausted', 'Llegaste al límite de enlaces de tu cuenta.')
    const linkData = {
      merchantId: auth.uid,
      slug,
      title,
      defaultMode: data.defaultMode,
      schemaJson: { products, captureCustomerInfo: Boolean((data.schemaJson as Record<string, unknown> | undefined)?.captureCustomerInfo) },
      ...paymentMethodFields(paymentMethods),
      adjustments,
      ...(merchantWhatsapp ? { merchantWhatsapp } : {}),
      reconciliationWindowMinutes: 20,
      isActive: true,
      createdAt: FieldValue.serverTimestamp(),
    }
    transaction.create(slugRef, { linkId: linkRef.id, merchantId: auth.uid, createdAt: FieldValue.serverTimestamp() })
    transaction.create(linkRef, linkData)
    transaction.create(publicLinkRef, { ...linkData, linkId: linkRef.id })
  })
  return { id: linkRef.id, slug }
})

export const updatePaymentLink = onCall({ region, enforceAppCheck: false }, async (request) => {
  const auth = requireGoogle(request)
  await requireRole(auth.uid, 'COMERCIANTE')
  const data = request.data as Record<string, unknown>
  const id = typeof data.id === 'string' ? data.id : ''
  const linkRef = db.doc(`payment_links/${id}`)
  const products = validateProducts((data.schemaJson as Record<string, unknown> | undefined)?.products)
  const current = await linkRef.get()
  if (!current.exists || current.get('merchantId') !== auth.uid) fail('not-found', 'No encontramos ese enlace en tu cuenta.')
  const publicRef = db.doc(`public_payment_links/${cleanSlug(current.get('slug'))}`)
  const paymentMethods = normalizePaymentMethods(data.paymentMethods, {
    ...current.data(),
    ...data,
  })
  const [contact, adjustments] = await Promise.all([
    db.doc(`users/${auth.uid}/settings/contact`).get(),
    loadMerchantAdjustments(auth.uid),
  ])
  const merchantWhatsapp = typeof contact.get('whatsappNumber') === 'string' ? contact.get('whatsappNumber') as string : ''
  if (merchantWhatsapp && !/^\+[1-9]\d{7,14}$/.test(merchantWhatsapp)) {
    fail('failed-precondition', 'Actualiza el WhatsApp del comercio en tu perfil antes de actualizar el enlace.')
  }
  await db.runTransaction(async (transaction) => {
    const [link, publicLink] = await Promise.all([transaction.get(linkRef), transaction.get(publicRef)])
    if (!link.exists || link.get('merchantId') !== auth.uid || !publicLink.exists) {
      fail('not-found', 'No encontramos ese enlace en tu cuenta.')
    }
    const patch = {
      schemaJson: { products, captureCustomerInfo: Boolean((data.schemaJson as Record<string, unknown> | undefined)?.captureCustomerInfo) },
      ...paymentMethodFields(paymentMethods),
      adjustments,
      merchantWhatsapp: merchantWhatsapp || FieldValue.delete(),
      updatedAt: FieldValue.serverTimestamp(),
    }
    transaction.update(linkRef, patch)
    transaction.update(publicRef, patch)
  })
  return { id }
})

export const saveMerchantAdjustments = onCall({ region, enforceAppCheck: false }, async (request) => {
  const auth = requireGoogle(request)
  await requireRole(auth.uid, 'COMERCIANTE')
  const adjustments = normalizeMerchantAdjustments(request.data)
  const links = await db.collection('payment_links').where('merchantId', '==', auth.uid).get()
  const batch = db.batch()
  batch.set(db.doc(`users/${auth.uid}/settings/fees`), {
    ...adjustments,
    updatedAt: FieldValue.serverTimestamp(),
  }, { merge: true })
  links.docs.forEach((link) => {
    batch.update(link.ref, { adjustments, updatedAt: FieldValue.serverTimestamp() })
    const slug = cleanSlug(link.get('slug'))
    if (slug) batch.set(db.doc(`public_payment_links/${slug}`), { adjustments }, { merge: true })
  })
  await batch.commit()
  return { saved: true, updatedLinks: links.size }
})

export const createCheckoutOrder = onCall({ region, enforceAppCheck: false }, async (request) => {
  const data = request.data as Record<string, unknown>
  const linkId = typeof data.linkId === 'string' ? data.linkId : ''
  const linkRef = db.doc(`payment_links/${linkId}`)
  const linkSnap = await linkRef.get()
  if (!linkSnap.exists || linkSnap.get('isActive') !== true) fail('not-found', 'Este enlace ya no está disponible.')
  const link = linkSnap.data()!
  let selectedMethod = selectPaymentMethod(link, data.paymentProvider)
  const adjustments = normalizeMerchantAdjustments(link.adjustments)
  const rawItems = data.items
  if (!Array.isArray(rawItems) || rawItems.length < 1 || rawItems.length > 50) fail('invalid-argument', 'El pedido no tiene productos válidos.')
  const catalog = new Map<string, { name: string; unitPrice: number }>(
    ((link.schemaJson?.products ?? []) as Array<{ id: string; name: string; unitPrice: number }>).map((product) => [product.id, product]),
  )
  const items = rawItems.map((raw: unknown) => {
    if (!raw || typeof raw !== 'object') fail('invalid-argument', 'Hay un producto inválido en el pedido.')
    const selection = raw as Record<string, unknown>
    const product = catalog.get(String(selection.productId ?? ''))
    const qty = selection.qty
    if (!product || !Number.isSafeInteger(qty) || Number(qty) < 0 || Number(qty) > 100) {
      fail('invalid-argument', 'Revisa las cantidades del pedido.')
    }
    const chargedUnitPrice = product.unitPrice + calculateAdjustmentAmount(product.unitPrice, adjustments.perProduct)
    return { name: product.name, qty: Number(qty), unitPrice: chargedUnitPrice, subtotal: Number(qty) * chargedUnitPrice }
  }).filter((item) => item.qty > 0)
  if (!items.length) fail('invalid-argument', 'Selecciona al menos un producto.')
  const subtotal = items.reduce((sum, item) => sum + item.subtotal, 0)
  const totalAmount = subtotal + calculateAdjustmentAmount(subtotal, adjustments.perOrder)
  if (!Number.isSafeInteger(totalAmount) || totalAmount <= 0) fail('invalid-argument', 'El total del pedido no es válido.')
  const channel = allowedChannels.includes(data.checkoutChannel as typeof allowedChannels[number])
    ? data.checkoutChannel as typeof allowedChannels[number]
    : link.defaultMode === 'IN_SITU_QR' ? 'WEB_IN_SITU_QR' : 'WHATSAPP_LINK'
  if (channel === 'WHATSAPP_LINK' && link.defaultMode !== 'WHATSAPP_LINK') fail('permission-denied', 'Este enlace no admite pedidos remotos.')
  const customerUid: string | null = null
  if (request.auth) {
    if (request.auth.token.firebase?.sign_in_provider !== 'google.com') fail('unauthenticated', 'Usa Google para continuar.')
    const profile = await db.doc(`users/${request.auth.uid}`).get()
    if (channel !== 'WHATSAPP_LINK' && (!profile.exists || profile.get('role') !== 'COMERCIANTE' || profile.get('isActive') !== true || link.merchantId !== request.auth.uid)) {
      fail('permission-denied', 'Solo el comercio dueño puede crear cobros en mostrador.')
    }
  } else if (channel !== 'WHATSAPP_LINK') {
    fail('permission-denied', 'El cobro en mostrador requiere la cuenta del comercio.')
  }
  const customerName = typeof data.customerName === 'string' ? data.customerName.trim().slice(0, 100) : ''
  const customerPhone = typeof data.customerPhone === 'string' ? data.customerPhone.trim().slice(0, 40) : ''
  if (channel === 'WHATSAPP_LINK' && (!customerName || customerPhone.replace(/\D/g, '').length < 7)) {
    fail('invalid-argument', 'Ingresa tu nombre y un teléfono válido para continuar.')
  }

  const orderRef = db.collection('orders').doc()
  const sessionRef = db.collection('checkout_sessions').doc()
  const orderCode = `VP-${orderRef.id.slice(-5).toUpperCase()}`
  const now = Timestamp.now()
  const requester = request.auth?.uid ?? request.rawRequest.ip ?? 'unknown-public-client'
  const rateBucket = Math.floor(Date.now() / (60 * 60 * 1000))
  const rateId = createHash('sha256').update(`${requester}:${rateBucket}`).digest('hex')
  const rateRef = db.doc(`checkout_rate_limits/${rateId}`)
  await db.runTransaction(async (transaction) => {
    const [currentLink, rateLimit] = await Promise.all([transaction.get(linkRef), transaction.get(rateRef)])
    if (!currentLink.exists || currentLink.get('isActive') !== true) fail('not-found', 'Este enlace ya no está disponible.')
    selectedMethod = selectPaymentMethod(currentLink.data() as Record<string, unknown>, data.paymentProvider)
    if ((rateLimit.get('count') ?? 0) >= 25) fail('resource-exhausted', 'Se alcanzó el límite temporal de pedidos. Inténtalo más tarde.')
    transaction.set(rateRef, {
      count: Number(rateLimit.get('count') ?? 0) + 1,
      expiresAt: Timestamp.fromMillis(Date.now() + 2 * 60 * 60 * 1000),
    })
    transaction.create(orderRef, {
      linkId,
      merchantId: link.merchantId,
      orderCode,
      customerUid,
      customerName,
      customerPhone,
      senderAccountName: null,
      items,
      totalAmount,
      checkoutChannel: channel,
      paymentProvider: selectedMethod.provider,
      paymentStatus: 'PENDING',
      deliveryStatus: 'PENDING_DELIVERY',
      matchedNotificationId: null,
      createdAt: now,
      localUpdatedAt: now,
      serverUpdatedAt: FieldValue.serverTimestamp(),
    })
    transaction.create(sessionRef, {
      orderId: orderRef.id,
      totalAmount,
      paymentStatus: 'PENDING',
      deliveryStatus: 'PENDING_DELIVERY',
      paymentProvider: selectedMethod.provider,
      updatedAt: FieldValue.serverTimestamp(),
      expiresAt: Timestamp.fromMillis(Date.now() + 48 * 60 * 60 * 1000),
    })
  })
  return { orderId: orderRef.id, sessionId: sessionRef.id, orderCode, totalAmount, paymentProvider: selectedMethod.provider }
})

export const mirrorCheckoutStatus = onDocumentUpdated({ document: 'orders/{orderId}', region }, async (event) => {
  const before = event.data?.before.data()
  const after = event.data?.after.data()
  if (!before || !after || (before.paymentStatus === after.paymentStatus && before.deliveryStatus === after.deliveryStatus)) return
  const sessions = await db.collection('checkout_sessions').where('orderId', '==', event.params.orderId).limit(1).get()
  if (sessions.empty) return
  await sessions.docs[0].ref.update({
    paymentStatus: after.paymentStatus,
    deliveryStatus: after.deliveryStatus,
    updatedAt: FieldValue.serverTimestamp(),
  })
})
