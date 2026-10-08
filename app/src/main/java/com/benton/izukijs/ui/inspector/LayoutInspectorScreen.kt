package com.benton.izukijs.ui.inspector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.model.Capability
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class NodeBox(
    val index: Int,
    val depth: Int,
    val label: String,
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
) {
    val area: Long get() = (right - left).toLong() * (bottom - top).toLong()

    fun contains(x: Float, y: Float): Boolean =
        x in left.toFloat()..right.toFloat() && y in top.toFloat()..bottom.toFloat()

    fun selector(): String = when {
        !viewId.isNullOrBlank() -> "selector.clickById(\"$viewId\")"
        !text.isNullOrBlank() -> "selector.clickByText(\"$text\")"
        !desc.isNullOrBlank() -> "selector.clickByDesc(\"$desc\")"
        else -> "click(${(left + right) / 2}, ${(top + bottom) / 2})"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutInspectorScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val context = LocalContext.current

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var nodes by remember { mutableStateOf<List<NodeBox>>(emptyList()) }
    var selected by remember { mutableStateOf<NodeBox?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 刷新替换或离开页面时回收旧截图，避免 native 内存累积。
    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose { current?.recycle() }
    }

    LaunchedEffect(refreshKey) {
        loading = true
        message = null
        val result = withContext(Dispatchers.IO) {
            val tree = container.controllerManager.controllerFor(Capability.NODE_TREE)?.nodeTree()
            val shot = container.controllerManager.controllerFor(Capability.SCREENSHOT)?.screenshot()
            tree to shot
        }
        val tree = result.first
        bitmap = result.second
        nodes = buildList { tree?.let { flatten(it, 0, this) } }
        selected = null
        message = when {
            tree == null -> context.getString(R.string.inspector_no_tree)
            nodes.isEmpty() -> context.getString(R.string.inspector_no_nodes)
            else -> null
        }
        loading = false
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inspector_title)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
            message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            val bmp = bitmap
            if (bmp != null) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val density = LocalDensity.current
                    val widthPx = with(density) { maxWidth.toPx() }
                    val heightPx = if (bmp.width > 0) widthPx * bmp.height / bmp.width else 0f
                    val scale = if (bmp.width > 0) widthPx / bmp.width else 1f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { heightPx.toDp() })
                            .pointerInput(nodes, scale) {
                                detectTapGestures { offset ->
                                    val x = offset.x / scale
                                    val y = offset.y / scale
                                    selected = nodes
                                        .filter { it.contains(x, y) }
                                        .minByOrNull { it.area }
                                }
                            },
                    ) {
                        val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            nodes.forEach { node ->
                                val isSelected = node == selected
                                val color = when {
                                    isSelected -> Color(0xFFFF1744)
                                    node.clickable -> Color(0xFF00E5FF)
                                    else -> Color(0x559B9B9B)
                                }
                                drawRect(
                                    color = color,
                                    topLeft = Offset(node.left * scale, node.top * scale),
                                    size = Size(
                                        (node.right - node.left) * scale,
                                        (node.bottom - node.top) * scale,
                                    ),
                                    style = Stroke(width = if (isSelected) 4f else 2f),
                                )
                            }
                        }
                    }
                }
            } else if (nodes.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(nodes, key = { it.index }) { node ->
                        ListItem(
                            headlineContent = { Text(node.label, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(node.viewId, node.text, node.desc)
                                        .joinToString(" · ")
                                        .ifBlank { "(${node.left},${node.top})-(${node.right},${node.bottom})" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = node },
                        )
                    }
                }
            }

            HorizontalDivider()

            val current = selected
            if (current == null) {
                Text(
                    stringResource(
                        if (bitmap != null) R.string.inspector_tap_hint else R.string.inspector_select_hint,
                    ),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(current.label, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        InfoLine(stringResource(R.string.inspector_field_id), current.viewId)
                        InfoLine(stringResource(R.string.inspector_field_text), current.text)
                        InfoLine(stringResource(R.string.inspector_field_desc), current.desc)
                        InfoLine(
                            stringResource(R.string.inspector_field_bounds),
                            "${current.left},${current.top} - ${current.right},${current.bottom}",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            current.selector(),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                copyToClipboard(context, current.selector())
                                Toast.makeText(context, context.getString(R.string.inspector_copied), Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.inspector_copy_selector)) }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun flatten(snapshot: NodeSnapshot, depth: Int, out: MutableList<NodeBox>) {
    out.add(
        NodeBox(
            index = out.size,
            depth = depth,
            label = snapshot.className?.substringAfterLast('.') ?: "View",
            viewId = snapshot.viewId,
            text = snapshot.text,
            desc = snapshot.contentDescription,
            left = snapshot.left,
            top = snapshot.top,
            right = snapshot.right,
            bottom = snapshot.bottom,
            clickable = snapshot.clickable,
        ),
    )
    snapshot.children.forEach { flatten(it, depth + 1, out) }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.inspector_copy_selector), text))
}
