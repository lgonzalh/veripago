package com.lantonium.veripago.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.CloudOff
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.OneTimeWorkRequestBuilder
import coil.compose.AsyncImage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.DocumentSnapshot
import com.lantonium.veripago.AppGraph
import com.lantonium.veripago.BuildConfig
import com.lantonium.veripago.R
import com.lantonium.veripago.data.FirebaseClients
import com.lantonium.veripago.data.GoogleAuthRepository
import com.lantonium.veripago.data.OrderEntity
import com.lantonium.veripago.data.PendingSyncScheduler
import com.lantonium.veripago.data.UserProfile
import com.lantonium.veripago.service.PaymentMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.text.NumberFormat

private val Navy = Color(0xFF0C2442)
private val Muted = Color(0xFF758398)
private val Green = Color(0xFF15966A)

private data class AndroidPaymentMethod(
    val provider: String,
    val qrImageUrl: String,
    val paymentKeyLabel: String,
    val beneficiaryName: String,
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
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val cached = runCatching { AppGraph.profileCache.read() }.getOrNull()
        profile = cached?.takeIf { it.role == "COMERCIANTE" || it.role == "ADMINISTRADOR" }
        if (cached != null && profile == null) AppGraph.profileCache.clear()
        val currentUser = FirebaseClients.auth?.currentUser
        if (currentUser != null && FirebaseClients.firestore != null) {
            runCatching {
                val userDoc = FirebaseClients.firestore!!.collection("users").document(currentUser.uid).get().await()
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
            }
        }
        loading = false
    }

    val credentialManager = remember { CredentialManager.create(context) }
    val signInWithGoogle: () -> Unit = {
        error = ""
        scope.launch {
            try {
                if (!FirebaseClients.isConfigured) error("Firebase aún no está configurado en este build.")
                if (BuildConfig.WEB_CLIENT_ID.isBlank()) error("Falta webClientId para Google Credential Manager en local.properties.")
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(BuildConfig.WEB_CLIENT_ID)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    error("Google no devolvió una credencial compatible.")
                }
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authRepo = GoogleAuthRepository(context, AppGraph.profileCache)
                profile = authRepo.signIn(token)
                error = ""
                snackbar.showSnackbar("Hola, ${profile?.displayName?.substringBefore(' ')}. Tu espacio está listo.")
                if (profile?.role == "COMERCIANTE") startPaymentListener(context)
            } catch (exception: GetCredentialException) {
                error = exception.localizedMessage ?: "No se pudo iniciar sesión con Google."
            } catch (exception: Exception) {
                error = exception.localizedMessage ?: "No se pudo iniciar sesión."
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
                title = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(31.dp).clip(RoundedCornerShape(10.dp)).background(Navy), contentAlignment = Alignment.Center) {
                        Text("V", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column { Text("VeriPago", color = Navy, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("ECOSISTEMA DE PAGOS", color = Muted, fontSize = 8.sp, letterSpacing = 1.2.sp) }
                } },
                actions = {
                    if (profile != null) IconButton(onClick = {
                        scope.launch { FirebaseClients.auth?.signOut(); AppGraph.profileCache.clear(); profile = null }
                    }) { Icon(Icons.Default.AccountCircle, contentDescription = "Cerrar sesión", tint = Muted) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (profile != null) {
                NavigationBar(containerColor = Color.White) {
                    val entries = when (profile?.role) {
                        "COMERCIANTE" -> listOf("inicio" to "Cobrar", "pedidos" to "Pedidos", "estado" to "Celular")
                        "ADMINISTRADOR" -> listOf("inicio" to "Resumen", "pedidos" to "Actividad")
                        else -> emptyList()
                    }
                    entries.forEach { (route, label) ->
                        val selected = currentRoute == route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigate(route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(if (route == "pedidos") Icons.Default.CheckCircle else Icons.Default.QrCode2, contentDescription = null) },
                            label = { Text(label, fontSize = 10.sp) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (profile == null) {
            LandingScreen(
                modifier = Modifier.padding(padding),
                configured = FirebaseClients.isConfigured,
                error = error,
                onLogin = signInWithGoogle,
            onRegister = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://veripago-9cda6.web.app/VeriPago/"))) },
            )
        } else {
            NavHost(navController = nav, startDestination = "inicio", modifier = Modifier.padding(padding)) {
                composable("inicio") {
                    when (profile?.role) {
                        "COMERCIANTE" -> MerchantScreen(profile!!, onError = { error = it; scope.launch { snackbar.showSnackbar(it) } })
                        "ADMINISTRADOR" -> AdminScreen()
                        else -> MerchantScreen(profile!!, onError = { error = it; scope.launch { snackbar.showSnackbar(it) } })
                    }
                }
                composable("pedidos") { OrdersScreen(profile!!) }
                composable("estado") { ListenerStatusScreen() }
            }
        }
    }

}

@Composable
private fun LandingScreen(modifier: Modifier, configured: Boolean, error: String, onLogin: () -> Unit, onRegister: () -> Unit) {
    Column(modifier.fillMaxSize().background(Color(0xFFF5F7FA))) {
        Column(Modifier.weight(1f).padding(horizontal = 24.dp).padding(top = 34.dp), verticalArrangement = Arrangement.Center) {
            Text("COBRA DIRECTO. CONFIRMA AL INSTANTE.", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(16.dp))
            Text("El pago llega.\nVeriPago lo confirma.", color = Navy, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(13.dp))
            Text("Muestra tu QR, recibe en Nequi, Daviplata o Bre-B y sigue cada pedido desde un solo lugar.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
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
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CloudOff, null, tint = Green, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Tus pedidos se conservan sin conexión", color = Muted, fontSize = 10.sp) }
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
private fun MerchantScreen(profile: UserProfile, onError: (String) -> Unit) {
    var productName by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var busy by remember { mutableStateOf(false) }
    var qrState by remember { mutableStateOf<Triple<AndroidPaymentMethod, String, Long>?>(null) }
    var configuredPaymentMethods by remember { mutableStateOf<List<AndroidPaymentMethod>>(emptyList()) }
    var selectedPaymentProvider by rememberSaveable { mutableStateOf("NEQUI") }
    var latestOrders by remember { mutableStateOf<List<OrderEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val amount = (price.toLongOrNull() ?: 0L) * (quantity.toIntOrNull()?.coerceIn(1, 100) ?: 1)
    LaunchedEffect(profile.uid) {
        val sync = AppGraph.syncManager()
        sync.start(profile.uid)
        runCatching {
            val firestore = FirebaseClients.firestore ?: return@runCatching
            val link = firestore.collection("payment_links").whereEqualTo("merchantId", profile.uid)
                .whereEqualTo("defaultMode", "IN_SITU_QR").whereEqualTo("isActive", true).limit(1).get().await().documents.firstOrNull()
            if (link != null) {
                configuredPaymentMethods = parsePaymentMethods(link)
                if (configuredPaymentMethods.none { it.provider == selectedPaymentProvider }) {
                    selectedPaymentProvider = configuredPaymentMethods.firstOrNull()?.provider ?: "NEQUI"
                }
            }
        }
        AppGraph.payments.observeOrders(profile.uid).collect { latestOrders = it }
    }
    val selectedPaymentMethod = configuredPaymentMethods.find { it.provider == selectedPaymentProvider }

    LazyColumn(Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Column(Modifier.padding(top = 14.dp, bottom = 3.dp)) {
                Text("ESPACIO COMERCIANTE", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp)); Text("Tu negocio, en orden.", color = Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Cobra sin fricción y entrega con confianza.", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniMetric("Verificados", latestOrders.count { it.paymentStatus.startsWith("VERIFIED") }.toString(), Green, Modifier.weight(1f))
                MiniMetric("Pendientes", latestOrders.count { it.paymentStatus == "PENDING" }.toString(), Color(0xFF977338), Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode2, null, tint = Color(0xFF3475D3)); Spacer(Modifier.width(8.dp))
                        Column { Text("Cobro rápido", color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text("QR inmediato o enlace por WhatsApp", color = Muted, fontSize = 10.sp) }
                    }
                    OutlinedTextField(productName, { productName = it }, label = { Text("Producto o concepto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedTextField(price, { price = it.filter(Char::isDigit).take(10) }, label = { Text("Valor (COP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit).take(3) }, label = { Text("Cant.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(90.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Total exacto", color = Muted, fontSize = 11.sp)
                        Text(formatCop(amount), color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        (listOf("NEQUI", "DAVIPLATA", "BRE_B") + if (configuredPaymentMethods.any { it.provider == "OTHER" }) listOf("OTHER") else emptyList()).forEach { provider ->
                            val method = configuredPaymentMethods.find { it.provider == provider }
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (selectedPaymentProvider == provider) Color(0xFFEAF2FF) else Color(0xFFF5F7FA))
                                    .clickable(enabled = method != null) { selectedPaymentProvider = provider }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                paymentProviderLogo(provider)?.let { Image(painterResource(it), contentDescription = paymentProviderNames[provider], modifier = Modifier.size(27.dp)) }
                                    ?: Icon(Icons.Default.QrCode2, contentDescription = paymentProviderNames[provider], tint = Muted, modifier = Modifier.size(27.dp))
                                Text(paymentProviderNames[provider].orEmpty(), color = if (method != null) Navy else Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (configuredPaymentMethods.isEmpty()) Text("Configura tus QR de Nequi, DaviPlata o Bre-B desde VeriPago Web.", color = Muted, fontSize = 10.sp)
                    Button(
                        enabled = !busy && amount > 0 && selectedPaymentMethod != null,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val result = createInSituOrder(profile, productName.ifBlank { "Pedido rápido" }, price.toLong(), quantity.toIntOrNull()?.coerceIn(1, 100) ?: 1, selectedPaymentProvider)
                                    qrState = Triple(result.first, result.second, amount)
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
                        enabled = !busy && amount > 0,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val url = createShareLink(profile, productName.ifBlank { "Pedido" }, price.toLong())
                                    val share = "Hola, completa tu pedido: $url"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=${Uri.encode(share)}")))
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
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsActive, null, tint = Green); Spacer(Modifier.width(8.dp)); Text("Pagos recibidos", color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    Spacer(Modifier.height(6.dp))
                    val visible = latestOrders.take(4)
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
                    if (method.qrImageUrl.isNotBlank()) AsyncImage(method.qrImageUrl, contentDescription = "QR de ${paymentProviderNames[method.provider] ?: "pago"}", modifier = Modifier.size(205.dp).clip(RoundedCornerShape(10.dp)))
                    else Text("Configura el QR de tu comercio desde la web para mostrarlo aquí.", color = Muted, textAlign = TextAlign.Center, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp)); Text("${paymentProviderNames[method.provider] ?: method.provider} · ${method.paymentKeyLabel}", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(9.dp)); Text(orderCode, color = Muted, fontSize = 11.sp); Text(formatCop(total), color = Navy, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                    Spacer(Modifier.height(6.dp)); Text("Esperando confirmación del pago…", color = Green, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = { qrState = null }) { Text("Listo") } },
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

private suspend fun createInSituOrder(profile: UserProfile, name: String, unitPrice: Long, quantity: Int, provider: String): Pair<AndroidPaymentMethod, String> = withContext(Dispatchers.IO) {
    val firestore = FirebaseClients.firestore ?: error("Configura Firebase para crear cobros.")
    val functions = FirebaseClients.functions ?: error("Cloud Functions no está disponible.")
    val links = firestore.collection("payment_links").whereEqualTo("merchantId", profile.uid)
        .whereEqualTo("defaultMode", "IN_SITU_QR").whereEqualTo("isActive", true).limit(1).get().await()
    val link = links.documents.firstOrNull() ?: error("Configura tu QR de pago desde VeriPago Web antes del primer cobro.")
    val paymentMethod = parsePaymentMethods(link).find { it.provider == provider }
        ?: error("Configura un QR activo para ${paymentProviderNames[provider] ?: "ese medio de pago"} desde VeriPago Web.")
    val productId = "android-quick-item"
    functions.getHttpsCallable("updatePaymentLink").call(mapOf(
        "id" to link.id,
        "schemaJson" to mapOf("products" to listOf(mapOf("id" to productId, "name" to name, "unitPrice" to unitPrice))),
        "qrImageUrl" to paymentMethod.qrImageUrl,
        "paymentKeyLabel" to paymentMethod.paymentKeyLabel,
        "beneficiaryName" to paymentMethod.beneficiaryName,
        "paymentProvider" to paymentMethod.provider,
    )).await()
    val result = functions.getHttpsCallable("createCheckoutOrder").call(mapOf(
        "linkId" to link.id,
        "items" to listOf(mapOf("productId" to productId, "qty" to quantity)),
        "customerName" to "Cliente en mostrador",
        "customerPhone" to "",
        "checkoutChannel" to "MOBILE_IN_SITU_QR",
        "paymentProvider" to paymentMethod.provider,
    )).await().data as Map<*, *>
    paymentMethod to (result["orderCode"] as? String ?: "Pedido VeriPago")
}

private suspend fun createShareLink(profile: UserProfile, name: String, price: Long): String = withContext(Dispatchers.IO) {
    val firestore = FirebaseClients.firestore ?: error("Configura Firebase para compartir pedidos.")
    val functions = FirebaseClients.functions ?: error("Cloud Functions no está disponible.")
    val qrLink = firestore.collection("payment_links").whereEqualTo("merchantId", profile.uid)
        .whereEqualTo("defaultMode", "IN_SITU_QR").whereEqualTo("isActive", true).limit(1).get().await().documents.firstOrNull()
        ?: error("Configura primero tu QR de pago desde VeriPago Web.")
    val slug = "pedido-${System.currentTimeMillis().toString(36)}"
    val result = functions.getHttpsCallable("createPaymentLink").call(mapOf(
        "slug" to slug,
        "title" to name,
        "defaultMode" to "WHATSAPP_LINK",
        "schemaJson" to mapOf("products" to listOf(mapOf("id" to "android-share-item", "name" to name, "unitPrice" to price)), "captureCustomerInfo" to true),
        "qrImageUrl" to qrLink.getString("qrImageUrl").orEmpty(),
        "paymentKeyLabel" to qrLink.getString("paymentKeyLabel").orEmpty(),
        "beneficiaryName" to qrLink.getString("beneficiaryName").orEmpty(),
        "paymentProvider" to qrLink.getString("paymentProvider").orEmpty(),
        "paymentMethods" to parsePaymentMethods(qrLink).map { method -> mapOf(
            "provider" to method.provider,
            "qrImageUrl" to method.qrImageUrl,
            "paymentKeyLabel" to method.paymentKeyLabel,
            "beneficiaryName" to method.beneficiaryName,
        ) },
    )).await().data as Map<*, *>
        "https://veripago-9cda6.web.app/VeriPago/${result["slug"]}"
}

@Composable
private fun OrdersScreen(profile: UserProfile) {
    val context = LocalContext.current
    val ordersFlow = if (profile.role == "COMERCIANTE") AppGraph.payments.observeOrders(profile.uid) else kotlinx.coroutines.flow.flowOf(emptyList<OrderEntity>())
    val orders by ordersFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(18.dp)) {
        Text("PEDIDOS Y ENTREGAS", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp)); Text("Trazabilidad", color = Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(13.dp))
        if (orders.isEmpty()) EmptyCard("Tus pedidos aparecerán aquí al sincronizar con Firestore.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(orders, key = { it.orderId }) { order ->
                OrderCard(order, onDelivered = if (profile.role == "COMERCIANTE") ({ scope.launch { AppGraph.payments.markDelivered(order.orderId); PendingSyncScheduler.request(context) } }) else null)
            }
        }
    }
}

@Composable
private fun ListenerStatusScreen() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        allowed = listeners.contains(context.packageName)
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CELULAR RECEPTOR", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
        Text("Confirmación bancaria", color = Navy, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsActive, null, tint = if (allowed) Green else Color(0xFFB77C36)); Spacer(Modifier.width(10.dp)); Column { Text(if (allowed) "Permiso activado" else "Permiso pendiente", color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text("Acceso a notificaciones de pago", color = Muted, fontSize = 10.sp) } }
                Text("VeriPago solo lee el texto de las notificaciones de abonos. Nunca solicita claves ni acceso dentro de tu banco.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text(if (allowed) "Administrar permiso" else "Activar escucha bancaria") }
                if (allowed) OutlinedButton(onClick = { startPaymentListener(context) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(6.dp)); Text("Reanudar escucha") }
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
private fun AdminScreen() {
    val orders by AppGraph.database.orderDao().observeMerchantOrders("__admin__").collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(20.dp)) {
        Text("CONSOLA ADMINISTRADOR", color = Color(0xFF3473CC), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp)); Text("Resumen de plataforma", color = Navy, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(13.dp)); MiniMetric("Actividad local", "${orders.size} pedidos", Green, Modifier.fillMaxWidth())
        Spacer(Modifier.height(11.dp)); EmptyCard("El monitoreo global está disponible en la consola web administradora.")
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
