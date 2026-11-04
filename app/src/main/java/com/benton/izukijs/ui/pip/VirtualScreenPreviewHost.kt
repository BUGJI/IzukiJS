package com.benton.izukijs.ui.pip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.benton.izukijs.ui.virtualscreen.VirtualScreenSurface
import com.benton.izukijs.virtualscreen.VirtualScreenManager

/**
 * 系统画中画用的虚拟屏预览宿主。
 *
 * 非 PiP 时只保留一个 1×1 离屏 Surface，用于进入 PiP 时立即出帧；
 * 只有 PiP 中才铺满窗口并绘制黑底，避免覆盖主界面。
 */
@Composable
fun VirtualScreenPreviewHost(modifier: Modifier = Modifier) {
    val inPip = LocalIsInPip.current
    if (inPip) {
        Box(modifier = modifier.background(Color.Black)) {
            VirtualScreenSurface(
                owner = VirtualScreenManager.PIP_PREVIEW_OWNER,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        VirtualScreenSurface(
            owner = VirtualScreenManager.PIP_PREVIEW_OWNER,
            modifier = Modifier
                .size(1.dp)
                .offset(x = (-100).dp, y = (-100).dp),
        )
    }
}
