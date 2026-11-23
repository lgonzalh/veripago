package com.lantonium.veripago

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.lantonium.veripago.R
import com.lantonium.veripago.ui.VeriPagoApp

private val VeriPagoFontFamily = FontFamily(Font(R.font.ford_antenna_regular, FontWeight.Normal))
private val BaseTypography = Typography()
private val VeriPagoTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = VeriPagoFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = VeriPagoFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = VeriPagoFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = VeriPagoFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = VeriPagoFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = VeriPagoFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = VeriPagoFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = VeriPagoFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = VeriPagoFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = VeriPagoFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = VeriPagoFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = VeriPagoFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = VeriPagoFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = VeriPagoFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = VeriPagoFontFamily),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            VeriPagoTheme { VeriPagoApp(this) }
        }
    }
}

@Composable
private fun VeriPagoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2168D4),
            secondary = Color(0xFF12A875),
            background = Color(0xFFF5F7FA),
            surface = Color.White,
            onPrimary = Color.White,
            onBackground = Color(0xFF172A43),
        ),
        typography = VeriPagoTypography,
        content = content,
    )
}
