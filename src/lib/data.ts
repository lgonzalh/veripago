import { signInWithPopup } from 'firebase/auth'
import {
  collection, doc, getDoc, getDocs, onSnapshot, orderBy, query, serverTimestamp,
  setDoc, updateDoc, where, type Unsubscribe,
} from 'firebase/firestore'
import { httpsCallable } from 'firebase/functions'
import { getDownloadURL, ref, uploadBytes } from 'firebase/storage'
import { auth, db, functions, googleProvider, storage } from './firebase'
import type { OrderRecord, PaymentLink, PaymentProvider, PaymentMethod, Role, UserProfile } from './types'

function requireServices() {
  if (!auth || !db || !functions || !storage) throw new Error('Firebase no está configurado. Copia .env.example a .env.local y agrega las credenciales del proyecto.')
  return { auth, db, functions, storage }
}

export async function signInWithGoogle(initialRole: 'COMERCIANTE' | 'USUARIO') {
  const { auth, db } = requireServices()
  const credential = await signInWithPopup(auth, googleProvider)
  const profileRef = doc(db, 'users', credential.user.uid)
  const existing = await getDoc(profileRef)
  if (!existing.exists()) {
    const profile: UserProfile = {
      uid: credential.user.uid,
      email: credential.user.email ?? '',
      displayName: credential.user.displayName ?? 'Usuario VeriPago',
      photoUrl: credential.user.photoURL ?? '',
      role: initialRole,
      isActive: true,
      quotaMaxLinks: 10,
      loyaltyPoints: 0,
    }
    await setDoc(profileRef, { ...profile, createdAt: serverTimestamp(), lastLoginAt: serverTimestamp() })
    return profile
  }
  await updateDoc(profileRef, { lastLoginAt: serverTimestamp() })
  return existing.data() as UserProfile
}

export async function getProfile(uid: string) {
  const { db } = requireServices()
  const snap = await getDoc(doc(db, 'users', uid))
  return snap.exists() ? snap.data() as UserProfile : null
}

export async function getPaymentLinkBySlug(slug: string) {
  const { db } = requireServices()
  const result = await getDoc(doc(db, 'public_payment_links', slug))
  if (!result.exists() || result.get('isActive') !== true) return null
  return { ...result.data(), id: result.get('linkId') as string } as PaymentLink
}

export async function createPaymentLink(input: Omit<PaymentLink, 'id' | 'merchantId' | 'isActive'>) {
  const { functions } = requireServices()
  const call = httpsCallable<typeof input, { id: string; slug: string }>(functions, 'createPaymentLink')
  return (await call(input)).data
}

export async function updatePaymentLink(input: {
  id: string
  schemaJson: PaymentLink['schemaJson']
  qrImageUrl?: string
  paymentKeyLabel?: string
  beneficiaryName?: string
  paymentProvider?: PaymentProvider
  paymentMethods?: PaymentMethod[]
}) {
  const { functions } = requireServices()
  const call = httpsCallable<typeof input, { id: string }>(functions, 'updatePaymentLink')
  return (await call(input)).data
}

export async function createCheckout(input: {
  linkId: string
  items: Array<{ productId: string; qty: number }>
  customerName: string
  customerPhone: string
  checkoutChannel?: string
  paymentProvider?: PaymentProvider
}) {
  const { functions } = requireServices()
  const call = httpsCallable<typeof input, { orderId: string; sessionId: string; orderCode: string; totalAmount: number }>(functions, 'createCheckoutOrder')
  return (await call(input)).data
}

export async function getOrders(uid: string, role: Role) {
  const { db } = requireServices()
  const key = role === 'COMERCIANTE' ? 'merchantId' : 'customerUid'
  const result = await getDocs(query(collection(db, 'orders'), where(key, '==', uid), orderBy('createdAt', 'desc')))
  return result.docs.map((item) => ({ ...item.data(), id: item.id }) as OrderRecord)
}

export async function markOrderDelivered(orderId: string) {
  const { db } = requireServices()
  await updateDoc(doc(db, 'orders', orderId), { deliveryStatus: 'DELIVERED', serverUpdatedAt: serverTimestamp() })
}

export async function uploadQr(uid: string, file: File) {
  const { storage } = requireServices()
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size >= 2 * 1024 * 1024) {
    throw new Error('El QR debe ser PNG, JPEG o WebP y pesar menos de 2 MB.')
  }
  const location = ref(storage, `qr_codes/${uid}/${crypto.randomUUID()}-${file.name.replace(/[^a-zA-Z0-9._-]/g, '')}`)
  await uploadBytes(location, file, { contentType: file.type })
  return getDownloadURL(location)
}

export async function getApkUrl() {
  const { storage } = requireServices()
  return getDownloadURL(ref(storage, 'releases/veripago-latest.apk'))
}

export async function getApkRelease() {
  const { db, storage } = requireServices()
  const release = await getDoc(doc(db, 'platform_settings', 'global'))
  const fallbackUrl = await getDownloadURL(ref(storage, 'releases/veripago-latest.apk')).catch(() => '')
  return {
    url: release.get('latestApkUrl') as string || fallbackUrl,
    version: release.get('latestApkVersion') as string || '',
  }
}

export function watchCheckoutSession(sessionId: string, callback: (value: Record<string, unknown> | null) => void): Unsubscribe {
  const { db } = requireServices()
  return onSnapshot(doc(db, 'checkout_sessions', sessionId), (snapshot) => callback(snapshot.exists() ? snapshot.data() : null))
}

export async function getPublishedAnnouncements() {
  const { db } = requireServices()
  const result = await getDocs(query(collection(db, 'announcements'), where('isPublished', '==', true)))
  return result.docs.map((item) => ({ ...item.data(), id: item.id }))
}
