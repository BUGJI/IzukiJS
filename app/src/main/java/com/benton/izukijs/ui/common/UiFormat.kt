package com.benton.izukijs.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.benton.izukijs.runtime.LogLevel
import com.benton.izukijs.ui.theme.LocalIzukiExtraColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 统一的文件大小展示，避免各页面重复实现。 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
fun LogLevel.displayColor(): Color {
    val extra = LocalIzukiExtraColors.current
    return when (this) {
        LogLevel.DEBUG -> extra.logDebug
        LogLevel.INFO -> extra.logInfo
        LogLevel.SUCCESS -> extra.logSuccess
        LogLevel.WARN -> extra.logWarn
        LogLevel.ERROR -> extra.logError
    }
}

fun LogLevel.displayName(): String = when (this) {
    LogLevel.DEBUG -> "调试"
    LogLevel.INFO -> "信息"
    LogLevel.SUCCESS -> "成功"
    LogLevel.WARN -> "警告"
    LogLevel.ERROR -> "错误"
}

fun LogLevel.shortTag(): String = when (this) {
    LogLevel.DEBUG -> "D"
    LogLevel.INFO -> "I"
    LogLevel.SUCCESS -> "S"
    LogLevel.WARN -> "W"
    LogLevel.ERROR -> "E"
}

private val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 日志时间戳，[DateTimeFormatter] 线程安全，可在任意线程调用。 */
fun formatLogTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(LOG_TIME_FORMAT)
