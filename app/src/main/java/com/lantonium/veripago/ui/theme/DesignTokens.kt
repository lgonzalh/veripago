package com.lantonium.veripago.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Shared geometry and type scale for VeriPago's Android screens. */
object DesignTokens {
    val CardCornerRadius: Dp = 16.dp
    val ControlCornerRadius: Dp = 12.dp
    val StandardControlHeight: Dp = 52.dp
    val FieldContainerHeight: Dp = 60.dp
    val StandardBorderWidth: Dp = 1.dp
    val bodyText: TextUnit = 15.sp
    val fieldLabel: TextUnit = 12.sp

    // Backwards-compatible names keep all existing call sites on the same tokens.
    val cardCorner: Dp get() = CardCornerRadius
    val controlCorner: Dp get() = ControlCornerRadius
    val controlHeight: Dp get() = StandardControlHeight
    val fieldHeight: Dp get() = FieldContainerHeight
    val outlineWidth: Dp get() = StandardBorderWidth

    @Composable
    fun standardBorder(): BorderStroke = BorderStroke(StandardBorderWidth, MaterialTheme.colorScheme.outlineVariant)
}
