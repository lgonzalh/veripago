package com.lantonium.veripago.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantonium.veripago.R
import com.lantonium.veripago.util.VersionHelper

private val AboutNavy = Color(0xFF0C2442)
private val AboutMuted = Color(0xFF758398)
private val AboutBlue = Color(0xFF2168D4)
private val AboutBorder = Color(0xFFE1E8F0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F7FA))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ACERCA DE", color = AboutBlue, fontSize = 15.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Image(
            painter = painterResource(R.drawable.luis),
            contentDescription = "Luis Gonzalez",
            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text("Luis Gonzalez", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = AboutNavy)
        Text("Creador de VeriPago · Lantonium", style = MaterialTheme.typography.titleSmall, color = AboutMuted)
        Spacer(Modifier.height(8.dp))
        Text(
            VersionHelper.getGreekVersionString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = AboutBlue,
        )
        Spacer(Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sobre VeriPago", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AboutNavy)
                Text(
                    "VeriPago ayuda a los comercios a cobrar directamente con Nequi, DaviPlata o Bre-B y a seguir cada pedido desde un solo lugar.",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                    color = AboutNavy,
                )
                Text(
                    "La app verifica los pagos recibidos y anuncia su confirmación en voz alta para que puedas entregar con confianza.",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                    color = AboutNavy,
                )
                Text(
                    "Desarrollada por Lantonium para facilitar la gestión de cobros, pedidos y entregas de los comercios.",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                    color = AboutNavy,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Tecnologías", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AboutNavy)
                FlowRow(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("Android", "Kotlin", "Jetpack Compose", "Firebase", "Google Sign-In", "Nequi", "DaviPlata", "Bre-B").forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag) },
                            shape = RoundedCornerShape(10.dp),
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFFF5F7FA), labelColor = AboutNavy),
                            border = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = AboutBorder),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Contacto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AboutNavy)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AboutContactIcon("Sitio web", Modifier.weight(1f), icon = Icons.Default.Language) { openUrl("https://lantonium.com/acerca") }
                    AboutContactIcon("GitHub", Modifier.weight(1f), drawableRes = R.drawable.github) { openUrl("https://github.com/lgonzalh") }
                    AboutContactIcon("LinkedIn", Modifier.weight(1f), drawableRes = R.drawable.linkedin) { openUrl("https://www.linkedin.com/in/lantonium/") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AboutContactIcon("WhatsApp", Modifier.weight(1f), drawableRes = R.drawable.whatsapp) { openUrl("https://wa.me/573246864991") }
                    AboutContactIcon("Email", Modifier.weight(1f), drawableRes = R.drawable.outlook) { openUrl("mailto:lgonzalh@outlook.com") }
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Para conversar sobre VeriPago o compartir ideas sobre tecnología, puedes escribirme por correo, LinkedIn, GitHub o WhatsApp.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AboutMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("© 2021–2026 Luis Gonzalez. Todos los derechos reservados.", style = MaterialTheme.typography.bodySmall, color = AboutMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AboutContactIcon(
    label: String,
    modifier: Modifier,
    drawableRes: Int? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AboutNavy),
            border = BorderStroke(1.dp, AboutBorder),
        ) {
            if (drawableRes != null) {
                Image(painterResource(drawableRes), contentDescription = label, modifier = Modifier.fillMaxSize())
            } else {
                Icon(icon ?: Icons.Default.Language, contentDescription = label, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = AboutNavy)
    }
}
