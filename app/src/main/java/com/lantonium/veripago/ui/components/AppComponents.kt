package com.lantonium.veripago.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantonium.veripago.ui.theme.DesignTokens
import com.lantonium.veripago.ui.theme.VeriPagoShapes

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = VeriPagoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        content = { content() },
    )
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = DesignTokens.controlHeight),
        enabled = enabled,
        label = label?.let { { Text(it, fontSize = DesignTokens.contentText) } },
        placeholder = placeholder?.let { { Text(it, fontSize = DesignTokens.contentText) } },
        textStyle = TextStyle(fontSize = DesignTokens.contentText, color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        shape = VeriPagoShapes.control,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = DesignTokens.controlHeight),
        enabled = enabled,
        shape = VeriPagoShapes.control,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = DesignTokens.controlHeight),
        enabled = enabled,
        shape = VeriPagoShapes.control,
        border = BorderStroke(DesignTokens.outlineWidth, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}

data class SegmentOption<T>(val value: T, val label: String)

@Composable
fun <T> AppSegmentedSelector(
    options: List<SegmentOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            if (option.value == selected) {
                AppButton(onClick = { onSelected(option.value) }, modifier = Modifier.weight(1f)) {
                    Text(option.label, fontSize = DesignTokens.contentText, maxLines = 1)
                }
            } else {
                AppOutlinedButton(onClick = { onSelected(option.value) }, modifier = Modifier.weight(1f)) {
                    Text(option.label, fontSize = DesignTokens.contentText, maxLines = 1)
                }
            }
        }
    }
}
