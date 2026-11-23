package com.lantonium.veripago

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lantonium.veripago.ui.VeriPagoApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
        content = content,
    )
}
