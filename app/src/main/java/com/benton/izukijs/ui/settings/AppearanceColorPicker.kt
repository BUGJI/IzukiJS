package com.benton.izukijs.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import androidx.compose.foundation.text.KeyboardOptions

/** 预设主题种子色（Material 3 常用种子的近似值）。 */
val ThemeSeedPresets: List<Int> = listOf(
    0xFF1B5E9E.toInt(), // 品牌蓝
    0xFF00696D.toInt(), // 青
    0xFF2E7D32.toInt(), // 绿
    0xFF4F6A34.toInt(), // 橄榄
    0xFF7D5700.toInt(), // 琥珀
    0xFF8F4C38.toInt(), // 棕橙
    0xFFB3261E.toInt(), // 红
    0xFF9A25AE.toInt(), // 紫
    0xFFB00055.toInt(), // 品红
    0xFF3F51B5.toInt(), // 靛蓝
)

/** 校验并解析 `#RGB` / `#RRGGBB` / `#AARRGGBB`，失败返回 null。 */
fun parseHexColor(input: String): Int? {
    val hex = input.trim().removePrefix("#")
    if (hex.isEmpty() || hex.any { it.digitToIntOrNull(16) == null }) return null
    val argb = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        3 -> {
            val r = (argb shr 8).toInt() and 0xF
            val g = (argb shr 4).toInt() and 0xF
            val b = argb.toInt() and 0xF
            (0xFF shl 24) or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
        }

        6 -> argb.toInt() or (0xFF shl 24)
        8 -> argb.toInt()
        else -> null
    }
}

/** ARGB -> `#RRGGBB`。 */
fun Int.toHexString(): String = "#%06X".format(this and 0xFFFFFF)

/** 一排圆形色板，选中项显示对勾。 */
@Composable
fun ColorSwatchRow(
    selectedArgb: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeSeedPresets.forEach { preset ->
            val selected = selectedArgb == preset
            val swatch = Color(preset)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(swatch, CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled) { onSelect(preset) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = if (swatch.luminance() > 0.5f) Color.Black else Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 十六进制颜色输入框：失焦或回车时提交。空输入 = 恢复默认，非法输入回退并提示。
 * 右侧附带「恢复默认」按钮。
 */
@Composable
fun HexColorField(
    value: Int?,
    enabled: Boolean,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value?.toHexString().orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }

    LaunchedEffect(value) { if (!editing) text = value?.toHexString().orEmpty() }

    fun commit() {
        val trimmed = text.trim()
        when {
            trimmed.isEmpty() -> {
                invalid = false
                if (value != null) onChange(null)
            }

            else -> {
                val parsed = parseHexColor(trimmed)
                if (parsed == null) {
                    invalid = true
                    text = value?.toHexString().orEmpty()
                } else {
                    invalid = false
                    text = parsed.toHexString()
                    onChange(parsed)
                }
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                invalid = false
            },
            label = { Text(stringResource(R.string.settings_custom_color_hex_label)) },
            placeholder = { Text("#RRGGBB") },
            singleLine = true,
            isError = invalid,
            enabled = enabled,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.settings_custom_color_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    if (editing && !state.isFocused) commit()
                    editing = state.isFocused
                },
        )
    }
}

/** 「恢复默认主题色」按钮。 */
@Composable
fun ResetSeedColorButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.settings_custom_color_reset),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
