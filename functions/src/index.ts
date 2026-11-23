import { createHash } from 'node:crypto'
import { initializeApp } from 'firebase-admin/app'
import { FieldValue, Timestamp, getFirestore } from 'firebase-admin/firestore'
import { HttpsError, onCall, type CallableRequest } from 'firebase-functions/v2/https'
import { onDocumentUpdated } from 'firebase-functions/v2/firestore'

initializeApp()
const db = getFirestore()
const region = 'us-central1'
const allowedChannels = ['MOBILE_IN_SITU_QR', 'WEB_IN_SITU_QR', 'WHATSAPP_LINK'] as const

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

async function requireRole(uid: string, role: string) {
  const user = await db.doc(`users/${uid}`).get()
  if (!user.exists || user.get('role') !== role || user.get('isActive') !== true) {
    fail('permission-denied', 'Tu perfil no tiene permiso para esta acción.')
  }
  return user
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
    if (!id || ids.has(id) || !name || !Number.isSafeInteger(unitPrice) || Number(unitPrice) < 0) {
      fail('invalid-argument', 'Revisa el nombre y el precio de tus productos.')
    }
    ids.add(id)
    return { id, name, unitPrice: Number(unitPrice) }
  })
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
  const qrImageUrl = typeof data.qrImageUrl === 'string' ? data.qrImageUrl.slice(0, 2048) : ''
  if (!qrImageUrl) fail('invalid-argument', 'Sube el QR de tu billetera antes de publicar el enlace.')
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
      qrImageUrl,
      paymentKeyLabel: typeof data.paymentKeyLabel === 'string' ? data.paymentKeyLabel.slice(0, 100) : '',
      beneficiaryName: typeof data.beneficiaryName === 'string' ? data.beneficiaryName.slice(0, 100) : '',
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
  const qrImageUrl = typeof data.qrImageUrl === 'string' ? data.qrImageUrl.slice(0, 2048) : ''
  if (!qrImageUrl) fail('invalid-argument', 'El enlace debe tener un QR de pago.')
  const publicRef = db.doc(`public_payment_links/${cleanSlug((await linkRef.get()).get('slug'))}`)
  await db.runTransaction(async (transaction) => {
    const [link, publicLink] = await Promise.all([transaction.get(linkRef), transaction.get(publicRef)])
    if (!link.exists || link.get('merchantId') !== auth.uid || !publicLink.exists) {
      fail('not-found', 'No encontramos ese enlace en tu cuenta.')
    }
    const patch = {
      schemaJson: { products, captureCustomerInfo: Boolean((data.schemaJson as Record<string, unknown> | undefined)?.captureCustomerInfo) },
      qrImageUrl,
      paymentKeyLabel: typeof data.paymentKeyLabel === 'string' ? data.paymentKeyLabel.slice(0, 100) : '',
      beneficiaryName: typeof data.beneficiaryName === 'string' ? data.beneficiaryName.slice(0, 100) : '',
      updatedAt: FieldValue.serverTimestamp(),
    }
    transaction.update(linkRef, patch)
    transaction.update(publicRef, patch)
  })
  return { id }
})

export const createCheckoutOrder = onCall({ region, enforceAppCheck: false }, async (request) => {
  const data = request.data as Record<string, unknown>
  const linkId = typeof data.linkId === 'string' ? data.linkId : ''
  const linkRef = db.doc(`payment_links/${linkId}`)
  const linkSnap = await linkRef.get()
  if (!linkSnap.exists || linkSnap.get('isActive') !== true) fail('not-found', 'Este enlace ya no está disponible.')
  const link = linkSnap.data()!
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
    return { name: product.name, qty: Number(qty), unitPrice: product.unitPrice, subtotal: Number(qty) * product.unitPrice }
  }).filter((item) => item.qty > 0)
  if (!items.length) fail('invalid-argument', 'Selecciona al menos un producto.')
  const totalAmount = items.reduce((sum, item) => sum + item.subtotal, 0)
  if (!Number.isSafeInteger(totalAmount) || totalAmount <= 0) fail('invalid-argument', 'El total del pedido no es válido.')
  const channel = allowedChannels.includes(data.checkoutChannel as typeof allowedChannels[number])
    ? data.checkoutChannel as typeof allowedChannels[number]
    : link.defaultMode === 'IN_SITU_QR' ? 'WEB_IN_SITU_QR' : 'WHATSAPP_LINK'
  if (channel === 'WHATSAPP_LINK' && link.defaultMode !== 'WHATSAPP_LINK') fail('permission-denied', 'Este enlace no admite pedidos remotos.')
  let customerUid: string | null = null
  if (request.auth) {
    if (request.auth.token.firebase?.sign_in_provider !== 'google.com') fail('unauthenticated', 'Usa Google para guardar tus pedidos en tu cuenta.')
    const profile = await db.doc(`users/${request.auth.uid}`).get()
    if (profile.exists && profile.get('isActive') === true && profile.get('role') === 'USUARIO') customerUid = request.auth.uid
    if (profile.exists && profile.get('role') === 'COMERCIANTE' && profile.get('isActive') === true
      && link.merchantId !== request.auth.uid) {
      // A merchant may use their own QR for an in-person checkout.
      fail('permission-denied', 'No puedes crear pedidos en el enlace de otro comercio mientras actúas como comerciante.')
    }
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
      updatedAt: FieldValue.serverTimestamp(),
      expiresAt: Timestamp.fromMillis(Date.now() + 48 * 60 * 60 * 1000),
    })
  })
  return { orderId: orderRef.id, sessionId: sessionRef.id, orderCode, totalAmount }
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
