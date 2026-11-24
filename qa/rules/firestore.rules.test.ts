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

  it('allows a merchant to mark a verified order item delivered without changing its price', async () => {
    await seedUser('merchant-delivery', 'COMERCIANTE')
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), 'orders', 'delivery-order'), {
        merchantId: 'merchant-delivery',
        paymentStatus: 'VERIFIED_AUTO',
        deliveryStatus: 'PENDING_DELIVERY',
        items: [{ name: 'Café', qty: 1, unitPrice: 5000, subtotal: 5000 }],
        deliveredItemIndexes: [],
        totalAmount: 5000,
      })
    })

    const db = googleUser('merchant-delivery').firestore()
    const orderRef = doc(db, 'orders', 'delivery-order')
    await assertSucceeds(updateDoc(orderRef, { deliveredItemIndexes: [0] }))
    await assertFails(updateDoc(orderRef, { deliveredItemIndexes: [0], totalAmount: 1 }))
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
