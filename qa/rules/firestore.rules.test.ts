import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  it,
} from 'vitest'
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing'
import {
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  Timestamp,
  writeBatch,
} from 'firebase/firestore'
import { getBytes, ref, uploadBytes } from 'firebase/storage'

const projectId = 'demo-veripago'
let testEnv: RulesTestEnvironment

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId,
    firestore: {
      host: '127.0.0.1',
      port: 8080,
      rules: readFileSync(resolve('firestore.rules'), 'utf8'),
    },
    storage: {
      host: '127.0.0.1',
      port: 9199,
      rules: readFileSync(resolve('storage.rules'), 'utf8'),
    },
  })
})

beforeEach(async () => {
  await testEnv.clearFirestore()
  await testEnv.clearStorage()
})
afterEach(async () => {
  await testEnv.clearFirestore()
  await testEnv.clearStorage()
})
afterAll(async () => testEnv.cleanup())

describe('Firestore security rules', () => {
  it('allows an exact active public link lookup but denies catalog listing', async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), 'public_payment_links', 'cafe-central'), { isActive: true })
    })

    const guest = testEnv.unauthenticatedContext().firestore()
    await assertSucceeds(getDoc(doc(guest, 'public_payment_links', 'cafe-central')))
    await assertFails(getDocs(collection(guest, 'public_payment_links')))
  })

  it('allows first sign-in to create a merchant profile and prevents self-assigned admin roles', async () => {
    const merchantDb = googleUser('new-merchant', 'merchant@example.test')
    await assertSucceeds(setDoc(
      doc(merchantDb, 'users', 'new-merchant'),
      newProfile('new-merchant', 'merchant@example.test', 'COMERCIANTE'),
    ))

    const adminDb = googleUser('bootstrap-admin', 'lantonium@gmail.com')
    await assertFails(setDoc(
      doc(adminDb, 'users', 'bootstrap-admin'),
      newProfile('bootstrap-admin', 'lantonium@gmail.com', 'ADMINISTRADOR'),
    ))

    const impostorDb = googleUser('self-admin', 'impostor@example.test')
    await assertFails(setDoc(
      doc(impostorDb, 'users', 'self-admin'),
      newProfile('self-admin', 'impostor@example.test', 'ADMINISTRADOR'),
    ))
    await assertFails(setDoc(
      doc(merchantDb, 'users', 'someone-else'),
      newProfile('someone-else', 'merchant@example.test', 'COMERCIANTE'),
    ))
  })

  it('allows merchants to create their own pending orders only', async () => {
    await seedUser('merchant-1', 'COMERCIANTE')
    const db = googleUser('merchant-1')
    const order = {
      merchantId: 'merchant-1',
      paymentStatus: 'PENDING',
      deliveryStatus: 'PENDING_DELIVERY',
      totalAmount: 25_000,
    }

    await assertSucceeds(setDoc(doc(db, 'orders', 'own-order'), order))
    await assertFails(setDoc(doc(db, 'orders', 'other-order'), { ...order, merchantId: 'merchant-2' }))
    await assertFails(setDoc(doc(db, 'orders', 'paid-order'), { ...order, paymentStatus: 'VERIFIED_AUTO' }))
  })

  it('keeps each merchant product catalog private and validates stored VAT prices', async () => {
    await seedUser('merchant-catalog-1', 'COMERCIANTE')
    await seedUser('merchant-catalog-2', 'COMERCIANTE')
    const product = {
      name: 'Café',
      enteredPrice: 100_000,
      vatRate: 19,
      priceIncludesVat: false,
      priceWithoutVat: 100_000,
      vatAmount: 19_000,
      priceWithVat: 119_000,
    }
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), 'users', 'merchant-catalog-2', 'products', 'private-item'), product)
    })

    const merchant = googleUser('merchant-catalog-1')
    const ownCatalog = collection(merchant, 'users', 'merchant-catalog-1', 'products')
    await assertSucceeds(setDoc(doc(ownCatalog, 'coffee'), product))
    await assertSucceeds(getDoc(doc(ownCatalog, 'coffee')))
    await assertSucceeds(getDocs(ownCatalog))
    await assertFails(getDoc(doc(merchant, 'users', 'merchant-catalog-2', 'products', 'private-item')))
    await assertFails(getDocs(collection(merchant, 'users', 'merchant-catalog-2', 'products')))
    await assertFails(setDoc(doc(merchant, 'users', 'merchant-catalog-2', 'products', 'stolen-item'), product))
    await assertFails(setDoc(doc(ownCatalog, 'invalid-vat'), { ...product, priceWithVat: 100_000 }))
  })

  it('keeps the merchant WhatsApp contact private and writable only by its owner', async () => {
    await seedUser('merchant-contact-1', 'COMERCIANTE')
    await seedUser('merchant-contact-2', 'COMERCIANTE')
    const merchant = googleUser('merchant-contact-1')
    const contact = doc(merchant, 'users', 'merchant-contact-1', 'settings', 'contact')
    await assertSucceeds(setDoc(contact, { whatsappNumber: '+573001234567', updatedAt: serverTimestamp() }))
    await assertSucceeds(getDoc(contact))
    await assertFails(getDoc(doc(merchant, 'users', 'merchant-contact-2', 'settings', 'contact')))
    await assertFails(setDoc(doc(merchant, 'users', 'merchant-contact-2', 'settings', 'contact'), {
      whatsappNumber: '+573001234567',
      updatedAt: serverTimestamp(),
    }))
  })

  it('saves QR images privately for the owner and exposes only their checkout preview', async () => {
    await seedUser('merchant-qr-1', 'COMERCIANTE')
    await seedUser('merchant-qr-2', 'COMERCIANTE')
    const merchant = googleUser('merchant-qr-1')
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore()
      const link = {
        merchantId: 'merchant-qr-1',
        slug: 'qr-link',
        defaultMode: 'IN_SITU_QR',
        isActive: true,
        paymentMethods: [{ provider: 'OTHER', qrImageUrl: 'https://example.test/old.png', paymentKeyLabel: '', beneficiaryName: '' }],
      }
      await setDoc(doc(db, 'payment_links', 'qr-link'), link)
      await setDoc(doc(db, 'public_payment_links', 'qr-link'), { ...link, linkId: 'qr-link' })
    })
    const qr = {
      provider: 'NEQUI',
      contentType: 'image/png',
      imageBase64: 'iVBORw0KGgo=',
      paymentKeyLabel: 'Mi cuenta',
      beneficiaryName: 'Comercio',
      updatedAt: serverTimestamp(),
    }

    const batch = writeBatch(merchant)
    const paymentMethod = { provider: 'NEQUI', qrImageUrl: 'veripagoqr://merchant-qr-1/NEQUI', paymentKeyLabel: 'Mi cuenta', beneficiaryName: 'Comercio' }
    batch.set(doc(merchant, 'users', 'merchant-qr-1', 'wallet_qrs', 'NEQUI'), qr)
    batch.set(doc(merchant, 'public_merchant_qrs', 'merchant-qr-1', 'wallets', 'NEQUI'), qr)
    const linkUpdate = {
      paymentMethods: [paymentMethod],
      paymentProvider: 'NEQUI',
      qrImageUrl: paymentMethod.qrImageUrl,
      paymentKeyLabel: paymentMethod.paymentKeyLabel,
      beneficiaryName: paymentMethod.beneficiaryName,
      updatedAt: serverTimestamp(),
    }
    batch.update(doc(merchant, 'payment_links', 'qr-link'), linkUpdate)
    batch.update(doc(merchant, 'public_payment_links', 'qr-link'), linkUpdate)
    await assertSucceeds(batch.commit())
    await assertFails(getDoc(doc(merchant, 'users', 'merchant-qr-2', 'wallet_qrs', 'NEQUI')))
    await assertFails(setDoc(doc(merchant, 'users', 'merchant-qr-2', 'wallet_qrs', 'NEQUI'), qr))
    await assertFails(setDoc(doc(merchant, 'users', 'merchant-qr-1', 'wallet_qrs', 'UNKNOWN'), { ...qr, provider: 'UNKNOWN' }))
    await assertFails(setDoc(doc(merchant, 'users', 'merchant-qr-1', 'wallet_qrs', 'DAVIPLATA'), {
      ...qr,
      provider: 'DAVIPLATA',
      imageBase64: 'x'.repeat(750_001),
    }))

    const guest = testEnv.unauthenticatedContext().firestore()
    await assertSucceeds(getDoc(doc(guest, 'public_merchant_qrs', 'merchant-qr-1', 'wallets', 'NEQUI')))
    await assertFails(getDocs(collection(guest, 'public_merchant_qrs', 'merchant-qr-1', 'wallets')))
    await assertFails(getDoc(doc(guest, 'users', 'merchant-qr-1', 'wallet_qrs', 'NEQUI')))
  })

  it('allows a merchant to save valid adjustments only to its own settings and payment links', async () => {
    await seedUser('merchant-fees-1', 'COMERCIANTE')
    await seedUser('merchant-fees-2', 'COMERCIANTE')
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore()
      const link = { merchantId: 'merchant-fees-1', slug: 'fees-link', title: 'Pedido', adjustments: { perProduct: { enabled: false, mode: 'FIXED', value: 0 }, perOrder: { enabled: false, mode: 'FIXED', value: 0 } } }
      const otherLink = { ...link, merchantId: 'merchant-fees-2', slug: 'other-fees-link' }
      await setDoc(doc(db, 'payment_links', 'fees-link'), link)
      await setDoc(doc(db, 'public_payment_links', 'fees-link'), { ...link, linkId: 'fees-link', isActive: true })
      await setDoc(doc(db, 'payment_links', 'other-fees-link'), otherLink)
      await setDoc(doc(db, 'public_payment_links', 'other-fees-link'), { ...otherLink, linkId: 'other-fees-link', isActive: true })
    })

    const merchant = googleUser('merchant-fees-1')
    const adjustments = {
      perProduct: { enabled: true, mode: 'PERCENT', value: 5 },
      perOrder: { enabled: true, mode: 'FIXED', value: 500 },
    }
    const feeSettings = doc(merchant, 'users', 'merchant-fees-1', 'settings', 'fees')
    await assertSucceeds(setDoc(feeSettings, { ...adjustments, updatedAt: serverTimestamp() }))
    await assertFails(setDoc(doc(merchant, 'users', 'merchant-fees-2', 'settings', 'fees'), { ...adjustments, updatedAt: serverTimestamp() }))
    await assertSucceeds(updateDoc(doc(merchant, 'payment_links', 'fees-link'), { adjustments, updatedAt: serverTimestamp() }))
    await assertSucceeds(updateDoc(doc(merchant, 'public_payment_links', 'fees-link'), { adjustments, updatedAt: serverTimestamp() }))
    await assertFails(updateDoc(doc(merchant, 'payment_links', 'other-fees-link'), { adjustments, updatedAt: serverTimestamp() }))
    await assertFails(updateDoc(doc(merchant, 'public_payment_links', 'fees-link'), { adjustments, title: 'Cambio no autorizado', updatedAt: serverTimestamp() }))
    await assertFails(setDoc(feeSettings, {
      perProduct: { enabled: true, mode: 'PERCENT', value: 101 },
      perOrder: adjustments.perOrder,
      updatedAt: serverTimestamp(),
    }))
  })

  it('allows a merchant to mark a verified order item delivered without changing its price', async () => {
    await seedUser('merchant-delivery', 'COMERCIANTE')
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), 'orders', 'delivery-order'), {
        merchantId: 'merchant-delivery',
        paymentStatus: 'VERIFIED_AUTO',
        deliveryStatus: 'PENDING_DELIVERY',
        items: [
          { name: 'Café', qty: 1, unitPrice: 5000, subtotal: 5000 },
          { name: 'Pan', qty: 1, unitPrice: 3000, subtotal: 3000 },
        ],
        deliveredItemIndexes: [],
        totalAmount: 8000,
      })
    })

    const db = googleUser('merchant-delivery')
    const orderRef = doc(db, 'orders', 'delivery-order')
    await assertFails(updateDoc(orderRef, { deliveryStatus: 'DELIVERED' }))
    await assertSucceeds(updateDoc(orderRef, { deliveredItemIndexes: [0] }))
    await assertFails(updateDoc(orderRef, { deliveryStatus: 'DELIVERED' }))
    await assertSucceeds(updateDoc(orderRef, { deliveredItemIndexes: [0, 1] }))
    await assertFails(updateDoc(orderRef, { deliveredItemIndexes: [0, 1], totalAmount: 1 }))
    await assertSucceeds(updateDoc(orderRef, { deliveryStatus: 'DELIVERED' }))
  })

  it('keeps orders private and exposes only unexpired checkout sessions', async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore()
      await setDoc(doc(db, 'orders', 'private-order'), { merchantId: 'merchant-1', customerName: 'Private Name' })
      await setDoc(doc(db, 'checkout_sessions', 'valid-session'), {
        orderId: 'private-order',
        totalAmount: 25_000,
        paymentStatus: 'PENDING',
        deliveryStatus: 'PENDING_DELIVERY',
        expiresAt: Timestamp.fromMillis(Date.now() + 60_000),
      })
      await setDoc(doc(db, 'checkout_sessions', 'expired-session'), {
        orderId: 'private-order',
        totalAmount: 25_000,
        paymentStatus: 'PENDING',
        deliveryStatus: 'PENDING_DELIVERY',
        expiresAt: Timestamp.fromMillis(Date.now() - 60_000),
      })
    })

    const guest = testEnv.unauthenticatedContext().firestore()
    await assertFails(getDoc(doc(guest, 'orders', 'private-order')))
    await assertSucceeds(getDoc(doc(guest, 'checkout_sessions', 'valid-session')))
    await assertFails(getDoc(doc(guest, 'checkout_sessions', 'expired-session')))
    await assertFails(getDocs(query(collection(guest, 'checkout_sessions'))))
  })

  it('allows merchants to read only their orders and prevents self role escalation', async () => {
    await seedUser('merchant-1', 'COMERCIANTE')
    await seedUser('merchant-2', 'COMERCIANTE')
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore()
      await setDoc(doc(db, 'orders', 'own-order'), { merchantId: 'merchant-1' })
      await setDoc(doc(db, 'orders', 'foreign-order'), { merchantId: 'merchant-2' })
    })

    const merchant = googleUser('merchant-1')
    await assertSucceeds(getDoc(doc(merchant, 'orders', 'own-order')))
    await assertFails(getDoc(doc(merchant, 'orders', 'foreign-order')))
    await assertFails(setDoc(doc(merchant, 'users', 'merchant-1'), user('merchant-1', 'ADMINISTRADOR')))
  })

  it('allows a merchant to upload a small supported QR image only to its own path', async () => {
    await seedUser('merchant-1', 'COMERCIANTE')
    const storage = testEnv.authenticatedContext('merchant-1', {
      firebase: { sign_in_provider: 'google.com' },
    }).storage()
    const image = new Uint8Array([0x89, 0x50, 0x4e, 0x47])

    await assertSucceeds(uploadBytes(ref(storage, 'qr_codes/merchant-1/shop.png'), image, { contentType: 'image/png' }))
    await assertFails(uploadBytes(ref(storage, 'qr_codes/merchant-2/shop.png'), image, { contentType: 'image/png' }))
    await assertFails(uploadBytes(ref(storage, 'qr_codes/merchant-1/document.pdf'), image, { contentType: 'application/pdf' }))
    await assertFails(uploadBytes(
      ref(storage, 'qr_codes/merchant-1/too-large.png'),
      new Uint8Array(2 * 1024 * 1024),
      { contentType: 'image/png' },
    ))
    await assertSucceeds(getBytes(ref(testEnv.unauthenticatedContext().storage(), 'qr_codes/merchant-1/shop.png')))
  })

  it('allows only administrators to publish the latest APK at the public release path', async () => {
    await seedUser('admin-1', 'ADMINISTRADOR')
    await seedUser('merchant-1', 'COMERCIANTE')
    const apk = new Uint8Array([0x50, 0x4b, 0x03, 0x04])
    const adminStorage = testEnv.authenticatedContext('admin-1', {
      firebase: { sign_in_provider: 'google.com' },
    }).storage()
    const merchantStorage = testEnv.authenticatedContext('merchant-1', {
      firebase: { sign_in_provider: 'google.com' },
    }).storage()
    const guestStorage = testEnv.unauthenticatedContext().storage()

    await assertSucceeds(uploadBytes(
      ref(adminStorage, 'releases/veripago-latest.apk'),
      apk,
      { contentType: 'application/vnd.android.package-archive' },
    ))
    await assertSucceeds(getBytes(ref(guestStorage, 'releases/veripago-latest.apk')))
    await assertFails(uploadBytes(
      ref(merchantStorage, 'releases/veripago-latest.apk'),
      apk,
      { contentType: 'application/vnd.android.package-archive' },
    ))
    await assertFails(uploadBytes(
      ref(adminStorage, 'releases/older.apk'),
      apk,
      { contentType: 'application/vnd.android.package-archive' },
    ))
  })
})

function googleUser(uid: string, email = `${uid}@example.test`) {
  return testEnv.authenticatedContext(uid, {
    email,
    email_verified: true,
    firebase: { sign_in_provider: 'google.com' },
  }).firestore()
}

function newProfile(uid: string, email: string, role: 'COMERCIANTE' | 'ADMINISTRADOR') {
  return {
    uid,
    email,
    displayName: uid,
    photoUrl: '',
    role,
    isActive: true,
    quotaMaxLinks: 10,
    loyaltyPoints: 0,
    createdAt: serverTimestamp(),
    lastLoginAt: serverTimestamp(),
  }
}

function user(uid: string, role: 'COMERCIANTE' | 'USUARIO' | 'ADMINISTRADOR') {
  return {
    uid,
    email: `${uid}@example.test`,
    displayName: uid,
    photoUrl: '',
    role,
    isActive: true,
    quotaMaxLinks: 10,
    loyaltyPoints: 0,
    createdAt: Timestamp.now(),
    lastLoginAt: Timestamp.now(),
  }
}

async function seedUser(uid: string, role: 'COMERCIANTE' | 'USUARIO' | 'ADMINISTRADOR') {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'users', uid), user(uid, role))
  })
}
