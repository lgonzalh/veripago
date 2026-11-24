import { useEffect, useState } from 'react'
import { Link, Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom'
import {
  ArrowDownToLine, ArrowRight, BadgeCheck, Bell, Check, ChevronDown,
  CircleHelp, Clock3, FileDown, Link2, LogOut, Menu, MessageCircle, PackageCheck, Plus, QrCode,
  ShieldCheck, Smartphone, Store, Truck, Wallet, X,
} from 'lucide-react'
import {
  collection, doc, getDocs, orderBy, query, serverTimestamp, setDoc, updateDoc, where,
} from 'firebase/firestore'
import { getDownloadURL, ref, uploadBytes } from 'firebase/storage'
import brand from '../assets/veripago-brand.webp'
import webDemo from '../assets/VeriPago_Webgif.gif'
import mobileDemo from '../assets/VeriPago_Movilgif.gif'
import googleLogo from '../assets/google.png'
import breBLogo from '../assets/logo_breb.png'
import daviplataLogo from '../assets/logo_daviplata.png'
import nequiLogo from '../assets/logo_nequi.png'
import { auth, db, functions, isFirebaseConfigured, storage } from './lib/firebase'
import {
  createCheckout, createPaymentLink, getApkRelease, getOrders, getPaymentLinkBySlug,
  getProfile, getPublicMerchantQr, markOrderDelivered, signInWithGoogle, updatePaymentLink, uploadQr, watchCheckoutSession,
} from './lib/data'
import { calculateOrderTotal, formatCOP, slugify } from './lib/money'
import { parseNotification } from './lib/bankParser'
import type { OrderRecord, PaymentLink, PaymentMethod, PaymentProvider, PriceAdjustment, Product, Role, UserProfile } from './lib/types'

const roleNames: Record<Role, string> = {
  COMERCIANTE: 'Comerciante', ADMINISTRADOR: 'Administrador',
}

const walletProviders: Array<{ id: PaymentProvider; label: string; logo?: string }> = [
  { id: 'NEQUI', label: 'Nequi', logo: nequiLogo },
  { id: 'DAVIPLATA', label: 'DaviPlata', logo: daviplataLogo },
  { id: 'BRE_B', label: 'Bre-B', logo: breBLogo },
  { id: 'OTHER', label: 'Otro QR' },
]

function paymentMethodsForLink(link: PaymentLink): PaymentMethod[] {
  const methods = link.paymentMethods?.filter((method) => method.qrImageUrl) ?? []
  if (methods.length) return methods
  if (!link.qrImageUrl) return []
  return [{
    provider: link.paymentProvider ?? 'OTHER',
    qrImageUrl: link.qrImageUrl,
    paymentKeyLabel: link.paymentKeyLabel,
    beneficiaryName: link.beneficiaryName,
  }]
}

function PaymentProviderLogo({ provider, className = '' }: { provider: PaymentProvider; className?: string }) {
  const wallet = walletProviders.find((item) => item.id === provider)
  return wallet?.logo
    ? <img src={wallet.logo} alt="" aria-hidden="true" className={className} />
    : <span className={`wallet-other-logo ${className}`}>QR</span>
}

function PaymentProviderChoice({ provider, selected, onSelect }: { provider: PaymentProvider; selected: boolean; onSelect: () => void }) {
  const wallet = walletProviders.find((item) => item.id === provider)!
  return <button type="button" className={`wallet-choice${selected ? ' selected' : ''}`} onClick={onSelect} aria-pressed={selected}>
    <PaymentProviderLogo provider={provider} className="wallet-choice-logo" />
    <span>{wallet.label}</span>
  </button>
}

type WalletDraft = { qrImageUrl: string; paymentKeyLabel: string; beneficiaryName: string; file: File | null }
const emptyWalletDraft = (): WalletDraft => ({ qrImageUrl: '', paymentKeyLabel: '', beneficiaryName: '', file: null })

export default function App() {
  const [user, setUser] = useState<UserProfile | null>(null)
  const [authReady, setAuthReady] = useState(false)
  const [loginError, setLoginError] = useState('')
  const [loginBusy, setLoginBusy] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    if (!auth) { setAuthReady(true); return }
    return auth.onAuthStateChanged(async (firebaseUser) => {
      try { setUser(firebaseUser ? await getProfile(firebaseUser.uid) : null) }
      catch { setUser(null) }
      finally { setAuthReady(true) }
    })
  }, [])

  const startLogin = async () => {
    if (loginBusy) return
    setLoginError('')
    setLoginBusy(true)
    try {
      const profile = await signInWithGoogle()
      setUser(profile)
      navigate(homeForRole(profile.role), { replace: true })
    } catch (error) {
      setLoginError(error instanceof Error ? error.message : 'No pudimos iniciar sesión con Google.')
      window.setTimeout(() => setLoginError(''), 6000)
    } finally {
      setLoginBusy(false)
    }
  }

  const signOut = async () => {
    if (auth) await auth.signOut()
    setUser(null)
    setMenuOpen(false)
  }

  return <>
    <header className="topbar">
      <Link className="brand-lockup" to="/" aria-label="VeriPago inicio"><img src={brand} alt="VeriPago" /></Link>
      {!user && <nav className={menuOpen ? 'nav-links open' : 'nav-links'}>
        <a href="#como-funciona" onClick={() => setMenuOpen(false)}>Cómo funciona</a>
        <a href="#beneficios" onClick={() => setMenuOpen(false)}>Beneficios</a>
      </nav>}
      <div className="top-actions">
        {user ? <div className="user-menu">
          <Link className="avatar-link" to={homeForRole(user.role)}><img src={user.photoUrl || brand} alt="" /><span>{user.displayName.split(' ')[0]}</span><ChevronDown size={15} /></Link>
          <button className="icon-button" title="Cerrar sesión" onClick={signOut}><LogOut size={17} /></button>
        </div> : <button className="button button-small button-light" onClick={startLogin} disabled={loginBusy || !isFirebaseConfigured}><GoogleMark /> {loginBusy ? 'Conectando con Google…' : 'Continuar con Google'} <ArrowRight size={15} /></button>}
        {!user && <button className="menu-toggle" onClick={() => setMenuOpen(!menuOpen)} aria-label="Abrir menú">{menuOpen ? <X /> : <Menu />}</button>}
      </div>
    </header>

    {loginError && <div className="toast toast-error" role="alert"><X size={16} />{loginError}</div>}
    {!authReady ? <main className="loading-screen"><span className="spinner" />Cargando tu espacio</main> :
      <Routes>
        <Route path="/" element={user ? <Navigate to={homeForRole(user.role)} replace /> : <Landing onLogin={startLogin} />} />
        <Route path="/comercio/*" element={user?.role === 'COMERCIANTE'
          ? <MerchantDashboard user={user} />
          : user?.role === 'ADMINISTRADOR' ? <Navigate to="/admin" replace /> : <AccessGate user={user} openLogin={startLogin} />} />
        <Route path="/mi-cuenta/*" element={<Navigate to="/" replace />} />
        <Route path="/admin/*" element={user?.role === 'ADMINISTRADOR'
          ? <AdminDashboard user={user} />
          : user?.role === 'COMERCIANTE' ? <Navigate to="/comercio" replace /> : <AccessGate user={user} openLogin={startLogin} />} />
        <Route path="/:slug" element={user ? <Navigate to={homeForRole(user.role)} replace /> : <PublicCheckout />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>}

    <footer className="site-footer"><Link to="/" className="footer-brand"><img src={brand} alt="" /> VeriPago</Link><span>Pagos directos. Pedidos claros. Cero comisiones.</span><span>© 2026 VeriPago</span></footer>
  </>
}

function homeForRole(role: Role) {
  return role === 'COMERCIANTE' ? '/comercio' : '/admin'
}

function Landing({ onLogin }: { onLogin: () => void }) {
  const [apkRelease, setApkRelease] = useState({ url: '', version: '' })
  const [apkMessage, setApkMessage] = useState('')
  useEffect(() => { getApkRelease().then(setApkRelease).catch(() => setApkMessage('La descarga estará disponible cuando el administrador publique la versión alfa.')) }, [])
  return <main>
    <section className="hero section-wrap">
      <div className="hero-copy">
        <div className="eyebrow"><span className="live-dot" /> COBRA DIRECTO, CONFIRMA AL INSTANTE</div>
        <h1>El pago llega.<br /><span>VeriPago lo confirma<br />en voz alta.</span></h1>
        <p className="hero-lead">Muestra tu QR, o envía un link por Whatsapp y recibe en Nequi, Daviplata o Bre-B para verificar el pago de cada pedido desde un solo lugar.</p>
        <div className="hero-actions">
          <button className="button button-primary" onClick={onLogin}>
            Empezar gratis <ArrowRight size={18} />
          </button>
          {apkRelease.url ? <a className="button button-outline" href={apkRelease.url} download><ArrowDownToLine size={17} /> Descargar app Android</a> : <button className="button button-outline" onClick={() => setApkMessage('La versión para Android estará disponible pronto.')}><Smartphone size={17} /> App Android</button>}
        </div>
        {apkMessage && <div className="subtle-note">{apkMessage}</div>}
        <div className="trust-row"><span>Funciona con tus billeteras de siempre</span><div className="wallet-pills" role="group" aria-label="Medios de pago disponibles">
          <img src={nequiLogo} alt="Nequi" title="Nequi" />
          <img src={daviplataLogo} alt="DaviPlata" title="DaviPlata" />
          <img src={breBLogo} alt="Bre-B" title="Bre-B" />
        </div></div>
      </div>
      <div className="hero-art">
        <div className="glow glow-one" /><div className="glow glow-two" />
        <div className="hero-demo-card"><img src={webDemo} alt="Vista del ecosistema VeriPago" /><div className="demo-caption"><span className="demo-pulse" /><span>Tu negocio, siempre al día</span><b>01 / 02</b></div></div>
        <div className="floating-card floating-card-top"><span className="float-icon green"><BadgeCheck size={20} /></span><span><b>Pago verificado</b><small>Confirmación en tiempo real</small></span></div>
        <div className="floating-card floating-card-bottom"><span className="float-icon purple"><Wallet size={20} /></span><span><b>$ 48.500</b><small>Directo a tu billetera</small></span></div>
      </div>
    </section>

    <section className="stat-strip section-wrap" id="beneficios">
      <div><strong>0%</strong><span>comisiones</span></div><div><strong>3</strong><span>medios de pago</span></div><div><strong>24/7</strong><span>pedidos a la vista</span></div><div><strong>100%</strong><span>del dinero es tuyo</span></div>
    </section>

    <section className="section-wrap how-section" id="como-funciona">
      <div className="section-heading"><div className="eyebrow">SENCILLO DESDE EL PRIMER COBRO</div><h2>Tu mostrador y tus ventas<br /><span>por WhatsApp, conectados.</span></h2><p>Elige cómo cobrar según dónde esté tu cliente. El dinero siempre va directo a tu cuenta.</p></div>
      <div className="mode-grid">
        <article className="mode-card mode-qr"><div className="mode-label">01 · EN PERSONA</div><div className="mode-icon"><QrCode size={23} /></div><h3>Muestra el QR y cobra</h3><p>Arma el pedido, enseña el valor exacto y recibe la transferencia en tu Nequi, Daviplata o llave Bre-B.</p><div className="mode-visual qr-visual"><div className="qr-placeholder"><QrCode size={78} strokeWidth={1.2} /></div><div className="qr-caption"><span><b>Pedido VP-0241</b><small>Valor exacto por pagar</small></span><strong>$ 32.000</strong></div></div></article>
        <article className="mode-card mode-link"><div className="mode-label">02 · A DISTANCIA</div><div className="mode-icon"><Link2 size={23} /></div><h3>Comparte por WhatsApp</h3><p>Envía un enlace personalizado, recibe los datos del cliente y mantén el pago a la vista.</p><div className="mode-visual chat-visual"><div className="chat-bubble">Hola, aquí puedes completar tu pedido 👋<div className="chat-link"><Link2 size={13} /> lantonium.com/VeriPago/tu-pedido</div><small>9:41 ✓✓</small></div><div className="chat-input"><span>Escribe un mensaje</span><span className="chat-send"><ArrowRight size={15} /></span></div></div></article>
      </div>
    </section>

    <section className="section-wrap feature-section"><div className="feature-copy"><div className="eyebrow">LA TRANQUILIDAD TAMBIÉN SE ESCUCHA</div><h2>Atiende a tu cliente.<br /><span>Nosotros te avisamos.</span></h2><p>La app Android detecta la notificación de pago, la relaciona con el pedido y te confirma en voz alta. Revisa qué está pagado y marca cada entrega con un toque.</p><div className="feature-list"><span><BadgeCheck size={17} /> Confirmación automática del pago</span><span><PackageCheck size={17} /> Entregas con trazabilidad</span><span><ShieldCheck size={17} /> Sin claves ni acceso a tu banco</span></div><a href={apkRelease.url || '#descarga'} className="text-link" onClick={(e) => { if (!apkRelease.url) { e.preventDefault(); setApkMessage('La versión alfa se habilitará desde el panel administrador.') } }}>Conoce la app Android <ArrowRight size={16} /></a></div><div className="phone-showcase"><div className="phone-frame"><img src={mobileDemo} alt="Vista de VeriPago móvil" /><div className="phone-top"><span>9:41</span><span>●●● ▰</span></div></div><div className="phone-note"><span className="sound-bars"><i /><i /><i /><i /></span><span><b>Pago recibido</b><small>“Recibiste veinte mil pesos”</small></span><Check size={17} /></div></div></section>

    <section className="closing-cta section-wrap"><div className="closing-icon"><Store size={24} /></div><div><h2>Tu próximo pago puede ser más simple.</h2><p>Empieza hoy. Configura tu primer cobro QR en minutos.</p></div><button className="button button-white" onClick={onLogin}>Crear mi espacio <ArrowRight size={17} /></button></section>
  </main>
}

function GoogleMark() { return <img src={googleLogo} alt="" aria-hidden="true" className="google-logo" /> }

function adjustmentAmount(base: number, adjustment?: PriceAdjustment) {
  if (!adjustment?.enabled || base <= 0) return 0
  return adjustment.mode === 'PERCENT' ? Math.round(base * adjustment.value / 100) : adjustment.value
}

function AccessGate({ user, openLogin }: { user: UserProfile | null; openLogin: () => void }) {
  return <main className="access-gate"><div className="access-card"><span className="access-icon"><ShieldCheck /></span><h2>Este espacio es para usuarios registrados</h2><p>{user ? `Tu perfil actual es ${roleNames[user.role]}.` : 'Inicia sesión para continuar con tu cuenta VeriPago.'}</p>{user ? <Link className="button button-primary" to={homeForRole(user.role)}>Ir a mi espacio</Link> : <button className="button button-primary" onClick={openLogin}><GoogleMark /> Continuar con Google <ArrowRight size={16} /></button>}</div></main>
}

function PublicCheckout() {
  const { slug = '' } = useParams()
  const [link, setLink] = useState<PaymentLink | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [quantities, setQuantities] = useState<Record<string, number>>({})
  const [paymentProvider, setPaymentProvider] = useState<PaymentProvider | ''>('')
  const [sessionId, setSessionId] = useState('')
  const [orderCode, setOrderCode] = useState('')
  const [status, setStatus] = useState('PENDING')
  const [saving, setSaving] = useState(false)
  const [paymentQrSource, setPaymentQrSource] = useState('')
  useEffect(() => { getPaymentLinkBySlug(slug).then((value) => { setLink(value); setLoading(false) }).catch((e) => { setError(errorText(e)); setLoading(false) }) }, [slug])
  useEffect(() => sessionId ? watchCheckoutSession(sessionId, (data) => { if (data?.paymentStatus) setStatus(String(data.paymentStatus)) }) : undefined, [sessionId])
  const items = link?.schemaJson.products ?? []
  const paymentMethods = link ? paymentMethodsForLink(link) : []
  const selectedPaymentMethod = paymentMethods.find((method) => method.provider === paymentProvider) ?? paymentMethods[0]
  useEffect(() => {
    if (!link || !selectedPaymentMethod) { setPaymentQrSource(''); return }
    if (!selectedPaymentMethod.qrImageUrl.startsWith('veripagoqr://')) {
      setPaymentQrSource(selectedPaymentMethod.qrImageUrl)
      return
    }
    let active = true
    getPublicMerchantQr(link.merchantId, selectedPaymentMethod.provider)
      .then((source) => { if (active) setPaymentQrSource(source ?? '') })
      .catch(() => { if (active) setPaymentQrSource('') })
    return () => { active = false }
  }, [link?.merchantId, selectedPaymentMethod?.provider, selectedPaymentMethod?.qrImageUrl])
  const chargedItems = items.map((item) => ({ ...item, unitPrice: item.unitPrice + adjustmentAmount(item.unitPrice, link?.adjustments?.perProduct) }))
  const subtotal = calculateOrderTotal(chargedItems.map((item) => ({ unitPrice: item.unitPrice, qty: quantities[item.id] ?? item.defaultQty ?? 1 })))
  const orderAdjustment = adjustmentAmount(subtotal, link?.adjustments?.perOrder)
  const total = subtotal + orderAdjustment
  const submit = async () => {
    if (!link) return
    if (!name.trim() || phone.replace(/\D/g, '').length < 7) { setError('Ingresa tu nombre y un teléfono válido para continuar.'); return }
    setSaving(true); setError('')
    try {
      const checkout = await createCheckout({ linkId: link.id, items: items.map((item) => ({ productId: item.id, qty: Math.min(100, quantities[item.id] ?? item.defaultQty ?? 1) })), customerName: name.trim(), customerPhone: phone.trim(), checkoutChannel: 'WHATSAPP_LINK', ...(selectedPaymentMethod ? { paymentProvider: selectedPaymentMethod.provider } : {}) })
      setSessionId(checkout.sessionId); setOrderCode(checkout.orderCode)
    } catch (e) { setError(errorText(e)) }
    finally { setSaving(false) }
  }
  if (loading) return <main className="loading-screen"><span className="spinner" />Buscando tu pedido</main>
  if (!link) return <main className="checkout-empty"><div className="checkout-empty-icon"><CircleHelp /></div><h1>Este enlace no está disponible</h1><p>{error || 'Pídele al comercio que te comparta un enlace vigente.'}</p><Link to="/" className="text-link">Conoce VeriPago <ArrowRight size={15} /></Link></main>
  const paid = status === 'VERIFIED_AUTO' || status === 'VERIFIED_MANUAL'
  return <main className="checkout-page"><div className="checkout-shell"><div className="checkout-brand"><img src={brand} alt="VeriPago" /><span>Compra segura y directa</span></div>
    <div className="checkout-card"><div className="checkout-head"><span className="checkout-icon"><Store size={20} /></span><span><small>ESTÁS PAGANDO A</small><b>{link.beneficiaryName || link.title}</b></span><ShieldCheck className="secure-icon" size={18} /></div>
      {paid ? <div className="paid-state"><span className="paid-check"><Check size={36} /></span><div className="eyebrow">PAGO CONFIRMADO</div><h1>¡Pago verificado!</h1><p>El comercio ya recibió la confirmación de tu transferencia.</p><div className="receipt-row"><span>Pedido</span><b>{orderCode}</b></div><div className="receipt-row"><span>Total</span><b>{formatCOP(total)}</b></div><div className="verified-foot"><BadgeCheck size={16} /> Comprobante digital VeriPago</div></div> : <>
        <div className="checkout-title"><div className="eyebrow">{sessionId ? 'ESPERANDO CONFIRMACIÓN' : 'COMPLETA TU PEDIDO'}</div><h1>{sessionId ? 'Paga y vuelve aquí' : link.title}</h1><p>{sessionId ? 'Transfiere el valor exacto con el QR. La confirmación aparecerá aquí automáticamente.' : 'Revisa tu pedido, transfiere directo al comercio y sigue el estado en tiempo real.'}</p></div>
        {!sessionId && <><div className="checkout-products">{chargedItems.length ? chargedItems.map((item) => <div className="checkout-product" key={item.id}><div><b>{item.name}</b><small>{formatCOP(item.unitPrice)} c/u</small></div><div className="qty-picker"><button onClick={() => setQuantities((q) => ({ ...q, [item.id]: Math.max(0, (q[item.id] ?? item.defaultQty ?? 1) - 1) }))}>−</button><span>{Math.min(100, quantities[item.id] ?? item.defaultQty ?? 1)}</span><button onClick={() => setQuantities((q) => ({ ...q, [item.id]: Math.min(100, (q[item.id] ?? item.defaultQty ?? 1) + 1) }))}>+</button></div></div>) : <div className="checkout-product"><b>Pedido</b><span>Contacta al comercio para conocer el detalle.</span></div>}</div>
          <label className="field-label">Tu nombre<input value={name} onChange={(e) => setName(e.target.value)} placeholder="Nombre y apellido" autoComplete="name" /></label><label className="field-label">WhatsApp o teléfono<input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="300 000 0000" inputMode="tel" autoComplete="tel" /></label>
        </>}
        <div className="checkout-total"><span>Total a transferir</span><strong>{formatCOP(total)}</strong></div>
        {paymentMethods.length > 0 && <div className="checkout-wallet-options" aria-label="Elige cómo pagar">{paymentMethods.map((method) => <PaymentProviderChoice key={method.provider} provider={method.provider} selected={(selectedPaymentMethod?.provider ?? '') === method.provider} onSelect={() => setPaymentProvider(method.provider)} />)}</div>}
        {selectedPaymentMethod && paymentQrSource && <div className="merchant-qr"><img src={paymentQrSource} alt={`Código QR de pago para ${selectedPaymentMethod.beneficiaryName || link.title}`} /><div><b>{selectedPaymentMethod.paymentKeyLabel || 'Escanea para pagar'}</b><small>Transfiere con {walletProviders.find((item) => item.id === selectedPaymentMethod.provider)?.label ?? 'tu billetera'} directamente al comercio</small></div></div>}
        {link.merchantWhatsapp && <a className="button button-white button-full" href={`https://wa.me/${link.merchantWhatsapp.replace(/\D/g, '')}`} target="_blank" rel="noopener noreferrer"><MessageCircle size={17} /> Contactar al comercio por WhatsApp</a>}
        {error && <div className="inline-error">{error}</div>}
        {!sessionId ? <button className="button button-primary button-full" onClick={submit} disabled={saving || total <= 0}>{saving ? 'Preparando pedido…' : <>Confirmar pedido <ArrowRight size={17} /></>}</button> : <div className="waiting-banner"><span className="spinner" /><span>Esperando que el comercio confirme tu pago</span></div>}
        <div className="checkout-privacy"><ShieldCheck size={14} /> Tu dinero llega directo a tu billetera. 0% comisión de VeriPago; Nequi, DaviPlata o Bre-B podrían cobrar la suya.</div>
      </>}
    </div><p className="checkout-powered">Protegido por <b>VeriPago</b> · 0% comisión de VeriPago</p></div></main>
}

function MerchantDashboard({ user }: { user: UserProfile }) {
  const [tab, setTab] = useState<'cobrar' | 'pedidos' | 'links'>('cobrar')
  const [orders, setOrders] = useState<OrderRecord[]>([])
  const [links, setLinks] = useState<PaymentLink[]>([])
  const [error, setError] = useState('')
  const [working, setWorking] = useState(false)
  const [qrModal, setQrModal] = useState<{ url: string; code: string; total: number; paymentProvider: PaymentProvider; paymentKeyLabel: string } | null>(null)
  const [title, setTitle] = useState('Pedido rápido')
  const [productName, setProductName] = useState('')
  const [price, setPrice] = useState('')
  const [qty, setQty] = useState(1)
  const [selectedProvider, setSelectedProvider] = useState<PaymentProvider>('NEQUI')
  const [walletDrafts, setWalletDrafts] = useState<Record<PaymentProvider, WalletDraft>>({ NEQUI: emptyWalletDraft(), DAVIPLATA: emptyWalletDraft(), BRE_B: emptyWalletDraft(), OTHER: emptyWalletDraft() })
  const [filter, setFilter] = useState('')

  const refresh = async () => {
    try {
      const [loadedOrders, linkSnap] = await Promise.all([
        getOrders(user.uid, 'COMERCIANTE'),
        getDocs(query(collection(db!, 'payment_links'), where('merchantId', '==', user.uid), orderBy('createdAt', 'desc'))),
      ])
      setOrders(loadedOrders)
      const loadedLinks = linkSnap.docs.map((item) => ({ ...item.data(), id: item.id }) as PaymentLink)
      setLinks(loadedLinks)
      const currentMethods = paymentMethodsForLink(loadedLinks.find((item) => item.defaultMode === 'IN_SITU_QR') ?? ({ qrImageUrl: '' } as PaymentLink))
      setWalletDrafts((previous) => {
        const next = { ...previous }
        for (const method of currentMethods) next[method.provider] = { qrImageUrl: method.qrImageUrl, paymentKeyLabel: method.paymentKeyLabel ?? '', beneficiaryName: method.beneficiaryName ?? '', file: null }
        return next
      })
    } catch (e) { setError(errorText(e)) }
  }
  useEffect(() => { if (db) void refresh() }, [user.uid])
  const parsedPrice = Number(price)
  const product: Product = { id: 'quick-product', name: productName || 'Pedido', unitPrice: Number.isSafeInteger(parsedPrice) && parsedPrice > 0 ? parsedPrice : 0 }
  const total = calculateOrderTotal([{ unitPrice: product.unitPrice, qty }])

  const saveWalletMethods = async () => {
    const methods: PaymentMethod[] = []
    for (const wallet of walletProviders) {
      const draft = walletDrafts[wallet.id]
      const qrImageUrl = draft.file ? await uploadQr(user.uid, draft.file) : draft.qrImageUrl
      if (qrImageUrl) methods.push({ provider: wallet.id, qrImageUrl, paymentKeyLabel: draft.paymentKeyLabel.trim(), beneficiaryName: draft.beneficiaryName.trim() || user.displayName })
    }
    if (!methods.length) throw new Error('Configura al menos un QR de Nequi, DaviPlata o Bre-B.')
    return methods
  }

  const createQrOrder = async () => {
    if (!db || !functions) { setError('Configura Firebase antes de crear pedidos.'); return }
    if (!product.unitPrice) { setError('Ingresa un valor mayor a cero.'); return }
    setWorking(true); setError('')
    try {
      const currentLink = links.find((item) => item.defaultMode === 'IN_SITU_QR')
      const methods = await saveWalletMethods()
      const method = methods.find((item) => item.provider === selectedProvider) ?? methods[0]
      setSelectedProvider(method.provider)
      let linkId = ''
      if (currentLink) {
        linkId = currentLink.id
        await updatePaymentLink({ id: currentLink.id, schemaJson: { products: [product] }, qrImageUrl: method.qrImageUrl, paymentKeyLabel: method.paymentKeyLabel, beneficiaryName: method.beneficiaryName, paymentProvider: method.provider, paymentMethods: methods })
      } else {
        const created = await ensureQuickLink(methods, method)
        linkId = created.id
      }
      const result = await createCheckout({ linkId, items: [{ productId: product.id, qty }], customerName: 'Cliente en mostrador', customerPhone: '', checkoutChannel: 'WEB_IN_SITU_QR', paymentProvider: method.provider })
      setQrModal({ url: method.qrImageUrl, code: result.orderCode, total: result.totalAmount, paymentProvider: method.provider, paymentKeyLabel: method.paymentKeyLabel })
      await refresh()
    } catch (e) { setError(errorText(e)) }
    finally { setWorking(false) }
  }

  const ensureQuickLink = async (methods: PaymentMethod[], primary: PaymentMethod) => {
    const created = await createPaymentLink({ slug: 'cobro-rapido', title: 'Cobro rápido', defaultMode: 'IN_SITU_QR', schemaJson: { products: [product] }, qrImageUrl: primary.qrImageUrl, paymentKeyLabel: primary.paymentKeyLabel, beneficiaryName: primary.beneficiaryName, paymentProvider: primary.provider, paymentMethods: methods })
    return { id: created.id }
  }

  const createShareLink = async () => {
    if (!product.unitPrice) { setError('Agrega al menos un producto con precio antes de compartir.'); return }
    setWorking(true); setError('')
    try {
      const methods = await saveWalletMethods()
      const primary = methods.find((item) => item.provider === selectedProvider) ?? methods[0]
      const created = await createPaymentLink({ slug: `${slugify(title) || 'pedido'}-${Math.random().toString(36).slice(2, 6)}`, title, defaultMode: 'WHATSAPP_LINK', schemaJson: { products: [product], captureCustomerInfo: true }, qrImageUrl: primary.qrImageUrl, paymentKeyLabel: primary.paymentKeyLabel, beneficiaryName: primary.beneficiaryName, paymentProvider: primary.provider, paymentMethods: methods })
      const url = `${window.location.origin}/VeriPago/${created.slug}`
      window.open(`https://wa.me/?text=${encodeURIComponent(`Hola, completa tu pedido de ${title}: ${url}`)}`, '_blank', 'noopener,noreferrer')
      await refresh()
      setTab('links')
    } catch (e) { setError(errorText(e)) }
    finally { setWorking(false) }
  }

  const markDelivered = async (orderId: string) => {
    try { await markOrderDelivered(orderId); await refresh() } catch (e) { setError(errorText(e)) }
  }

  const filteredOrders = orders.filter((order) => `${order.customerName} ${order.orderCode}`.toLowerCase().includes(filter.toLowerCase()))
  const paidTotal = orders.filter((order) => order.paymentStatus.startsWith('VERIFIED')).reduce((sum, order) => sum + order.totalAmount, 0)
  const updateWalletDraft = (provider: PaymentProvider, changes: Partial<WalletDraft>) => setWalletDrafts((drafts) => ({ ...drafts, [provider]: { ...drafts[provider], ...changes } }))

  return <DashboardShell user={user} eyebrow="ESPACIO COMERCIANTE" title="Tu negocio, en orden." subtitle="Cobra sin fricción y entrega con la tranquilidad de un pago verificado.">
    <div className="dashboard-stats"><Stat icon={<Wallet />} label="Recaudado verificado" value={formatCOP(paidTotal)} tone="green" /><Stat icon={<Clock3 />} label="Por confirmar" value={`${orders.filter((o) => o.paymentStatus === 'PENDING').length} pedidos`} tone="purple" /><Stat icon={<Link2 />} label="Enlaces activos" value={`${links.filter((l) => l.isActive).length}`} tone="blue" /></div>
    <div className="dashboard-tabs"><button className={tab === 'cobrar' ? 'active' : ''} onClick={() => setTab('cobrar')}><QrCode size={16} /> Cobrar</button><button className={tab === 'pedidos' ? 'active' : ''} onClick={() => setTab('pedidos')}><PackageCheck size={16} /> Pedidos <span className="tab-count">{orders.length}</span></button><button className={tab === 'links' ? 'active' : ''} onClick={() => setTab('links')}><Link2 size={16} /> Mis enlaces</button></div>
    {error && <div className="inline-error">{error}</div>}
    {tab === 'cobrar' && <div className="merchant-grid"><section className="panel composer-panel"><div className="panel-heading"><span className="panel-heading-icon"><QrCode size={20} /></span><div><h2>Arma un cobro</h2><p>Configura el valor y elige cómo cobrar.</p></div></div>
      <label className="field-label">Nombre del pedido<input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Ej. Almuerzo ejecutivo" /></label><div className="form-row"><label className="field-label">Producto o concepto<input value={productName} onChange={(e) => setProductName(e.target.value)} placeholder="Ej. Menú del día" /></label><label className="field-label">Valor (COP)<input type="number" min="0" step="100" value={price} onChange={(e) => setPrice(e.target.value)} placeholder="25.000" /></label></div><label className="field-label qty-field">Cantidad<div className="qty-picker"><button onClick={() => setQty(Math.max(1, qty - 1))}>−</button><span>{qty}</span><button onClick={() => setQty(qty + 1)}>+</button></div></label>
      <div className="composer-total"><span>Total del pedido</span><b>{formatCOP(total)}</b></div>
      <div className="wallet-editor-grid">{walletProviders.slice(0, 3).map((wallet) => { const draft = walletDrafts[wallet.id]; const inputId = `qr-upload-${wallet.id}`; return <div className={`wallet-config-card${selectedProvider === wallet.id ? ' selected' : ''}`} key={wallet.id}>
        <PaymentProviderChoice provider={wallet.id} selected={selectedProvider === wallet.id} onSelect={() => setSelectedProvider(wallet.id)} />
        <input id={inputId} type="file" accept="image/png,image/jpeg,image/webp" onChange={(e) => updateWalletDraft(wallet.id, { file: e.target.files?.[0] ?? null })} />
        <label htmlFor={inputId} className="qr-upload-box">{draft.file ? <><BadgeCheck size={16} /><span>{draft.file.name}</span></> : draft.qrImageUrl ? <><BadgeCheck size={16} /><span>QR configurado</span></> : <><Plus size={16} /><span>Subir QR {wallet.label}</span><small>PNG, JPG o WebP · hasta 2 MB</small></>}</label>
        <input className="wallet-text-input" value={draft.beneficiaryName} onChange={(e) => updateWalletDraft(wallet.id, { beneficiaryName: e.target.value })} placeholder="Titular" aria-label={`Titular de ${wallet.label}`} />
        <input className="wallet-text-input" value={draft.paymentKeyLabel} onChange={(e) => updateWalletDraft(wallet.id, { paymentKeyLabel: e.target.value })} placeholder="Llave o número" aria-label={`Llave o número de ${wallet.label}`} />
      </div>})}</div>
      <div className="dual-actions"><button className="button button-primary" onClick={createQrOrder} disabled={working}><QrCode size={17} /> Mostrar QR</button><button className="button button-whatsapp" onClick={createShareLink} disabled={working}><span className="wa-mark">◉</span> Compartir por WhatsApp</button></div><p className="helper-line"><ShieldCheck size={14} /> Tu dinero llega directo a tu billetera. 0% comisión de VeriPago; Nequi, DaviPlata o Bre-B podrían cobrar la suya.</p>
    </section><section className="panel insight-panel"><div className="panel-heading"><span className="panel-heading-icon violet"><Bell size={20} /></span><div><h2>Así funciona tu cobro</h2><p>VeriPago acompaña cada paso.</p></div></div><div className="timeline"><div className="timeline-step done"><span><Check size={14} /></span><div><b>Armas el pedido</b><small>Agrega productos, cantidad y valor.</small></div></div><div className="timeline-step done"><span><QrCode size={14} /></span><div><b>El cliente transfiere</b><small>Escanea tu QR y paga directo a tu cuenta.</small></div></div><div className="timeline-step"><span><Bell size={14} /></span><div><b>VeriPago te avisa</b><small>La app confirma el pago y te lo anuncia.</small></div></div><div className="timeline-step"><span><Truck size={14} /></span><div><b>Entregas con confianza</b><small>Marca el pedido entregado cuando quieras.</small></div></div></div><div className="device-state"><span className="device-symbol"><Smartphone size={20} /></span><span><b>App VeriPago para Android</b><small>Vincula tu celular receptor</small></span><span className="device-offline"><i /> Por configurar</span></div><div className="qr-tip"><ShieldCheck size={18} /><span>VeriPago solo lee las notificaciones de pago. Nunca pedimos tu clave bancaria.</span></div></section></div>}
    {tab === 'pedidos' && <OrderTable orders={filteredOrders} filter={filter} setFilter={setFilter} markDelivered={markDelivered} exportEnabled />}
    {tab === 'links' && <section className="panel"><div className="panel-heading"><span className="panel-heading-icon"><Link2 size={20} /></span><div><h2>Enlaces de cobro</h2><p>Comparte pedidos por WhatsApp o redes.</p></div><button className="button button-small button-outline" onClick={() => setTab('cobrar')}><Plus size={15} /> Crear enlace</button></div>{links.length ? <div className="link-list">{links.map((link) => <div className="link-row" key={link.id}><span className="link-row-icon"><Link2 size={16} /></span><div><b>{link.title}</b><small>{link.defaultMode === 'WHATSAPP_LINK' ? 'Enlace para compartir' : 'Cobro en mostrador'} · {link.slug}</small></div><a className="button button-small button-outline" href={`${window.location.origin}/VeriPago/${link.slug}`} target="_blank" rel="noreferrer">Abrir <ArrowRight size={14} /></a></div>)}</div> : <EmptyState title="Aún no tienes enlaces" body="Configura un pedido y compártelo con tus clientes por WhatsApp." action={() => setTab('cobrar')} />}</section>}
    {qrModal && <QrPaymentModal value={qrModal} onClose={() => setQrModal(null)} />}
  </DashboardShell>
}

function OrderTable({ orders, filter, setFilter, markDelivered, exportEnabled = false }: { orders: OrderRecord[]; filter: string; setFilter: (value: string) => void; markDelivered?: (id: string) => void; exportEnabled?: boolean }) {
  const exportCsv = () => {
    const rows = [['Código', 'Cliente', 'Teléfono', 'Total COP', 'Pago', 'Entrega'], ...orders.map((order) => [order.orderCode, order.customerName, order.customerPhone, String(order.totalAmount), order.paymentStatus, order.deliveryStatus])]
    const csv = rows.map((row) => row.map((cell) => `"${cell.replaceAll('"', '""')}"`).join(',')).join('\r\n')
    const url = URL.createObjectURL(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }))
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'veripago-pedidos.csv'; anchor.click(); URL.revokeObjectURL(url)
  }
  const exportExcel = async () => {
    const ExcelJS = await import('exceljs')
    const book = new ExcelJS.Workbook()
    const sheet = book.addWorksheet('Pedidos')
    sheet.columns = [
      { header: 'Código', key: 'code', width: 18 },
      { header: 'Cliente', key: 'customer', width: 28 },
      { header: 'Teléfono', key: 'phone', width: 18 },
      { header: 'Total COP', key: 'total', width: 16 },
      { header: 'Pago', key: 'payment', width: 22 },
      { header: 'Entrega', key: 'delivery', width: 22 },
    ]
    sheet.addRows(orders.map((order) => ({ code: order.orderCode, customer: order.customerName, phone: order.customerPhone, total: order.totalAmount, payment: order.paymentStatus, delivery: order.deliveryStatus })))
    const data = await book.xlsx.writeBuffer()
    const url = URL.createObjectURL(new Blob([data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'veripago-pedidos.xlsx'; anchor.click(); URL.revokeObjectURL(url)
  }
  return <section className="panel orders-panel"><div className="panel-heading"><span className="panel-heading-icon"><PackageCheck size={20} /></span><div><h2>Pedidos y entregas</h2><p>Confirma lo recibido y sigue cada entrega.</p></div><input className="search-input" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Buscar cliente o código" />{exportEnabled && <div className="export-actions"><button className="button button-small button-outline" onClick={exportExcel}><FileDown size={14} /> Excel</button><button className="button button-small button-outline" onClick={exportCsv}><FileDown size={14} /> CSV</button></div>}</div>{orders.length ? <div className="table-wrap"><table><thead><tr><th>Pedido</th><th>Cliente</th><th>Total</th><th>Pago</th><th>Entrega</th><th /></tr></thead><tbody>{orders.map((order) => <tr key={order.id}><td><b>{order.orderCode}</b><small>{order.createdAt ? dateLabel(order.createdAt) : 'Hoy'}</small></td><td>{order.customerName || 'Cliente mostrador'}</td><td><b>{formatCOP(order.totalAmount)}</b></td><td><PaymentPill status={order.paymentStatus} /></td><td>{order.deliveryStatus === 'DELIVERED' ? <span className="delivered-label"><Check size={14} /> Entregado</span> : 'Por entregar'}</td><td>{markDelivered && order.paymentStatus.startsWith('VERIFIED') && order.deliveryStatus !== 'DELIVERED' && <button className="text-action" onClick={() => markDelivered(order.id)}>Marcar entregado <ArrowRight size={14} /></button>}</td></tr>)}</tbody></table></div> : <EmptyState title="Tus pedidos aparecerán aquí" body="Cuando empieces a cobrar, podrás seguir pagos y entregas en tiempo real." action={() => setFilter('')} />}</section>
}

function QrPaymentModal({ value, onClose }: { value: { url: string; code: string; total: number; paymentProvider: PaymentProvider; paymentKeyLabel: string }; onClose: () => void }) {
  return <div className="modal-backdrop" onClick={onClose}><div className="qr-modal" onClick={(e) => e.stopPropagation()}><button className="modal-close" onClick={onClose}><X size={19} /></button><div className="eyebrow">COBRO EN MOSTRADOR</div><h2>Escanea y paga</h2><p>Pedido <b>{value.code}</b></p><div className="qr-modal-code">{value.url ? <img src={value.url} alt={`QR de ${walletProviders.find((item) => item.id === value.paymentProvider)?.label ?? 'pago'}`} /> : <div><QrCode size={80} /><small>Configura un QR bancario en VeriPago Web</small></div>}</div><div className="wallet-modal-method"><PaymentProviderLogo provider={value.paymentProvider} className="wallet-choice-logo" /><b>{walletProviders.find((item) => item.id === value.paymentProvider)?.label}</b>{value.paymentKeyLabel && <span>{value.paymentKeyLabel}</span>}</div><div className="qr-modal-total"><span>Total exacto</span><b>{formatCOP(value.total)}</b></div><div className="waiting-banner"><span className="spinner" /> Esperando confirmación del pago</div><button className="button button-outline button-full" onClick={onClose}>Listo</button></div></div>
}

function AdminDashboard({ user }: { user: UserProfile }) {
  const [tab, setTab] = useState<'overview' | 'rules' | 'users' | 'platform'>('overview')
  const [orders, setOrders] = useState<OrderRecord[]>([])
  const [rules, setRules] = useState<Array<Record<string, unknown>>>([])
  const [users, setUsers] = useState<UserProfile[]>([])
  const [error, setError] = useState('')
  const [regexText, setRegexText] = useState('Te llegaron $15.000 de Maria Perez')
  const [amountRegex, setAmountRegex] = useState('(?i)(?:lleg(?:ó|o|aron)|recibiste)\\s*\\$?\\s*([0-9][0-9.,]*)')
  const [senderRegex, setSenderRegex] = useState('(?i)(?:de|remitente)\\s*[:\\-]?\\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑa-záéíóúñ ]{2,60})')
  const [apkFile, setApkFile] = useState<File | null>(null)
  const [apkVersion, setApkVersion] = useState('1.0.0-alpha.1')
  const [saving, setSaving] = useState(false)
  const [parsed, setParsed] = useState<{ amount: string; sender: string } | null>(null)
  const refresh = async () => {
    if (!db) return
    try {
      const [orderSnap, ruleSnap, userSnap] = await Promise.all([
        getDocs(query(collection(db, 'orders'), orderBy('createdAt', 'desc'))),
        getDocs(collection(db, 'bank_parser_rules')),
        getDocs(collection(db, 'users')),
      ])
      setOrders(orderSnap.docs.map((d) => ({ ...d.data(), id: d.id }) as OrderRecord))
      setRules(ruleSnap.docs.map((d) => ({ ...d.data(), id: d.id })))
      setUsers(userSnap.docs.map((d) => d.data() as UserProfile))
    } catch (e) { setError(errorText(e)) }
  }
  useEffect(() => { void refresh() }, [])
  const matchRegex = () => {
    try {
      const result = parseNotification(amountRegex, senderRegex, regexText)
      setParsed({ amount: result ? formatCOP(result.amount) : 'Sin coincidencia', sender: result?.sender || 'Sin coincidencia' })
    } catch { setParsed({ amount: 'Regex inválida', sender: 'Regex inválida' }) }
  }
  const saveRule = async () => {
    if (!db) return
    setSaving(true); setError('')
    try {
      const id = `rule-${crypto.randomUUID()}`
      await setDoc(doc(db, 'bank_parser_rules', id), { walletName: 'Personalizada', androidPackage: '', amountRegex, senderRegex, isActive: false, updatedAt: serverTimestamp() })
      await refresh()
    } catch (e) { setError(errorText(e)) }
    finally { setSaving(false) }
  }
  const changeRole = async (uid: string, role: Role) => {
    try { await updateDoc(doc(db!, 'users', uid), { role }); await refresh() } catch (e) { setError(errorText(e)) }
  }
  const publishApk = async () => {
    if (!storage || !db || !apkFile) return
    if (!apkFile.name.toLowerCase().endsWith('.apk')) { setError('Selecciona un archivo .apk.'); return }
    setSaving(true); setError('')
    try {
      const target = ref(storage, 'releases/veripago-latest.apk')
      await uploadBytes(target, apkFile, { contentType: 'application/vnd.android.package-archive' })
      const url = await getDownloadURL(target)
      await setDoc(doc(db, 'platform_settings', 'global'), { latestApkUrl: url, latestApkVersion: apkVersion, apkReleaseNotes: 'Versión alfa VeriPago', updatedAt: serverTimestamp() }, { merge: true })
      setApkFile(null)
    } catch (e) { setError(errorText(e)) }
    finally { setSaving(false) }
  }
  const matched = orders.filter((o) => o.paymentStatus.startsWith('VERIFIED'))
  const volume = matched.reduce((sum, item) => sum + item.totalAmount, 0)
  return <DashboardShell user={user} eyebrow="CONSOLA DE PLATAFORMA" title="Todo conectado." subtitle="Monitorea la operación y administra la configuración de VeriPago.">
    {error && <div className="inline-error">{error}</div>}<div className="dashboard-tabs"><button className={tab === 'overview' ? 'active' : ''} onClick={() => setTab('overview')}><Wallet size={16} /> Resumen</button><button className={tab === 'rules' ? 'active' : ''} onClick={() => setTab('rules')}><ShieldCheck size={16} /> Reglas Regex</button><button className={tab === 'users' ? 'active' : ''} onClick={() => setTab('users')}><Store size={16} /> Usuarios</button><button className={tab === 'platform' ? 'active' : ''} onClick={() => setTab('platform')}><Smartphone size={16} /> Plataforma</button></div>
    {tab === 'overview' && <><div className="dashboard-stats"><Stat icon={<Wallet />} label="Volumen verificado" value={formatCOP(volume)} tone="green" /><Stat icon={<BadgeCheck />} label="Pagos conciliados" value={`${matched.length}`} tone="blue" /><Stat icon={<Store />} label="Comercios registrados" value={`${users.filter((u) => u.role === 'COMERCIANTE').length}`} tone="purple" /></div><section className="panel"><div className="panel-heading"><span className="panel-heading-icon"><PackageCheck size={20} /></span><div><h2>Actividad reciente</h2><p>Operación global de VeriPago.</p></div></div><OrderTable orders={orders.slice(0, 20)} filter="" setFilter={() => undefined} /></section></>}
    {tab === 'rules' && <section className="panel admin-rules"><div className="panel-heading"><span className="panel-heading-icon violet"><ShieldCheck size={20} /></span><div><h2>Reglas bancarias</h2><p>Prueba las expresiones antes de sincronizarlas.</p></div></div><label className="field-label">Texto de notificación<textarea value={regexText} onChange={(e) => setRegexText(e.target.value)} rows={2} /></label><label className="field-label">Expresión para monto<input value={amountRegex} onChange={(e) => setAmountRegex(e.target.value)} /></label><label className="field-label">Expresión para remitente<input value={senderRegex} onChange={(e) => setSenderRegex(e.target.value)} /></label><div className="form-actions"><button className="button button-outline" onClick={matchRegex}>Probar reglas</button><button className="button button-primary" onClick={saveRule} disabled={saving}><Plus size={15} /> Guardar desactivada</button></div>{parsed && <div className="regex-result"><b>Resultado de prueba</b><span>Monto: {parsed.amount}</span><span>Remitente: {parsed.sender}</span></div>}<div className="rule-list">{rules.map((rule) => <div className="rule-row" key={String(rule.id)}><span className="rule-dot" /><div><b>{String(rule.walletName)}</b><small>{String(rule.androidPackage || 'Paquete por configurar')}</small></div><span className="status-chip status-pending">{rule.isActive ? 'Activa' : 'Desactivada'}</span></div>)}</div></section>}
    {tab === 'users' && <section className="panel"><div className="panel-heading"><span className="panel-heading-icon"><Store size={20} /></span><div><h2>Perfiles registrados</h2><p>Asigna el rol correcto para cada cuenta.</p></div></div><div className="user-list">{users.map((profile) => <div className="admin-user-row" key={profile.uid}><img src={profile.photoUrl || brand} alt="" /><span><b>{profile.displayName}</b><small>{profile.email}</small></span><select value={profile.role} onChange={(e) => changeRole(profile.uid, e.target.value as Role)} disabled={profile.uid === user.uid}><option value="COMERCIANTE">Comerciante</option><option value="ADMINISTRADOR">Administrador</option></select></div>)}</div></section>}
    {tab === 'platform' && <section className="panel platform-panel"><div className="panel-heading"><span className="panel-heading-icon"><Smartphone size={20} /></span><div><h2>Publicar app Android</h2><p>Actualiza el APK que se ofrece para descarga.</p></div></div><div className="form-row"><label className="field-label">Versión<input value={apkVersion} onChange={(e) => setApkVersion(e.target.value)} /></label><label className="field-label">Archivo APK<input type="file" accept=".apk,application/vnd.android.package-archive" onChange={(e) => setApkFile(e.target.files?.[0] ?? null)} /></label></div><button className="button button-primary" onClick={publishApk} disabled={saving || !apkFile}><ArrowDownToLine size={16} /> {saving ? 'Publicando…' : 'Publicar versión'}</button><p className="helper-line"><ShieldCheck size={14} /> El APK estará disponible públicamente desde Storage.</p></section>}
  </DashboardShell>
}

function DashboardShell({ user, eyebrow, title, subtitle, children }: { user: UserProfile; eyebrow: string; title: string; subtitle: string; children: React.ReactNode }) {
  return <main className="dashboard-wrap"><div className="dashboard-back"><span className="role-chip"><span className="live-dot" />{roleNames[user.role]}</span></div><section className="dashboard-heading"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1><p>{subtitle}</p></div><div className="dashboard-user"><img src={user.photoUrl || brand} alt="" /><span><b>{user.displayName}</b><small>{user.email} · Google</small></span><ShieldCheck size={18} /></div></section>{children}</main>
}

function Stat({ icon, label, value, tone }: { icon: React.ReactNode; label: string; value: string; tone: 'green' | 'purple' | 'blue' }) {
  return <article className="stat-card"><span className={`stat-icon ${tone}`}>{icon}</span><div><small>{label}</small><strong>{value}</strong></div></article>
}

function PaymentPill({ status }: { status: string }) {
  const verified = status.startsWith('VERIFIED')
  return <span className={verified ? 'status-chip status-paid' : status === 'CANCELLED' ? 'status-chip status-cancelled' : 'status-chip status-pending'}>{verified ? <BadgeCheck size={13} /> : <Clock3 size={13} />}{status === 'VERIFIED_AUTO' ? 'Verificado' : status === 'VERIFIED_MANUAL' ? 'Confirmado' : status === 'CANCELLED' ? 'Cancelado' : 'Pendiente'}</span>
}

function EmptyState({ title, body, action }: { title: string; body: string; action: () => void }) {
  return <div className="empty-state"><span className="empty-icon"><PackageCheck size={23} /></span><h3>{title}</h3><p>{body}</p><button className="text-link" onClick={action}>Empezar <ArrowRight size={14} /></button></div>
}

function errorText(error: unknown) {
  if (error instanceof Error) return error.message
  return 'No se pudo completar la acción. Inténtalo de nuevo.'
}

function dateLabel(value: NonNullable<OrderRecord['createdAt']>) {
  const date = value instanceof Date ? value : value.toDate()
  return new Intl.DateTimeFormat('es-CO', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
