package com.lantonium.veripago.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

object VeriPagoShapes {
    val card = RoundedCornerShape(DesignTokens.cardCorner)
    val control = RoundedCornerShape(DesignTokens.controlCorner)
    val material = Shapes(small = control, medium = card, large = card)
}
