package com.benton.izukijs.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R

/** 设置类页面统一的卡片容器：标题 + 内容。 */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** 带标题的普通输入框，多个设置页共用；自带 8dp 上间距。 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 带「显示 / 隐藏」切换的密钥输入框；自带 8dp 上间距。 */
@Composable
fun SecretField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.common_hide else R.string.common_show))
            }
        },
    )
}

/**
 * 整数输入框：本地保留用户正在输入的文本，仅在能解析且落在 [range] 内时才回写，
 * 避免「清空 / 中间态」被外部值立刻回弹；失焦时把非法输入还原为当前值。
 */
@Composable
fun NumberField(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onChange: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!editing) text = value.toString() }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter(Char::isDigit)
            text = filtered
            filtered.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth().onFocusChanged { state ->
            if (editing && !state.isFocused) {
                val parsed = text.toIntOrNull()
                if (parsed != null && parsed in range) {
                    onChange(parsed)
                    text = parsed.toString()
                } else {
                    text = value.toString()
                }
            }
            editing = state.isFocused
        },
    )
}

/**
 * 小数输入框：保留单个小数点，仅在解析成功且落在 [range] 内时回写，失焦时归一化显示。
 */
@Composable
fun DecimalField(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onChange: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!editing) text = value.toString() }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val builder = StringBuilder()
            var dotSeen = false
            input.forEach { c ->
                when {
                    c.isDigit() -> builder.append(c)
                    c == '.' && !dotSeen -> {
                        dotSeen = true
                        builder.append(c)
                    }
                }
            }
            val filtered = builder.toString()
            text = filtered
            filtered.toDoubleOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth().onFocusChanged { state ->
            if (editing && !state.isFocused) {
                val parsed = text.toDoubleOrNull()
                if (parsed != null && parsed in range) {
                    onChange(parsed)
                    text = parsed.toString()
                } else {
                    text = value.toString()
                }
            }
            editing = state.isFocused
        },
    )
}

/** 标题 + 可选说明 + 开关的整行。 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 自动换行的选项组：小屏上把选项折到下一行，避免横向滚动把选项挤出屏幕。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** 列表 / 控制台统一的空状态提示。 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 可纵向滚动的页面内容容器：限制最大宽度并居中，避免平板 / 折叠屏上单列被拉得过宽。
 * 调用方通过 [modifier] 传入 Scaffold 的 padding 与页面内边距。
 */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 840.dp,
    verticalArrangement: androidx.compose.foundation.layout.Arrangement.Vertical =
        androidx.compose.foundation.layout.Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = verticalArrangement,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = maxWidth),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
