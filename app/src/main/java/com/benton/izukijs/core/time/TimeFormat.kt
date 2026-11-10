package com.benton.izukijs.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MONTH_DAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 月-日 时:分。使用线程安全的 [DateTimeFormatter] 并复用实例，
 * 供调度 / 编排等非 UI 代码替代每次调用都新建的 [java.text.SimpleDateFormat]。
 */
fun formatMonthDayTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(MONTH_DAY_TIME)
