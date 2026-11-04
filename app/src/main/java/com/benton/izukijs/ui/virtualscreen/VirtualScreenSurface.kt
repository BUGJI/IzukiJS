package com.benton.izukijs.ui.virtualscreen

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.benton.izukijs.di.appContainer

/**
 * 虚拟屏预览 Surface。多个 owner（PiP / 虚拟屏页面）可以同时绑定，
 * 由 VirtualScreenPreviewRenderer 统一绘制。
 */
@Composable
fun VirtualScreenSurface(
    owner: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = context.appContainer.virtualScreenManager
    val attachedSurface = remember { AttachedSurface() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFormat(PixelFormat.RGBA_8888)
                val callback = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        attachedSurface.value = holder.surface
                        manager.attachPreviewSurface(owner, holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        attachedSurface.value = holder.surface
                        manager.attachPreviewSurface(owner, holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        attachedSurface.value = null
                        manager.detachPreviewSurface(owner, holder.surface)
                    }
                }
                holder.addCallback(callback)
                tag = callback
            }
        },
    )

    DisposableEffect(manager, owner, attachedSurface) {
        onDispose {
            attachedSurface.value?.let { manager.detachPreviewSurface(owner, it) }
            attachedSurface.value = null
        }
    }
}

private class AttachedSurface {
    var value: Surface? = null
}
