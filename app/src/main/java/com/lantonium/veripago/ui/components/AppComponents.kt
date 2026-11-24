package com.lantonium.veripago.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantonium.veripago.ui.theme.DesignTokens
import com.lantonium.veripago.ui.theme.VeriPagoShapes

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = VeriPagoShapes.card,
        border = DesignTokens.standardBorder(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        content = { content() },
    )
}

@Composable
fun VeriNotchedContainer(
    label: String,
    modifier: Modifier = Modifier,
    borderColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.outlineVariant,
    surfaceColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(DesignTokens.controlCorner)
    Box(modifier.fillMaxWidth().height(DesignTokens.fieldHeight)) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(DesignTokens.controlHeight)
                .clip(shape).background(surfaceColor)
                .border(BorderStroke(DesignTokens.outlineWidth, borderColor), shape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
            content = content,
        )
        Text(
            label,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 12.dp).background(surfaceColor).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = DesignTokens.fieldLabel,
                lineHeight = 16.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            ),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Fixed geometry for all one-line form fields. The label sits in the top border notch. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val surface = MaterialTheme.colorScheme.surface
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    VeriNotchedContainer(
        label = label.orEmpty(),
        modifier = modifier,
        borderColor = borderColor,
        surfaceColor = surface,
        labelColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                enabled = enabled,
                singleLine = true,
                maxLines = 1,
                keyboardOptions = keyboardOptions,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = DesignTokens.bodyText,
                    lineHeight = 20.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = DesignTokens.bodyText,
                                    lineHeight = 20.sp,
                                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (!focused && value.isNotEmpty()) Text(
                value,
                modifier = Modifier.fillMaxWidth().background(surface),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = DesignTokens.bodyText,
                    lineHeight = 20.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
}

/** Fixed-size app input used by all form screens. */
@Composable
fun VeriFixedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
) = AppTextField(value, onValueChange, modifier, label, placeholder, keyboardOptions, enabled)

/** Consistent drop-down selector styled to match the fixed text field. */
@Composable
fun <T> VeriFixedSelector(
    value: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Producto",
    placeholder: String = "Selecciona un producto",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val surface = MaterialTheme.colorScheme.surface
    VeriNotchedContainer(label, modifier, surfaceColor = surface) {
        Box(Modifier.fillMaxWidth().clickable(enabled) { expanded = true }, contentAlignment = Alignment.CenterStart) {
            Text(
                value.ifBlank { placeholder },
                modifier = Modifier.fillMaxWidth().padding(end = 28.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = DesignTokens.bodyText),
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("⌄", modifier = Modifier.align(Alignment.CenterEnd), fontSize = DesignTokens.bodyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val labelText = optionText(option)
                DropdownMenuItem(
                    text = { Text(labelText, fontSize = DesignTokens.bodyText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
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
        modifier = modifier.height(DesignTokens.controlHeight),
        enabled = enabled,
        shape = VeriPagoShapes.control,
        border = DesignTokens.standardBorder(),
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
        modifier = modifier.height(DesignTokens.controlHeight),
        enabled = enabled,
        shape = VeriPagoShapes.control,
        border = DesignTokens.standardBorder(),
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
                    Text(option.label, fontSize = DesignTokens.bodyText, maxLines = 1)
                }
            } else {
                AppOutlinedButton(onClick = { onSelected(option.value) }, modifier = Modifier.weight(1f)) {
                    Text(option.label, fontSize = DesignTokens.bodyText, maxLines = 1)
                }
            }
        }
    }
}
