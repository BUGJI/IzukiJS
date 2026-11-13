package com.benton.izukijs.privileged.internal

import android.view.Display
import java.lang.reflect.Method

/** 录制/回放当时屏幕的逻辑尺寸与方向；三者必须同时采样。 */
internal data class ScreenGeometry(val width: Int, val height: Int, val rotation: Int)

internal object HiddenDisplayManager {

    fun current(): ScreenGeometry? {
        readFromGlobal()?.let { return it }
        return readFromDumpsys()
    }

    private fun readFromGlobal(): ScreenGeometry? = runCatching {
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val instance = globalClass.getDeclaredMethod("getInstance").invoke(null)
        val getDisplayInfo: Method = globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        val info = getDisplayInfo.invoke(instance, Display.DEFAULT_DISPLAY) ?: return null
        val cls = info.javaClass
        val width = cls.getDeclaredField("logicalWidth").getInt(info)
        val height = cls.getDeclaredField("logicalHeight").getInt(info)
        val rotation = cls.getDeclaredField("rotation").getInt(info)
        if (width <= 0 || height <= 0) null else ScreenGeometry(width, height, rotation)
    }.onFailure {
        PrivilegedLog.w("DisplayManagerGlobal.getDisplayInfo failed: ${it.message}")
    }.getOrNull()

    private fun readFromDumpsys(): ScreenGeometry? = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", "dumpsys display")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        // real 是自然方向尺寸，rotation 是当前方向；逻辑尺寸需要按 rotation 交换。
        // 旧实现直接把 real 当逻辑尺寸返回，横屏时会被 TouchCoordMapper 再交换一次。
        val size = Regex("""real\s+(\d+)\s+x\s+(\d+)""").find(output) ?: return null
        val rotation = Regex("""rotation\s+(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val naturalWidth = size.groupValues[1].toInt()
        val naturalHeight = size.groupValues[2].toInt()
        if (naturalWidth <= 0 || naturalHeight <= 0) return null
        val width = if (rotation % 2 == 0) naturalWidth else naturalHeight
        val height = if (rotation % 2 == 0) naturalHeight else naturalWidth
        ScreenGeometry(width, height, rotation)
    }.onFailure {
        PrivilegedLog.w("dumpsys display failed: ${it.message}")
    }.getOrNull()
}
