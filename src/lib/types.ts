export type Role = 'COMERCIANTE' | 'ADMINISTRADOR'
export type PaymentStatus = 'PENDING' | 'VERIFIED_AUTO' | 'VERIFIED_MANUAL' | 'CANCELLED'
export type DeliveryStatus = 'PENDING_DELIVERY' | 'DELIVERED'
export type CheckoutMode = 'IN_SITU_QR' | 'WHATSAPP_LINK'
export type PaymentProvider = 'NEQUI' | 'DAVIPLATA' | 'BRE_B' | 'OTHER'

export interface PaymentMethod {
  provider: PaymentProvider
  qrImageUrl: string
  paymentKeyLabel: string
  beneficiaryName: string
}

export interface UserProfile {
  uid: string
  email: string
  displayName: string
  photoUrl: string
  role: Role
  isActive: boolean
  quotaMaxLinks: number
  loyaltyPoints: number
}

export interface Product {
  id: string
  name: string
  unitPrice: number
}

export interface PaymentLink {
  id: string
  merchantId: string
  slug: string
  title: string
  defaultMode: CheckoutMode
  schemaJson: { products: Product[]; captureCustomerInfo?: boolean }
  qrImageUrl: string
  paymentKeyLabel: string
  beneficiaryName: string
  paymentProvider?: PaymentProvider
  paymentMethods?: PaymentMethod[]
  merchantWhatsapp?: string
  isActive: boolean
}

export interface OrderRecord {
  id: string
  linkId: string
  merchantId: string
  orderCode: string
  customerUid: string | null
  customerName: string
  customerPhone: string
  items: Array<{ name: string; qty: number; unitPrice: number; subtotal: number }>
  totalAmount: number
  checkoutChannel: string
  paymentStatus: PaymentStatus
  deliveryStatus: DeliveryStatus
  createdAt?: { toDate: () => Date } | Date
}
