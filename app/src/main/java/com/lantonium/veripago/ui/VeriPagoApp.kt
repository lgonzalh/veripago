package com.lantonium.veripago.ui

import android.app.Activity
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.OneTimeWorkRequestBuilder
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.request.ImageRequest
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.lantonium.veripago.AppGraph
import com.lantonium.veripago.BuildConfig
import com.lantonium.veripago.R
import com.lantonium.veripago.data.FirebaseClients
import com.lantonium.veripago.data.GoogleAuthRepository
import com.lantonium.veripago.data.AdminRepository
import com.lantonium.veripago.data.AdminOverview
import com.lantonium.veripago.data.AdminOrderItem
import com.lantonium.veripago.data.AdminRule
import com.lantonium.veripago.data.PlatformRelease
import com.lantonium.veripago.data.OrderEntity
import com.lantonium.veripago.data.MerchantProduct
import com.lantonium.veripago.data.MerchantProductRepository
import com.lantonium.veripago.data.PendingSyncScheduler
import com.lantonium.veripago.data.UserProfile
import com.lantonium.veripago.data.BankNotificationParser
import com.lantonium.veripago.data.BankRuleEntity
import com.lantonium.veripago.service.PaymentMonitorService
import com.lantonium.veripago.service.VoiceConfirmationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.text.NumberFormat
import kotlin.math.roundToLong

private val Navy = Color(0xFF0C2442)
private val Muted = Color(0xFF758398)
private val Green = Color(0xFF15966A)

private data class AndroidPaymentMethod(
    val provider: String,
    val qrImageUrl: String,
    val paymentKeyLabel: String,
    val beneficiaryName: String,
)

private data class ChargeDraftLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val unitPriceText: String = "",
    val quantityText: String = "1",
    val catalogProductId: String? = null,
)

private data class ProductPriceBreakdown(val beforeVat: Long, val vatAmount: Long, val withVat: Long)

private fun parseVatRate(raw: String): Double? {
    if (raw.isBlank()) return 0.0
    return raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
}

private fun calculateProductPricing(enteredPrice: Long, vatRate: Double, includesVat: Boolean): ProductPriceBreakdown {
    if (enteredPrice <= 0 || vatRate <= 0.0) return ProductPriceBreakdown(enteredPrice, 0, enteredPrice)
    return if (includesVat) {
        val beforeVat = (enteredPrice / (1.0 + vatRate / 100.0)).roundToLong()
        ProductPriceBreakdown(beforeVat, enteredPrice - beforeVat, enteredPrice)
    } else {
        val vat = (enteredPrice * vatRate / 100.0).roundToLong()
        ProductPriceBreakdown(enteredPrice, vat, enteredPrice + vat)
    }
}

private fun demoMerchantProduct() = MerchantProduct(
    id = "demo-product-1",
    name = "Producto de prueba",
    enteredPrice = 11900,
    vatRate = 19.0,
    priceIncludesVat = true,
    priceWithoutVat = 10000,
    vatAmount = 1900,
    priceWithVat = 11900,
)

private const val DEMO_MERCHANT_UID = "demo-paula-local"

private fun isDemoMerchant(profile: UserProfile): Boolean =
    BuildConfig.DEBUG && profile.uid == DEMO_MERCHANT_UID

private fun demoMerchantProfile() = UserProfile(
    uid = DEMO_MERCHANT_UID,
    email = "correapaula882@gmail.com",
    displayName = "Paula Correa",
    photoUrl = "",
    role = "COMERCIANTE",
    isActive = true,
    loyaltyPoints = 0,
)

private fun demoMerchantOrders() = listOf(
    OrderEntity(
        orderId = "demo-order-verified",
        orderCode = "VP-DEMO-101",
        merchantId = DEMO_MERCHANT_UID,
        customerUid = null,
        customerName = "Cliente de prueba",
        customerPhone = "",
        itemsJson = "[]",
        totalAmount = 48500,
        checkoutChannel = "DEMO",
        paymentStatus = "VERIFIED",
        deliveryStatus = "PENDING",
        matchedNotificationId = null,
        createdAt = System.currentTimeMillis() - 60_000,
        updatedAt = System.currentTimeMillis() - 60_000,
    ),
    OrderEntity(
        orderId = "demo-order-pending",
        orderCode = "VP-DEMO-102",
        merchantId = DEMO_MERCHANT_UID,
        customerUid = null,
        customerName = "Pedido pendiente",
        customerPhone = "",
        itemsJson = "[]",
        totalAmount = 25000,
        checkoutChannel = "DEMO",
        paymentStatus = "PENDING",
        deliveryStatus = "PENDING",
        matchedNotificationId = null,
        createdAt = System.currentTimeMillis() - 30_000,
        updatedAt = System.currentTimeMillis() - 30_000,
    ),
)

private val paymentProviderNames = mapOf("NEQUI" to "Nequi", "DAVIPLATA" to "DaviPlata", "BRE_B" to "Bre-B", "OTHER" to "Otro QR")
private fun paymentProviderLogo(provider: String): Int? = when (provider) {
    "NEQUI" -> R.drawable.logo_nequi
    "DAVIPLATA" -> R.drawable.logo_daviplata
    "BRE_B" -> R.drawable.logo_breb
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VeriPagoApp(activity: Activity) {
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var demoOrders by remember { mutableStateOf(demoMerchantOrders()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val splashStartedAt = System.currentTimeMillis()
        val cached = runCatching { AppGraph.profileCache.read() }.getOrNull()
        profile = cached?.takeIf { it.role == "COMERCIANTE" || it.role == "ADMINISTRADOR" }
        if (cached != null && profile == null) AppGraph.profileCache.clear()
        val currentUser = FirebaseClients.auth?.currentUser
        val splashTimeRemaining = 3_000L - (System.currentTimeMillis() - splashStartedAt)
        if (splashTimeRemaining > 0) delay(splashTimeRemaining)
        // Show the locally restored session without waiting for a network round trip.
        loading = false
        if (currentUser != null && FirebaseClients.firestore != null) {
            runCatching {
                val userDoc = FirebaseClients.getUserProfile(currentUser.uid)
                val role = userDoc.getString("role").orEmpty()
                if (userDoc.exists() && (role == "COMERCIANTE" || role == "ADMINISTRADOR") && userDoc.getBoolean("isActive") == true) {
                    val saved = UserProfile(currentUser.uid, userDoc.getString("email").orEmpty(), userDoc.getString("displayName").orEmpty(),
                        userDoc.getString("photoUrl").orEmpty(), role, true, userDoc.getLong("loyaltyPoints") ?: 0L)
                    profile = saved
                    AppGraph.profileCache.save(saved)
                } else {
                    FirebaseClients.auth?.signOut()
                    AppGraph.profileCache.clear()
                    profile = null
                    error = "Inicia sesión en la app web para registrar tu perfil de comerciante."
                }
            }.onFailure { error = signInErrorMessage(it) }
        }
    }

    val credentialManager = remember { CredentialManager.create(context) }
    val signInWithGoogle: () -> Unit = {
        error = ""
        scope.launch {
            try {
                if (!FirebaseClients.isConfigured) error("Firebase aún no está configurado en este build.")
                if (BuildConfig.WEB_CLIENT_ID.isBlank()) error("Falta webClientId para Google Credential Manager en local.properties.")
                val signInOption = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()
                val credential = credentialManager.getCredential(activity, request).credential
                if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    error("Google no devolvió una credencial compatible.")
                }
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authRepo = GoogleAuthRepository(context, AppGraph.profileCache)
                profile = authRepo.signIn(token)
                error = ""
                snackbar.showSnackbar("Hola, ${profile?.displayName?.substringBefore(' ')}. Tu espacio está listo.")
                if (profile?.role == "COMERCIANTE") startPaymentListener(context)
            } catch (exception: GetCredentialCancellationException) {
                error = "Se canceló el inicio de sesión con Google."
            } catch (exception: NoCredentialException) {
                error = "No se encontró una cuenta de Google disponible en este dispositivo."
            } catch (exception: GetCredentialException) {
                error = "Google no pudo ofrecer una cuenta. Revisa la conexión e inténtalo de nuevo."
            } catch (exception: Exception) {
                error = signInErrorMessage(exception)
            }
        }
    }

    val nav = rememberNavController()
    val navEntry by nav.currentBackStackEntryAsState()
    val currentRoute = navEntry?.destination?.route ?: "inicio"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (!loading && currentRoute in setOf("acerca", "perfil")) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                title = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.veripago_mark),
                        contentDescription = "Logo VeriPago",
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("VeriPago", color = Navy, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("ECOSISTEMA DE VERIFICACIÓN DE PAGOS", color = Muted, fontSize = 7.sp, letterSpacing = 0.35.sp, maxLines = 1)
                    }
                } },
                actions = {
                    if (!loading && currentRoute !in setOf("acerca", "perfil")) IconButton(
                        onClick = { nav.navigate("acerca") },
                        modifier = Modifier.clip(CircleShape).background(Color(0xFF2168D4)).size(40.dp),
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Acerca de VeriPago", tint = Color.White)
                    }
                    if (profile != null) {
                        val signedInProfile = profile!!
                        if (signedInProfile.photoUrl.isNotBlank()) {
                            AsyncImage(
                                model = signedInProfile.photoUrl,
                                contentDescription = "Cuenta de Google de ${signedInProfile.displayName} (${signedInProfile.email})",
                                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { nav.navigate("perfil") },
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(Navy).clickable { nav.navigate("perfil") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(signedInProfile.displayName.firstOrNull()?.uppercase() ?: "G", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        IconButton(onClick = {
                            scope.launch {
                                if (!isDemoMerchant(signedInProfile)) {
                                    FirebaseClients.auth?.signOut()
                                    AppGraph.profileCache.clear()
                                }
                                profile = null
                                nav.navigate("inicio") {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }) {
                            Image(
                                painter = painterResource(R.drawable.logout),
                                contentDescription = "Cerrar sesión",
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (profile != null && currentRoute != "acerca") {
                NavigationBar(containerColor = Color.White) {
                    val entries = when (profile?.role) {
                        "COMERCIANTE" -> listOf("inicio" to "Cobrar", "pedidos" to "Pedidos", "estado" to "Celular")
                        "ADMINISTRADOR" -> listOf(
                            "inicio" to "Resumen",
                            "reglas" to "Reglas Regex",
                            "usuarios" to "Usuarios",
                            "plataforma" to "Plataforma",
                        )
                        else -> emptyList()
                    }
                    entries.forEach { (route, label) ->
                        val selected = currentRoute == route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigate(route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = {
                                val icon = when (route) {
                                    "pedidos" -> Icons.Default.CheckCircle
                                    "reglas" -> Icons.Default.NotificationsActive
                                    "usuarios" -> Icons.Default.AccountCircle
                                    "plataforma" -> Icons.Default.Storefront
                                    else -> Icons.Default.QrCode2
                                }
                                Icon(icon, contentDescription = null)
                            },
                            label = { Text(label, fontSize = 9.sp, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (loading) {
            Box(
                Modifier.fillMaxSize().padding(padding).background(Color(0xFF071A32)),
                contentAlignment = Alignment.Center,
            ) {
                VeriPagoAnimatedSplash(Modifier.fillMaxWidth().height(500.dp).padding(horizontal = 36.dp))
                Text(
                    "Ecosistema de verificación de pagos",
                    modifier = Modifier.align(Alignment.Center).offset(y = 98.dp)
                        .background(Color(0xFF071A32)).padding(horizontal = 6.dp, vertical = 1.dp),
                    color = Color.White,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        } else {
            NavHost(navController = nav, startDestination = "inicio", modifier = Modifier.padding(padding)) {
                composable("inicio") {
                    val signedInProfile = profile
                    if (signedInProfile == null) {
                        LandingScreen(
                            modifier = Modifier,
                            configured = FirebaseClients.isConfigured,
                            error = error,
                            onLogin = signInWithGoogle,
                            onDemoMerchant = { demoOrders = demoMerchantOrders(); profile = demoMerchantProfile(); error = "" },
                            onRegister = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://veripago-9cda6.web.app/VeriPago/"))) },
                        )
                    } else when (signedInProfile.role) {
                        "COMERCIANTE" -> MerchantScreen(signedInProfile, demoOrders, { demoOrders = it }, onError = { error = it; scope.launch { snackbar.showSnackbar(it) } })
                        "ADMINISTRADOR" -> AdminScreen()
                        else -> MerchantScreen(signedInProfile, demoOrders, { demoOrders = it }, onError = { error = it; scope.launch { snackbar.showSnackbar(it) } })
                    }
                }
                composable("acerca") { AboutScreen() }
                composable("perfil") { profile?.let { ProfileScreen(it) } }
                composable("pedidos") { profile?.let { OrdersScreen(it, demoOrders, { demoOrders = it }) } }
                composable("estado") { ListenerStatusScreen(isDemo = profile?.let(::isDemoMerchant) == true) }
                composable("reglas") { if (profile?.role == "ADMINISTRADOR") AdminRulesScreen() else profile?.let { MerchantScreen(it, demoOrders, { demoOrders = it }, onError = { error = it }) } }
                composable("usuarios") { if (profile?.role == "ADMINISTRADOR") AdminUsersScreen(profile!!) else profile?.let { MerchantScreen(it, demoOrders, { demoOrders = it }, onError = { error = it }) } }
                composable("plataforma") { if (profile?.role == "ADMINISTRADOR") AdminPlatformScreen() else profile?.let { MerchantScreen(it, demoOrders, { demoOrders = it }, onError = { error = it }) } }
            }
        }
    }

}

private fun signInErrorMessage(exception: Throwable): String {
    val message = exception.localizedMessage.orEmpty()
    val firestoreException = exception as? FirebaseFirestoreException
    return if (firestoreException?.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
        firestoreException?.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ||
        message.contains("client is offline", ignoreCase = true)
    ) {
        "No se pudo conectar con VeriPago. Revisa la conexión e inténtalo de nuevo."
    } else {
        message.ifBlank { "No se pudo iniciar sesión." }
    }
}

@Composable
private fun VeriPagoAnimatedSplash(modifier: Modifier) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val drawable = remember(context) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, R.drawable.veripago_splash))
                    as? AnimatedImageDrawable
            }.getOrNull()
        }
        DisposableEffect(drawable) {
            drawable?.start()
            onDispose { drawable?.stop() }
        }
        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                ImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(drawable)
                }
            },
            update = { it.setImageDrawable(drawable) },
        )
    } else {
        AsyncImage(
            model = remember {
                ImageRequest.Builder(context)
                    .data(R.drawable.veripago_splash)
                    .decoderFactory(GifDecoder.Factory())
                    .build()
            },
            contentDescription = "Animación de inicio de VeriPago",
            modifier = modifier,
        )
    }
}

@Composable
private fun LandingScreen(modifier: Modifier, configured: Boolean, error: String, onLogin: () -> Unit, onDemoMerchant: () -> Unit, onRegister: () -> Unit) {
    Column(modifier.fillMaxSize().background(Color(0xFFF5F7FA))) {
        Column(Modifier.weight(1f).padding(horizontal = 24.dp).padding(top = 34.dp), verticalArrangement = Arrangement.Center) {
            Text("COBRA DIRECTO. CONFIRMA AL INSTANTE.", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(16.dp))
            Text("El pago llega.\nVeriPago lo confirma en voz alta.", color = Navy, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(13.dp))
            Text("Muestra tu QR, o envía un link por Whatsapp y recibe en Nequi, Daviplata o Bre-B para verificar el pago de cada pedido desde un solo lugar.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(24.dp))
            FeatureLine(Icons.Default.QrCode2, "Cobra con QR o comparte por WhatsApp")
            FeatureLine(Icons.Default.VolumeUp, "Escucha la confirmación del pago")
            FeatureLine(Icons.Default.CheckCircle, "Entrega con el pago verificado")
        }
        Column(Modifier.fillMaxWidth().background(Color.White).padding(22.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(51.dp), shape = RoundedCornerShape(12.dp)) {
                Image(painterResource(R.drawable.google_logo), contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text("Continuar con Google", fontWeight = FontWeight.SemiBold)
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFB44743), fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (!configured) Text("Configura Google Sign-In en Firebase para habilitar el acceso.", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onRegister, modifier = Modifier.fillMaxWidth()) { Text("Registrar perfil de comerciante en la web") }
            if (BuildConfig.DEBUG) TextButton(onClick = onDemoMerchant, modifier = Modifier.fillMaxWidth()) { Text("Probar espacio comerciante (demo)") }
        }
    }
}

@Composable
private fun FeatureLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 7.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEAF4FF)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color(0xFF3578D8), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp)); Text(label, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(profile: UserProfile) {
    val isDemo = isDemoMerchant(profile)
    val scope = rememberCoroutineScope()
    var whatsappNumber by rememberSaveable(profile.uid) { mutableStateOf("") }
    var whatsappDraft by rememberSaveable(profile.uid) { mutableStateOf("") }
    var loadingContact by remember(profile.uid) { mutableStateOf(!isDemo && profile.role == "COMERCIANTE") }
    var savingContact by remember { mutableStateOf(false) }
    var contactMessage by remember { mutableStateOf("") }
    LaunchedEffect(profile.uid, isDemo) {
        if (!isDemo && profile.role == "COMERCIANTE") {
            runCatching {
                FirebaseClients.firestore?.collection("users")?.document(profile.uid)
                    ?.collection("settings")?.document("contact")?.get()?.await()
                    ?.getString("whatsappNumber").orEmpty()
            }.onSuccess { number -> whatsappNumber = number; whatsappDraft = number }
                .onFailure { contactMessage = "No se pudo cargar el contacto de WhatsApp." }
            loadingContact = false
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (isDemo) DemoNotice()
        Text("PERFIL", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
        Text("Tu cuenta VeriPago", color = Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                if (profile.photoUrl.isNotBlank()) {
                    AsyncImage(profile.photoUrl, contentDescription = "Foto de ${profile.displayName}", modifier = Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Navy), contentAlignment = Alignment.Center) {
                        Text(profile.displayName.firstOrNull()?.uppercase() ?: "G", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(profile.displayName.ifBlank { "Usuario de Google" }, color = Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(profile.email, color = Muted, fontSize = 11.sp)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Nombre de Google", color = Muted, fontSize = 10.sp)
                Text(profile.displayName.ifBlank { "Usuario de Google" }, color = Navy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Este nombre viene de tu cuenta de Google y no se edita en VeriPago.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(5.dp))
                Text("Correo", color = Muted, fontSize = 10.sp)
                Text(profile.email, color = Navy, fontSize = 13.sp)
                Spacer(Modifier.height(5.dp))
                Text("Tipo de cuenta", color = Muted, fontSize = 10.sp)
                Text(if (profile.role == "ADMINISTRADOR") "Administrador" else "Comerciante", color = Navy, fontSize = 13.sp)
            }
        }
        if (profile.role == "COMERCIANTE") {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WhatsApp del comercio", color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Este número aparecerá como contacto en tus enlaces de cobro.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    if (isDemo) {
                        Text("En la prueba no se guarda ni se publica ningún número.", color = Color(0xFFB54743), fontSize = 10.sp)
                    } else {
                        OutlinedTextField(
                            value = whatsappDraft,
                            onValueChange = { whatsappDraft = it.take(20); contactMessage = "" },
                            label = { Text("Número del comerciante") },
                            placeholder = { Text("+57 300 000 0000") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            enabled = !loadingContact && !savingContact,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = !loadingContact && !savingContact && whatsappDraft != whatsappNumber,
                            onClick = {
                                val normalized = normalizeWhatsappNumber(whatsappDraft)
                                if (normalized == null) {
                                    contactMessage = "Ingresa un número válido, por ejemplo +57 300 000 0000."
                                } else scope.launch {
                                    savingContact = true; contactMessage = ""
                                    runCatching {
                                        val firestore = FirebaseClients.firestore ?: error("Firebase no está configurado.")
                                        firestore.collection("users").document(profile.uid).collection("settings").document("contact")
                                            .set(mapOf("whatsappNumber" to normalized, "updatedAt" to FieldValue.serverTimestamp())).await()
                                    }.onSuccess {
                                        whatsappNumber = normalized; whatsappDraft = normalized
                                        contactMessage = "Número guardado. Los nuevos enlaces incluirán este contacto."
                                    }.onFailure { contactMessage = it.localizedMessage ?: "No se pudo guardar el número." }
                                    savingContact = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loadingContact) "Cargando…" else if (savingContact) "Guardando…" else "Guardar número") }
                    }
                    if (contactMessage.isNotBlank()) Text(contactMessage, color = if (contactMessage.startsWith("No se pudo") || contactMessage.startsWith("Ingresa")) Color(0xFFB54743) else Green, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun MerchantScreen(profile: UserProfile, demoOrders: List<OrderEntity>, onDemoOrdersChange: (List<OrderEntity>) -> Unit, onError: (String) -> Unit) {
    val isDemo = isDemoMerchant(profile)
    var chargeLines by remember(profile.uid) { mutableStateOf(listOf(ChargeDraftLine())) }
    var catalogProducts by remember(profile.uid) { mutableStateOf(emptyList<MerchantProduct>()) }
    var catalogName by rememberSaveable(profile.uid) { mutableStateOf("") }
    var catalogPrice by rememberSaveable(profile.uid) { mutableStateOf("") }
    var catalogVatRate by rememberSaveable(profile.uid) { mutableStateOf("") }
    var catalogPriceIncludesVat by rememberSaveable(profile.uid) { mutableStateOf(false) }
    var editingProductId by rememberSaveable(profile.uid) { mutableStateOf("") }
    var savingProduct by remember { mutableStateOf(false) }
    var catalogMessage by remember { mutableStateOf("") }
    var productMenuLineId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var qrState by remember { mutableStateOf<Triple<AndroidPaymentMethod, String, Long>?>(null) }
    var demoSharePreview by remember { mutableStateOf<String?>(null) }
    var configuredPaymentMethods by remember { mutableStateOf<List<AndroidPaymentMethod>>(emptyList()) }
    var selectedPaymentProvider by rememberSaveable { mutableStateOf("NEQUI") }
    var customerWhatsapp by rememberSaveable(profile.uid) { mutableStateOf("") }
    var merchantWhatsapp by remember(profile.uid) { mutableStateOf("") }
    var pendingQrProvider by remember { mutableStateOf("") }
    var uploadingQrProvider by remember { mutableStateOf("") }
    var qrUploadMessage by remember { mutableStateOf("") }
    var latestOrders by remember { mutableStateOf<List<OrderEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val productRepository = remember { MerchantProductRepository() }
    val installedWalletApps = remember(profile.uid) { detectInstalledWalletApps(context) }
    val amount = chargeLines.sumOf { line ->
        (line.unitPriceText.toLongOrNull() ?: 0L) * (line.quantityText.toIntOrNull()?.coerceIn(1, 100) ?: 1)
    }
    val chargeIsValid = chargeLines.isNotEmpty() && chargeLines.size <= 50 && chargeLines.all { line ->
        line.name.isNotBlank() && (line.unitPriceText.toLongOrNull() ?: 0L) > 0L && (line.quantityText.toIntOrNull() ?: 0) in 1..100
    }
    val catalogEnteredPrice = catalogPrice.toLongOrNull() ?: 0L
    val catalogVat = parseVatRate(catalogVatRate)
    val catalogPricing = calculateProductPricing(catalogEnteredPrice, catalogVat ?: 0.0, catalogPriceIncludesVat)
    fun updateChargeLine(id: String, update: (ChargeDraftLine) -> ChargeDraftLine) {
        chargeLines = chargeLines.map { if (it.id == id) update(it) else it }
    }
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val provider = pendingQrProvider
        if (uri != null && provider.isNotBlank()) scope.launch {
            uploadingQrProvider = provider; qrUploadMessage = ""
            runCatching {
                uploadAndSaveWalletQr(context, profile, provider, uri)
            }.onSuccess { methods ->
                configuredPaymentMethods = methods
                selectedPaymentProvider = provider
                qrUploadMessage = "QR de ${paymentProviderNames[provider]} guardado. VeriPago no valida su titular ni su destino."
            }.onFailure { qrUploadMessage = it.localizedMessage ?: "No se pudo guardar el QR." }
            uploadingQrProvider = ""
        }
    }
    LaunchedEffect(profile.uid) {
        if (isDemo) {
            configuredPaymentMethods = listOf("NEQUI", "DAVIPLATA", "BRE_B").map { provider ->
                AndroidPaymentMethod(provider, "", "Cuenta de prueba", "Paula Correa")
            }
            catalogProducts = listOf(demoMerchantProduct())
            return@LaunchedEffect
        }
        val sync = AppGraph.syncManager()
        sync.start(profile.uid)
        runCatching {
            val firestore = FirebaseClients.firestore ?: return@runCatching
            catalogProducts = productRepository.load(profile.uid)
            merchantWhatsapp = firestore.collection("users").document(profile.uid)
                .collection("settings").document("contact").get().await().getString("whatsappNumber").orEmpty()
            val link = firestore.collection("payment_links").whereEqualTo("merchantId", profile.uid)
                .whereEqualTo("defaultMode", "IN_SITU_QR").whereEqualTo("isActive", true).limit(1).get().await().documents.firstOrNull()
            if (link != null) {
                configuredPaymentMethods = parsePaymentMethods(link)
                if (configuredPaymentMethods.none { it.provider == selectedPaymentProvider && isWalletProviderAvailable(it.provider, installedWalletApps) }) {
                    selectedPaymentProvider = configuredPaymentMethods.firstOrNull { isWalletProviderAvailable(it.provider, installedWalletApps) }?.provider ?: "NEQUI"
                }
            }
        }
        AppGraph.payments.observeOrders(profile.uid).collect { latestOrders = it }
    }
    val selectedPaymentMethod = configuredPaymentMethods.find { it.provider == selectedPaymentProvider }
        ?.takeIf { isWalletProviderAvailable(it.provider, installedWalletApps) }
    val visibleOrders = if (isDemo) demoOrders else latestOrders

    LazyColumn(Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        if (isDemo) item { DemoNotice() }
        item {
            Column(Modifier.padding(top = 14.dp, bottom = 3.dp)) {
                Text("ESPACIO COMERCIANTE", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp)); Text("Tu negocio, en orden.", color = Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Cobra sin fricción y entrega con confianza.", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniMetric("Verificados", visibleOrders.count { it.paymentStatus.startsWith("VERIFIED") }.toString(), Green, Modifier.weight(1f))
                MiniMetric("Pendientes", visibleOrders.count { it.paymentStatus == "PENDING" }.toString(), Color(0xFF977338), Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode2, null, tint = Color(0xFF3475D3)); Spacer(Modifier.width(8.dp))
                        Column { Text("Cobro rápido", color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text("QR inmediato o enlace por WhatsApp", color = Muted, fontSize = 10.sp) }
                    }
                    chargeLines.forEachIndexed { index, line ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Color(0xFFF7F9FC)).padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(line.name, { value -> updateChargeLine(line.id) { it.copy(name = value.take(100), catalogProductId = null) } }, label = { Text("Producto ${index + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
                                if (chargeLines.size > 1) TextButton(onClick = { chargeLines = chargeLines.filterNot { it.id == line.id } }) { Text("Quitar", fontSize = 9.sp) }
                            }
                            Box {
                                TextButton(enabled = catalogProducts.isNotEmpty(), onClick = { productMenuLineId = line.id }) {
                                    Text(if (line.catalogProductId == null) "Elegir del catálogo" else "Cambiar producto", fontSize = 9.sp)
                                }
                                DropdownMenu(expanded = productMenuLineId == line.id, onDismissRequest = { productMenuLineId = "" }) {
                                    catalogProducts.forEach { product ->
                                        DropdownMenuItem(
                                            text = { Column {
                                                Text(product.name, color = Navy, fontSize = 12.sp)
                                                Text("${formatCop(product.priceWithVat)} con IVA · IVA ${formatCop(product.vatAmount)}", color = Muted, fontSize = 9.sp)
                                            } },
                                            onClick = {
                                                updateChargeLine(line.id) { it.copy(name = product.name, unitPriceText = product.priceWithVat.toString(), catalogProductId = product.id) }
                                                productMenuLineId = ""
                                            },
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                OutlinedTextField(line.unitPriceText, { value -> updateChargeLine(line.id) { it.copy(unitPriceText = value.filter(Char::isDigit).take(10), catalogProductId = null) } }, label = { Text("Precio unitario (COP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(line.quantityText, { value -> updateChargeLine(line.id) { it.copy(quantityText = value.filter(Char::isDigit).take(3)) } }, label = { Text("Cantidad") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(100.dp))
                            }
                            val lineTotal = (line.unitPriceText.toLongOrNull() ?: 0L) * (line.quantityText.toIntOrNull()?.coerceIn(1, 100) ?: 1)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Subtotal del producto", color = Muted, fontSize = 9.sp)
                                Text(formatCop(lineTotal), color = Navy, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    TextButton(enabled = chargeLines.size < 50, onClick = { chargeLines = chargeLines + ChargeDraftLine() }, modifier = Modifier.align(Alignment.Start)) { Text("＋ Agregar producto") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Total exacto", color = Muted, fontSize = 11.sp)
                        Text(formatCop(amount), color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        (listOf("NEQUI", "DAVIPLATA", "BRE_B") + if (configuredPaymentMethods.any { it.provider == "OTHER" }) listOf("OTHER") else emptyList()).forEach { provider ->
                            val method = configuredPaymentMethods.find { it.provider == provider }
                            val walletInstalled = isWalletProviderAvailable(provider, installedWalletApps)
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (selectedPaymentProvider == provider) Color(0xFFEAF2FF) else Color(0xFFF5F7FA))
                                    .clickable(enabled = method != null && walletInstalled) { selectedPaymentProvider = provider }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                paymentProviderLogo(provider)?.let { Image(painterResource(it), contentDescription = paymentProviderNames[provider], modifier = Modifier.size(27.dp)) }
                                    ?: Icon(Icons.Default.QrCode2, contentDescription = paymentProviderNames[provider], tint = Muted, modifier = Modifier.size(27.dp))
                                Text(paymentProviderNames[provider].orEmpty(), color = if (method != null && walletInstalled) Navy else Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                if (!walletInstalled) Text("No instalada", color = Muted, fontSize = 7.sp)
                                else if (method == null) Text("QR sin configurar", color = Muted, fontSize = 7.sp)
                            }
                        }
                    }
                    if (installedWalletApps.none { it == NEQUI_PACKAGE || it == DAVIPLATA_PACKAGE }) {
                        Text("Instala Nequi o DaviPlata para habilitar estas billeteras y Bre-B.", color = Muted, fontSize = 9.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("NEQUI", "DAVIPLATA", "BRE_B").forEach { provider ->
                            val currentMethod = configuredPaymentMethods.find { it.provider == provider }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                OutlinedButton(
                                    enabled = !isDemo && uploadingQrProvider.isBlank(),
                                    onClick = { pendingQrProvider = provider; qrPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 5.dp),
                                ) {
                                    if (uploadingQrProvider == provider) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    else Text(if (currentMethod == null) "Subir ${paymentProviderNames[provider]}" else "Cambiar QR ${paymentProviderNames[provider]}", fontSize = 8.sp, maxLines = 1)
                                }
                                if (currentMethod != null) AsyncImage(currentMethod.qrImageUrl, contentDescription = "QR guardado para ${paymentProviderNames[provider]}", modifier = Modifier.size(48.dp).padding(top = 3.dp))
                            }
                        }
                    }
                    if (isDemo) Text("La carga de QR está disponible al iniciar sesión con tu cuenta real.", color = Muted, fontSize = 9.sp)
                    if (qrUploadMessage.isNotBlank()) Text(qrUploadMessage, color = if (qrUploadMessage.startsWith("No se pudo")) Color(0xFFB54743) else Green, fontSize = 9.sp)
                    if (configuredPaymentMethods.isEmpty()) Text("Configura tus QR de Nequi, DaviPlata o Bre-B desde VeriPago Web.", color = Muted, fontSize = 10.sp)
                    OutlinedTextField(
                        value = customerWhatsapp,
                        onValueChange = { customerWhatsapp = it.take(20) },
                        label = { Text("WhatsApp del cliente") },
                        placeholder = { Text("+57 300 000 0000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !busy && chargeIsValid && selectedPaymentMethod != null,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    if (isDemo) {
                                        val method = selectedPaymentMethod ?: error("Selecciona una billetera de prueba.")
                                        val orderCode = "VP-DEMO-${System.currentTimeMillis().toString().takeLast(6)}"
                                        onDemoOrdersChange(listOf(
                                            OrderEntity(
                                                orderId = "demo-${System.currentTimeMillis()}",
                                                orderCode = orderCode,
                                                merchantId = DEMO_MERCHANT_UID,
                                                customerUid = null,
                                                customerName = "Pedido de demostración",
                                                customerPhone = "",
                                                itemsJson = serializeChargeItems(chargeLines),
                                                totalAmount = amount,
                                                checkoutChannel = "DEMO",
                                                paymentStatus = "PENDING",
                                                deliveryStatus = "PENDING",
                                                matchedNotificationId = null,
                                                createdAt = System.currentTimeMillis(),
                                                updatedAt = System.currentTimeMillis(),
                                            ),
                                        ) + demoOrders)
                                        qrState = Triple(method, orderCode, amount)
                                    } else {
                                        val result = createInSituOrder(profile, chargeLines, selectedPaymentProvider)
                                        qrState = Triple(result.first, result.second, result.third)
                                    }
                                } catch (exception: Exception) { onError(exception.localizedMessage ?: "No se pudo crear el cobro.") }
                                finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(47.dp), shape = RoundedCornerShape(11.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White)
                        else { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(7.dp)); Text("Mostrar QR en mostrador") }
                    }
                    OutlinedButton(
                        enabled = !busy && chargeIsValid,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    if (isDemo) {
                                        val name = chargeLines.firstOrNull()?.name?.ifBlank { "Pedido de prueba" } ?: "Pedido de prueba"
                                        val demoLink = "https://demo.veripago.local/${System.currentTimeMillis().toString().takeLast(6)}"
                                        demoSharePreview = "Para: ${customerWhatsapp.ifBlank { "número del cliente" }}\nProductos: ${chargeLines.joinToString { "${it.name.ifBlank { "Producto" }} × ${it.quantityText}" }}\nTotal: ${formatCop(amount)}\nContacto de comercio: solo se configura en el perfil real.\n\nHola, completa tu pedido de prueba: $demoLink\n\nEste enlace es demostrativo y no recibe pagos."
                                    } else {
                                        val customerPhone = normalizeWhatsappNumber(customerWhatsapp)
                                            ?: error("Ingresa un WhatsApp de cliente válido, por ejemplo +57 300 000 0000.")
                                        if (normalizeWhatsappNumber(merchantWhatsapp) == null) error("Guarda primero el WhatsApp del comercio en Perfil.")
                                        val url = createShareLink(profile, chargeLines)
                                        val share = "Hola, completa tu pedido. Total: ${formatCop(amount)}. $url"
                                        openWhatsAppShare(context, customerPhone, share)
                                    }
                                } catch (exception: Exception) { onError(exception.localizedMessage ?: "No se pudo crear el enlace.") }
                                finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(45.dp), shape = RoundedCornerShape(11.dp),
                    ) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(7.dp)); Text("Compartir por WhatsApp") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Tu dinero llega directo a tu billetera. 0% comisión.", color = Muted, fontSize = 9.sp) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Catálogo de productos", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Privado de tu comercio. El pedido usa el precio final con IVA cuando aplique.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    OutlinedTextField(catalogName, { catalogName = it.take(100) }, label = { Text("Nombre del producto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(catalogPrice, { catalogPrice = it.filter(Char::isDigit).take(10) }, label = { Text("Precio ingresado (COP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(catalogVatRate, { catalogVatRate = it.filter { char -> char.isDigit() || char == '.' || char == ',' }.take(6) }, label = { Text("IVA (%) · opcional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = catalogPriceIncludesVat, onCheckedChange = { catalogPriceIncludesVat = it })
                        Text("El precio ingresado ya incluye IVA", color = Navy, fontSize = 10.sp)
                    }
                    Text("Sin IVA: ${formatCop(catalogPricing.beforeVat)}   ·   IVA: ${formatCop(catalogPricing.vatAmount)}   ·   Con IVA: ${formatCop(catalogPricing.withVat)}", color = Navy, fontSize = 9.sp, lineHeight = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !savingProduct && catalogName.isNotBlank() && catalogEnteredPrice > 0 && catalogVat != null,
                            onClick = {
                                scope.launch {
                                    savingProduct = true; catalogMessage = ""
                                    runCatching {
                                        val rate = catalogVat ?: error("El IVA debe ser un porcentaje entre 0 y 100.")
                                        val priceBreakdown = calculateProductPricing(catalogEnteredPrice, rate, catalogPriceIncludesVat)
                                        val draft = MerchantProduct(
                                            id = editingProductId,
                                            name = catalogName.trim(),
                                            enteredPrice = catalogEnteredPrice,
                                            vatRate = rate,
                                            priceIncludesVat = catalogPriceIncludesVat,
                                            priceWithoutVat = priceBreakdown.beforeVat,
                                            vatAmount = priceBreakdown.vatAmount,
                                            priceWithVat = priceBreakdown.withVat,
                                        )
                                        if (isDemo) {
                                            val saved = draft.copy(id = draft.id.ifBlank { "demo-${java.util.UUID.randomUUID()}" })
                                            catalogProducts = (catalogProducts.filterNot { it.id == saved.id } + saved).sortedBy { it.name.lowercase() }
                                        } else {
                                            productRepository.save(profile.uid, draft)
                                            catalogProducts = productRepository.load(profile.uid)
                                        }
                                    }.onSuccess {
                                        catalogName = ""; catalogPrice = ""; catalogVatRate = ""; catalogPriceIncludesVat = false; editingProductId = ""
                                        catalogMessage = "Producto guardado. Solo tú puedes ver este catálogo."
                                    }.onFailure { catalogMessage = it.localizedMessage ?: "No se pudo guardar el producto." }
                                    savingProduct = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (savingProduct) "Guardando…" else if (editingProductId.isBlank()) "Agregar producto" else "Guardar cambios", fontSize = 10.sp) }
                        if (editingProductId.isNotBlank()) TextButton(onClick = { editingProductId = ""; catalogName = ""; catalogPrice = ""; catalogVatRate = ""; catalogPriceIncludesVat = false }) { Text("Cancelar") }
                    }
                    if (catalogMessage.isNotBlank()) Text(catalogMessage, color = if (catalogMessage.startsWith("No se pudo") || catalogMessage.startsWith("El IVA")) Color(0xFFB54743) else Green, fontSize = 9.sp)
                    if (isDemo) Text("Modo de demostración: los productos solo viven en esta sesión.", color = Color(0xFFB54743), fontSize = 9.sp)
                    if (catalogProducts.isEmpty()) Text("Aún no agregas productos.", color = Muted, fontSize = 10.sp)
                    catalogProducts.forEach { product ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC)), shape = RoundedCornerShape(11.dp)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(product.name, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("IVA ${product.vatRate}% · Sin IVA ${formatCop(product.priceWithoutVat)} · IVA ${formatCop(product.vatAmount)} · Total ${formatCop(product.priceWithVat)}", color = Muted, fontSize = 9.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        editingProductId = product.id; catalogName = product.name; catalogPrice = product.enteredPrice.toString()
                                        catalogVatRate = if (product.vatRate == 0.0) "" else product.vatRate.toString()
                                        catalogPriceIncludesVat = product.priceIncludesVat
                                    }) { Text("Editar", fontSize = 9.sp) }
                                    TextButton(enabled = !savingProduct, onClick = {
                                        scope.launch {
                                            savingProduct = true
                                            runCatching {
                                                if (isDemo) catalogProducts = catalogProducts.filterNot { it.id == product.id }
                                                else { productRepository.delete(profile.uid, product.id); catalogProducts = productRepository.load(profile.uid) }
                                            }.onFailure { catalogMessage = it.localizedMessage ?: "No se pudo eliminar el producto." }
                                            savingProduct = false
                                        }
                                    }) { Text("Eliminar", color = Color(0xFFB54743), fontSize = 9.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsActive, null, tint = Green); Spacer(Modifier.width(8.dp)); Text("Pagos recibidos", color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    Spacer(Modifier.height(6.dp))
                    val visible = visibleOrders.take(4)
                    if (visible.isEmpty()) Text("Tus pedidos aparecerán aquí al sincronizar.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 14.dp))
                    visible.forEach { order -> CompactOrder(order) }
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }

    qrState?.let { (method, orderCode, total) ->
        AlertDialog(
            onDismissRequest = { qrState = null },
            title = { Text("Escanea y paga", color = Navy, fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (isDemo) {
                        Box(Modifier.size(205.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEAF2FF)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.QrCode2, contentDescription = null, tint = Color(0xFF3475D3), modifier = Modifier.size(84.dp))
                                Text("DEMO · NO PAGAR", color = Color(0xFFB54743), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    else if (method.qrImageUrl.isNotBlank()) AsyncImage(method.qrImageUrl, contentDescription = "QR de ${paymentProviderNames[method.provider] ?: "pago"}", modifier = Modifier.size(205.dp).clip(RoundedCornerShape(10.dp)))
                    else Text("Configura el QR de tu comercio desde la web para mostrarlo aquí.", color = Muted, textAlign = TextAlign.Center, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp)); Text("${paymentProviderNames[method.provider] ?: method.provider} · ${method.paymentKeyLabel}", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(9.dp)); Text(orderCode, color = Muted, fontSize = 11.sp); Text(formatCop(total), color = Navy, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                    Spacer(Modifier.height(6.dp)); Text(if (isDemo) "Simulación local: no se crea un cobro real." else "Esperando confirmación del pago…", color = if (isDemo) Muted else Green, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = { qrState = null }) { Text("Listo") } },
        )
    }

    demoSharePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { demoSharePreview = null },
            title = { Text("Vista previa para WhatsApp", color = Navy, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preview, color = Muted, fontSize = 12.sp)
                Text("Modo demo: no se envía el mensaje ni se crea un enlace de pago.", color = Color(0xFFB54743), fontSize = 10.sp)
            } },
            confirmButton = { TextButton(onClick = { demoSharePreview = null }) { Text("Cerrar") } },
        )
    }
}

private fun parsePaymentMethods(link: DocumentSnapshot): List<AndroidPaymentMethod> {
    val methods = (link.get("paymentMethods") as? List<*>)?.mapNotNull { item ->
        val value = item as? Map<*, *> ?: return@mapNotNull null
        val provider = value["provider"] as? String ?: return@mapNotNull null
        if (provider !in paymentProviderNames.keys) return@mapNotNull null
        val qr = value["qrImageUrl"] as? String ?: return@mapNotNull null
        if (qr.isBlank()) return@mapNotNull null
        AndroidPaymentMethod(provider, qr, value["paymentKeyLabel"] as? String ?: "", value["beneficiaryName"] as? String ?: "")
    }.orEmpty()
    if (methods.isNotEmpty()) return methods
    val legacyQr = link.getString("qrImageUrl").orEmpty()
    if (legacyQr.isBlank()) return emptyList()
    val legacyProvider = link.getString("paymentProvider")?.takeIf { it in paymentProviderNames.keys } ?: "OTHER"
    return listOf(AndroidPaymentMethod(legacyProvider, legacyQr, link.getString("paymentKeyLabel").orEmpty(), link.getString("beneficiaryName").orEmpty()))
}

private suspend fun createInSituOrder(profile: UserProfile, lines: List<ChargeDraftLine>, provider: String): Triple<AndroidPaymentMethod, String, Long> = withContext(Dispatchers.IO) {
    val firestore = FirebaseClients.firestore ?: error("Configura Firebase para crear cobros.")
    val functions = FirebaseClients.functions ?: error("Cloud Functions no está disponible.")
    val links = firestore.collection("payment_links").whereEqualTo("merchantId", profile.uid)
        .whereEqualTo("defaultMode", "IN_SITU_QR").whereEqualTo("isActive", true).limit(1).get().await()
    val link = links.documents.firstOrNull() ?: error("Configura tu QR de pago desde VeriPago Web antes del primer cobro.")
    val paymentMethod = parsePaymentMethods(link).find { it.provider == provider }
        ?: error("Configura un QR activo para ${paymentProviderNames[provider] ?: "ese medio de pago"} desde VeriPago Web.")
    val products = lines.map { line ->
        mapOf("id" to line.id, "name" to line.name.trim(), "unitPrice" to line.unitPriceText.toLong(), "defaultQty" to line.quantityText.toInt())
    }
    functions.getHttpsCallable("updatePaymentLink").call(mapOf(
        "id" to link.id,
        "schemaJson" to mapOf("products" to products, "captureCustomerInfo" to false),
        "paymentMethods" to parsePaymentMethods(link).map { method -> mapOf(
            "provider" to method.provider,
            "qrImageUrl" to method.qrImageUrl,
            "paymentKeyLabel" to method.paymentKeyLabel,
            "beneficiaryName" to method.beneficiaryName,
        ) },
    )).await()
    val result = functions.getHttpsCallable("createCheckoutOrder").call(mapOf(
        "linkId" to link.id,
        "items" to lines.map { line -> mapOf("productId" to line.id, "qty" to line.quantityText.toInt()) },
        "customerName" to "Cliente en mostrador",
        "customerPhone" to "",
        "checkoutChannel" to "MOBILE_IN_SITU_QR",
        "paymentProvider" to paymentMethod.provider,
    )).await().data as Map<*, *>
    Triple(paymentMethod, result["orderCode"] as? String ?: "Pedido VeriPago", lines.sumOf { it.unitPriceText.toLong() * it.quantityText.toInt() })
}

private suspend fun createShareLink(profile: UserProfile, lines: List<ChargeDraftLine>): String = withContext(Dispatchers.IO) {
    val firestore = FirebaseClients.firestore ?: error("Configura Firebase para compartir pedidos.")
    val functions = FirebaseClients.functions ?: error("Cloud Functions no está disponible.")
    val qrLink = firestore.collection("payment_links").whereEqualTo("merchantId", profile.uid)
        .whereEqualTo("defaultMode", "IN_SITU_QR").whereEqualTo("isActive", true).limit(1).get().await().documents.firstOrNull()
        ?: error("Configura primero tu QR de pago desde VeriPago Web.")
    val productNames = lines.map { it.name.trim() }
    val title = if (productNames.size == 1) productNames.first() else "${productNames.first()} + ${productNames.size - 1} productos"
    val slug = "pedido-${java.util.UUID.randomUUID().toString().take(10)}"
    val result = functions.getHttpsCallable("createPaymentLink").call(mapOf(
        "slug" to slug,
        "title" to title.take(100),
        "defaultMode" to "WHATSAPP_LINK",
        "schemaJson" to mapOf("products" to lines.map { line ->
            mapOf("id" to line.id, "name" to line.name.trim(), "unitPrice" to line.unitPriceText.toLong(), "defaultQty" to line.quantityText.toInt())
        }, "captureCustomerInfo" to true),
        "paymentMethods" to parsePaymentMethods(qrLink).map { method -> mapOf(
            "provider" to method.provider,
            "qrImageUrl" to method.qrImageUrl,
            "paymentKeyLabel" to method.paymentKeyLabel,
            "beneficiaryName" to method.beneficiaryName,
        ) },
    )).await().data as Map<*, *>
        "https://veripago-9cda6.web.app/VeriPago/${result["slug"]}"
}

private fun serializeChargeItems(lines: List<ChargeDraftLine>): String = lines.joinToString(prefix = "[", postfix = "]") { line ->
    val safeName = line.name.trim().replace("\\", "\\\\").replace("\"", "\\\"")
    val quantity = line.quantityText.toIntOrNull()?.coerceIn(1, 100) ?: 1
    val unitPrice = line.unitPriceText.toLongOrNull() ?: 0L
    "{\"name\":\"$safeName\",\"qty\":$quantity,\"unitPrice\":$unitPrice,\"subtotal\":${unitPrice * quantity}}"
}

private fun normalizeWhatsappNumber(raw: String): String? {
    val digits = raw.filter(Char::isDigit)
    val international = when {
        digits.length == 10 && digits.startsWith('3') -> "57$digits"
        digits.length in 11..15 -> digits
        else -> return null
    }
    return "+$international"
}

private const val NEQUI_PACKAGE = "com.nequi.MobileApp"
private const val DAVIPLATA_PACKAGE = "com.davivienda.daviplataapp"

private fun detectInstalledWalletApps(context: android.content.Context): Set<String> =
    setOf(NEQUI_PACKAGE, DAVIPLATA_PACKAGE).filterTo(mutableSetOf()) { packageName ->
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

private fun isWalletProviderAvailable(provider: String, installedApps: Set<String>): Boolean = when (provider) {
    "NEQUI" -> NEQUI_PACKAGE in installedApps
    "DAVIPLATA" -> DAVIPLATA_PACKAGE in installedApps
    // Bre-B is a transfer rail available inside supported wallet apps, not a standalone app.
    "BRE_B" -> NEQUI_PACKAGE in installedApps || DAVIPLATA_PACKAGE in installedApps
    else -> true
}

private suspend fun uploadAndSaveWalletQr(
    context: android.content.Context,
    profile: UserProfile,
    provider: String,
    uri: Uri,
): List<AndroidPaymentMethod> = withContext(Dispatchers.IO) {
    val contentType = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        ?.takeIf { it in setOf("image/png", "image/jpeg", "image/webp") }
        ?: error("El QR debe ser una imagen PNG, JPEG o WebP.")
    val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
    if (size >= 2L * 1024 * 1024) error("La imagen del QR debe pesar menos de 2 MB.")
    val storage = FirebaseClients.storage ?: error("Firebase Storage no está disponible.")
    val functions = FirebaseClients.functions ?: error("Cloud Functions no está disponible.")
    val filename = "qr_codes/${profile.uid}/${provider.lowercase(Locale.ROOT)}-${java.util.UUID.randomUUID()}"
    val location = storage.reference.child(filename)
    val metadata = com.google.firebase.storage.StorageMetadata.Builder().setContentType(contentType).build()
    location.putFile(uri, metadata).await()
    val qrUrl = location.downloadUrl.await().toString()

    val firestore = FirebaseClients.firestore ?: error("Firestore no está disponible.")
    val link = firestore.collection("payment_links").whereEqualTo("merchantId", profile.uid)
        .whereEqualTo("defaultMode", "IN_SITU_QR").whereEqualTo("isActive", true).limit(1).get().await().documents.firstOrNull()
    val existingMethods = link?.let(::parsePaymentMethods).orEmpty()
    val prior = existingMethods.find { it.provider == provider }
    val method = AndroidPaymentMethod(
        provider = provider,
        qrImageUrl = qrUrl,
        paymentKeyLabel = prior?.paymentKeyLabel?.ifBlank { "QR ${paymentProviderNames[provider]}" } ?: "QR ${paymentProviderNames[provider]}",
        beneficiaryName = prior?.beneficiaryName?.ifBlank { profile.displayName } ?: profile.displayName,
    )
    val retainedMethods = existingMethods.filterNot { it.provider == provider }.toMutableList()
    if (retainedMethods.size >= 3) {
        val otherIndex = retainedMethods.indexOfFirst { it.provider == "OTHER" }
        if (otherIndex >= 0) retainedMethods.removeAt(otherIndex)
        else error("Ya configuraste tres billeteras. Cambia uno de esos QR para continuar.")
    }
    val methods = retainedMethods + method
    val methodsPayload = methods.map { item ->
        mapOf("provider" to item.provider, "qrImageUrl" to item.qrImageUrl, "paymentKeyLabel" to item.paymentKeyLabel, "beneficiaryName" to item.beneficiaryName)
    }
    val result: Map<*, *>
    if (link == null) {
        result = functions.getHttpsCallable("createPaymentLink").call(mapOf(
            "slug" to "android-cobro-${java.util.UUID.randomUUID().toString().take(8)}",
            "title" to "Cobro rápido",
            "defaultMode" to "IN_SITU_QR",
            "schemaJson" to mapOf("products" to listOf(mapOf("id" to "android-quick-item", "name" to "Cobro rápido", "unitPrice" to 0)), "captureCustomerInfo" to false),
            "paymentMethods" to methodsPayload,
        )).await().data as Map<*, *>
    } else {
        val schema = link.get("schemaJson") as? Map<*, *> ?: error("El enlace de cobro no tiene una configuración válida.")
        val products = schema["products"] as? List<*> ?: error("Agrega un producto o pedido desde el espacio web antes de configurar el QR.")
        result = functions.getHttpsCallable("updatePaymentLink").call(mapOf(
            "id" to link.id,
            "schemaJson" to mapOf("products" to products, "captureCustomerInfo" to (schema["captureCustomerInfo"] as? Boolean ?: false)),
            "paymentMethods" to methodsPayload,
        )).await().data as? Map<*, *> ?: mapOf("id" to link.id)
    }
    if ((result["id"] as? String).isNullOrBlank() && link == null) error("No se pudo guardar la configuración del QR.")
    methods
}

private fun openWhatsAppShare(context: android.content.Context, phone: String, message: String) {
    val uri = Uri.parse("https://wa.me/${phone.filter(Char::isDigit)}?text=${Uri.encode(message)}")
    val installedPackages = listOf("com.whatsapp", "com.whatsapp.w4b").filter { packageName ->
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }
    if (installedPackages.isEmpty()) error("Instala WhatsApp o WhatsApp Business para compartir el enlace.")
    val targets = installedPackages.map { packageName ->
        Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)
    }
    if (targets.size == 1) {
        context.startActivity(targets.single())
    } else {
        val chooser = Intent.createChooser(targets.first(), "Enviar con WhatsApp")
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        context.startActivity(chooser)
    }
}

@Composable
private fun OrdersScreen(profile: UserProfile, demoOrders: List<OrderEntity>, onDemoOrdersChange: (List<OrderEntity>) -> Unit) {
    val isDemo = isDemoMerchant(profile)
    val context = LocalContext.current
    val ordersFlow = if (profile.role == "COMERCIANTE" && !isDemo) AppGraph.payments.observeOrders(profile.uid) else kotlinx.coroutines.flow.flowOf(emptyList<OrderEntity>())
    val observedOrders by ordersFlow.collectAsState(initial = emptyList())
    val orders = if (isDemo) demoOrders else observedOrders
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(18.dp)) {
        if (isDemo) DemoNotice()
        Text("PEDIDOS Y ENTREGAS", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp)); Text("Trazabilidad", color = Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(13.dp))
        if (orders.isEmpty()) EmptyCard("Tus pedidos aparecerán aquí al sincronizar con Firestore.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(orders, key = { it.orderId }) { order ->
                OrderCard(order, onDelivered = if (profile.role == "COMERCIANTE") ({
                    if (isDemo) onDemoOrdersChange(demoOrders.map { if (it.orderId == order.orderId) it.copy(deliveryStatus = "DELIVERED") else it })
                    else scope.launch { AppGraph.payments.markDelivered(order.orderId); PendingSyncScheduler.request(context) }
                }) else null)
            }
        }
    }
}

@Composable
private fun ListenerStatusScreen(isDemo: Boolean = false) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        allowed = listeners.contains(context.packageName)
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isDemo) DemoNotice()
        Text("CELULAR RECEPTOR", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
        Text("Confirmación bancaria", color = Navy, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsActive, null, tint = if (allowed) Green else Color(0xFFB77C36)); Spacer(Modifier.width(10.dp)); Column { Text(if (allowed) "Permiso activado" else "Permiso pendiente", color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text("Acceso a notificaciones de pago", color = Muted, fontSize = 10.sp) } }
                Text("VeriPago solo lee el texto de las notificaciones de abonos. Nunca solicita claves ni acceso dentro de tu banco.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                if (isDemo) Text("La demostración no solicita ni modifica permisos del teléfono.", color = Muted, fontSize = 10.sp)
                else {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text(if (allowed) "Administrar permiso" else "Activar escucha bancaria") }
                    if (allowed) OutlinedButton(onClick = { startPaymentListener(context) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(6.dp)); Text("Reanudar escucha") }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Billeteras compatibles", color = Navy, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Nequi · Daviplata · Bre-B", color = Muted, fontSize = 11.sp)
                Text("Las reglas de lectura llegan desde Firestore y quedan disponibles sin conexión.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun DemoNotice() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5E6)), shape = RoundedCornerShape(12.dp)) {
        Text(
            "PERFIL DE DEMOSTRACIÓN · No guarda datos ni procesa pagos reales.",
            color = Color(0xFF855E23),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        )
    }
}

@Composable
private fun AdminScreen() {
    val repository = remember { AdminRepository() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val voiceConfirmation = remember(context) { VoiceConfirmationEngine(context) }
    DisposableEffect(voiceConfirmation) { onDispose { voiceConfirmation.release() } }
    var overview by remember { mutableStateOf<AdminOverview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    fun refresh() {
        scope.launch {
            loading = true
            error = ""
            runCatching { repository.loadOverview() }
                .onSuccess { overview = it }
                .onFailure { error = it.localizedMessage ?: "No se pudo cargar el resumen." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp, bottom = 2.dp)) {
                Text("CONSOLA DE PLATAFORMA", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("Todo conectado.", color = Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Monitorea la operación de VeriPago.", color = Muted, fontSize = 11.sp)
            }
        }
        if (error.isNotBlank()) item { AdminInlineError(error) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniMetric("Volumen verificado", formatCop(overview?.verifiedVolume ?: 0L), Green, Modifier.weight(1f))
                MiniMetric("Pagos conciliados", "${overview?.reconciledPayments ?: 0}", Color(0xFF3473CC), Modifier.weight(1f))
            }
        }
        item {
            MiniMetric("Comercios registrados", "${overview?.merchantsRegistered ?: 0}", Color(0xFF8057D9), Modifier.fillMaxWidth())
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, null, tint = Color(0xFF3475D3))
                        Spacer(Modifier.width(8.dp))
                        Text("Prueba de texto a voz", color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Escucha cómo suena el aviso de VeriPago. Es una simulación, no un pago real.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    Button(onClick = { voiceConfirmation.announceTest() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Probar texto a voz")
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Actividad reciente", color = Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Operación global de VeriPago.", color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = { refresh() }) { Text("Actualizar", fontSize = 11.sp) }
            }
        }
        if (loading && overview == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        val recentOrders = overview?.orders.orEmpty()
        if (!loading && recentOrders.isEmpty()) item { EmptyCard("Todavía no hay pedidos registrados.") }
        items(recentOrders, key = { it.id }) { order -> AdminOrderRow(order) }
    }
}

@Composable
private fun AdminRulesScreen() {
    val repository = remember { AdminRepository() }
    val scope = rememberCoroutineScope()
    var sampleText by rememberSaveable { mutableStateOf("Te llegaron $15.000 de Maria Perez") }
    var amountRegex by rememberSaveable { mutableStateOf("(?i)(?:lleg(?:ó|o|aron)|recibiste)\\s*\\$?\\s*([0-9][0-9.,]*)") }
    var senderRegex by rememberSaveable { mutableStateOf("(?i)(?:de|remitente)\\s*[:\\-]?\\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑa-záéíóúñ ]{2,60})") }
    var parsedText by remember { mutableStateOf<String?>(null) }
    var rules by remember { mutableStateOf<List<AdminRule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.loadRules() }
                .onSuccess { rules = it }
                .onFailure { error = it.localizedMessage ?: "No se pudieron cargar las reglas." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp, bottom = 2.dp)) {
                Text("REGLAS REGEX", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp)); Text("Reglas bancarias", color = Navy, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Prueba las expresiones antes de sincronizarlas.", color = Muted, fontSize = 11.sp)
            }
        }
        if (error.isNotBlank()) item { AdminInlineError(error) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(sampleText, { sampleText = it }, label = { Text("Texto de notificación") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(amountRegex, { amountRegex = it }, label = { Text("Expresión para monto") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(senderRegex, { senderRegex = it }, label = { Text("Expresión para remitente") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            parsedText = try {
                                Regex(amountRegex); Regex(senderRegex)
                                val rule = BankRuleEntity("preview", "Personalizada", "", amountRegex, senderRegex, true, 0L)
                                val parsed = BankNotificationParser.parse(sampleText, rule)
                                if (parsed == null) "Sin coincidencia" else "Monto: ${formatCop(parsed.amount)} · Remitente: ${parsed.sender.ifBlank { "Sin coincidencia" }}"
                            } catch (_: IllegalArgumentException) { "Regex inválida" }
                        }, modifier = Modifier.weight(1f)) { Text("Probar reglas", fontSize = 11.sp) }
                        Button(enabled = !saving, onClick = {
                            scope.launch {
                                saving = true; error = ""
                                runCatching { repository.saveDisabledRule(amountRegex, senderRegex) }
                                    .onSuccess { parsedText = "Regla guardada desactivada."; refresh() }
                                    .onFailure { error = it.localizedMessage ?: "No se pudo guardar la regla." }
                                saving = false
                            }
                        }, modifier = Modifier.weight(1f)) { Text(if (saving) "Guardando…" else "Guardar desactivada", fontSize = 10.sp) }
                    }
                    parsedText?.let { Text(it, color = if (it == "Regex inválida") Color(0xFFB54747) else Navy, fontSize = 11.sp) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Reglas guardadas", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { refresh() }) { Text("Actualizar", fontSize = 11.sp) }
            }
        }
        if (loading && rules.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (!loading && rules.isEmpty()) item { EmptyCard("Todavía no hay reglas bancarias guardadas.") }
        items(rules, key = { it.id }) { rule ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(13.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (rule.isActive) Green else Muted))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rule.walletName.ifBlank { "Personalizada" }, color = Navy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(rule.androidPackage.ifBlank { "Paquete por configurar" }, color = Muted, fontSize = 10.sp)
                    }
                    Text(if (rule.isActive) "Activa" else "Desactivada", color = if (rule.isActive) Green else Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun AdminUsersScreen(currentUser: UserProfile) {
    val repository = remember { AdminRepository() }
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var savingUid by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    fun refresh() {
        scope.launch {
            loading = true; error = ""
            runCatching { repository.loadUsers() }
                .onSuccess { users = it }
                .onFailure { error = it.localizedMessage ?: "No se pudieron cargar los usuarios." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("USUARIOS", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp)); Text("Perfiles registrados", color = Navy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Asigna el rol correcto para cada cuenta.", color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = { refresh() }) { Text("Actualizar", fontSize = 11.sp) }
            }
        }
        if (error.isNotBlank()) item { AdminInlineError(error) }
        if (loading && users.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (!loading && users.isEmpty()) item { EmptyCard("No hay perfiles registrados.") }
        items(users, key = { it.uid }) { user ->
            AdminUserCard(user, currentUser.uid, savingUid == user.uid) { role ->
                scope.launch {
                    savingUid = user.uid; error = ""
                    runCatching { repository.updateRole(user.uid, role) }
                        .onSuccess { refresh() }
                        .onFailure { error = it.localizedMessage ?: "No se pudo actualizar el rol." }
                    savingUid = null
                }
            }
        }
    }
}

@Composable
private fun AdminUserCard(user: UserProfile, currentUid: String, saving: Boolean, onRoleChange: (String) -> Unit) {
    var expanded by remember(user.uid) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (user.photoUrl.isNotBlank()) {
                AsyncImage(user.photoUrl, contentDescription = "Foto de ${user.displayName}", modifier = Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFEAF2FF)), contentAlignment = Alignment.Center) {
                    Text(user.displayName.firstOrNull()?.uppercase() ?: "G", color = Navy, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(user.displayName.ifBlank { "Usuario VeriPago" }, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(user.email, color = Muted, fontSize = 9.sp, maxLines = 1)
            }
            Box {
                TextButton(enabled = currentUid != user.uid && !saving, onClick = { expanded = true }) {
                    Text(if (saving) "Guardando…" else if (user.role == "ADMINISTRADOR") "Administrador" else "Comerciante", fontSize = 9.sp, maxLines = 1)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("COMERCIANTE" to "Comerciante", "ADMINISTRADOR" to "Administrador").forEach { (role, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onRoleChange(role) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminPlatformScreen() {
    val repository = remember { AdminRepository() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var release by remember { mutableStateOf<PlatformRelease?>(null) }
    var selectedApk by remember { mutableStateOf<Uri?>(null) }
    var version by rememberSaveable { mutableStateOf(BuildConfig.VERSION_NAME) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selectedApk = it }
    fun refresh() {
        scope.launch {
            loading = true; error = ""
            runCatching { repository.loadPlatformRelease() }
                .onSuccess { release = it }
                .onFailure { error = it.localizedMessage ?: "No se pudo cargar la versión publicada." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp, bottom = 2.dp)) {
                Text("PLATAFORMA", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp)); Text("App Android", color = Navy, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Actualiza el APK ofrecido para descarga.", color = Muted, fontSize = 11.sp)
            }
        }
        if (error.isNotBlank()) item { AdminInlineError(error) }
        if (message.isNotBlank()) item { Text(message, color = Green, fontSize = 11.sp) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("Versión publicada", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    else if (release == null || release?.url.isNullOrBlank()) Text("No hay una versión publicada.", color = Muted, fontSize = 11.sp)
                    else {
                        Text("${release?.version.orEmpty()} · ${release?.notes.orEmpty()}", color = Muted, fontSize = 11.sp)
                        Text("El archivo ya está disponible en Storage.", color = Green, fontSize = 10.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("Publicar app Android", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Selecciona el APK y confirma la versión que se mostrará en la web.", color = Muted, fontSize = 10.sp)
                    OutlinedTextField(version, { version = it }, label = { Text("Versión") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedApk?.lastPathSegment?.substringAfterLast(':') ?: "Seleccionar archivo APK", maxLines = 1)
                    }
                    Button(enabled = !saving && selectedApk != null, onClick = {
                        val uri = selectedApk ?: return@Button
                        scope.launch {
                            saving = true; error = ""; message = ""
                            runCatching { repository.publishApk(context, uri, version) }
                                .onSuccess { release = it; selectedApk = null; message = "La versión quedó publicada." }
                                .onFailure { error = it.localizedMessage ?: "No se pudo publicar el APK." }
                            saving = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (saving) "Publicando…" else "Publicar versión")
                    }
                    Text("El APK estará disponible públicamente desde Storage.", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun AdminOrderRow(order: AdminOrderItem) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(13.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(order.customerName.ifBlank { "Pedido ${order.orderCode}" }, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${order.orderCode} · ${order.paymentStatus.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}", color = Muted, fontSize = 9.sp, maxLines = 1)
            }
            Text(formatCop(order.totalAmount), color = Navy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AdminInlineError(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0F0)), shape = RoundedCornerShape(12.dp)) {
        Text(message, Modifier.fillMaxWidth().padding(12.dp), color = Color(0xFFB54747), fontSize = 11.sp)
    }
}

@Composable
private fun MiniMetric(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) { Text(label, color = Muted, fontSize = 9.sp); Spacer(Modifier.height(5.dp)); Text(value, color = tone, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun CompactOrder(order: OrderEntity) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (order.paymentStatus.startsWith("VERIFIED")) Color(0xFFE8F7F0) else Color(0xFFFFF4E3)), contentAlignment = Alignment.Center) {
            Icon(if (order.paymentStatus.startsWith("VERIFIED")) Icons.Default.CheckCircle else Icons.Default.QrCode2, null, tint = if (order.paymentStatus.startsWith("VERIFIED")) Green else Color(0xFFAA7A31), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(order.customerName.ifBlank { "Cliente" }, color = Navy, fontSize = 11.sp, fontWeight = FontWeight.SemiBold); Text(order.orderCode, color = Muted, fontSize = 9.sp) }
        Column(horizontalAlignment = Alignment.End) { Text(formatCop(order.totalAmount), color = Navy, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(if (order.paymentStatus.startsWith("VERIFIED")) "Verificado" else "Pendiente", color = if (order.paymentStatus.startsWith("VERIFIED")) Green else Color(0xFFAA7A31), fontSize = 8.sp) }
    }
    Divider(color = Color(0xFFF0F2F5))
}

@Composable
private fun OrderCard(order: OrderEntity, onDelivered: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(order.orderCode, color = Navy, fontWeight = FontWeight.Bold, fontSize = 12.sp); Text(order.customerName.ifBlank { "Cliente" }, color = Muted, fontSize = 10.sp) }; Text(formatCop(order.totalAmount), color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp)); Text(if (order.paymentStatus.startsWith("VERIFIED")) "Pago verificado" else "Pago pendiente", color = if (order.paymentStatus.startsWith("VERIFIED")) Green else Color(0xFFAA7A31), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            if (onDelivered != null && order.paymentStatus.startsWith("VERIFIED") && order.deliveryStatus != "DELIVERED") TextButton(onClick = onDelivered, modifier = Modifier.align(Alignment.End)) { Text("Marcar entregado") }
            if (order.deliveryStatus == "DELIVERED") Text("Entregado", color = Green, fontSize = 9.sp)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Muted, fontSize = 11.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(22.dp))
    }
}

private fun formatCop(value: Long): String = "${NumberFormat.getIntegerInstance(Locale("es", "CO")).format(value)} COP"

private fun startPaymentListener(context: android.content.Context) {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    if (enabled.contains(context.packageName)) {
        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, PaymentMonitorService::class.java))
    }
}
