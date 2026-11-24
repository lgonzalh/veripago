package com.lantonium.veripago.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

object VeriPagoShapes {
    val card = RoundedCornerShape(DesignTokens.CardCornerRadius)
    val control = RoundedCornerShape(DesignTokens.ControlCornerRadius)
    val material = Shapes(small = control, medium = card, large = card)
}
