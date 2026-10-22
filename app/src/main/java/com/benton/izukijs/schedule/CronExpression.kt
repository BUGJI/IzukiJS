package com.benton.izukijs.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 五段式 Cron 表达式（分 时 日 月 周），用于定时任务。
 *
 * 支持 `*`、数字、范围 `a-b`、步长 `/n`、列表 `a,b,c`，以及 `@hourly` /
 * `@daily` / `@weekly` / `@monthly` / `@yearly` 别名。周字段 0 或 7 都表示周日。
 * 日与周同时受限时按「同时满足」处理（与部分 cron 实现的 OR 语义不同），
 * 避免脚本在意料之外的日子触发。
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
) {

    /** 返回严格晚于 [nowMillis] 的下一个触发时间；两年内无匹配返回 null。 */
    fun nextAfter(nowMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        var time = Instant.ofEpochMilli(nowMillis)
            .atZone(zoneId)
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)
        val limit = time.plusYears(8)
        while (time.isBefore(limit)) {
            if (time.monthValue !in months) {
                time = time.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
                continue
            }
            if (!dayMatches(time)) {
                time = time.plusDays(1).truncatedTo(ChronoUnit.DAYS)
                continue
            }
            if (time.hour !in hours) {
                time = time.plusHours(1).truncatedTo(ChronoUnit.HOURS)
                continue
            }
            if (time.minute !in minutes) {
                time = time.plusMinutes(1)
                continue
            }
            return time.toInstant().toEpochMilli()
        }
        return null
    }

    private fun dayMatches(time: ZonedDateTime): Boolean {
        val dom = time.dayOfMonth in daysOfMonth
        val dow = (time.dayOfWeek.value % 7) in daysOfWeek
        return dom && dow
    }

    companion object {
        private val ALIASES = mapOf(
            "@hourly" to "0 * * * *",
            "@daily" to "0 0 * * *",
            "@midnight" to "0 0 * * *",
            "@weekly" to "0 0 * * 0",
            "@monthly" to "0 0 1 * *",
            "@yearly" to "0 0 1 1 *",
            "@annually" to "0 0 1 1 *",
        )

        /** 解析失败返回 null（调用方应提示用户并拒绝保存）。 */
        fun parse(raw: String): CronExpression? {
            val expr = raw.trim().lowercase()
            if (expr.isEmpty()) return null
            val normalized = ALIASES[expr] ?: expr
            val fields = normalized.split(Regex("""\s+"""))
            if (fields.size != 5) return null
            val minutes = parseField(fields[0], 0, 59) ?: return null
            val hours = parseField(fields[1], 0, 23) ?: return null
            val dom = parseField(fields[2], 1, 31) ?: return null
            val months = parseField(fields[3], 1, 12) ?: return null
            val dow = parseField(fields[4], 0, 7, normalizeSunday = true) ?: return null
            return CronExpression(minutes, hours, dom, months, dow)
        }

        private fun parseField(
            text: String,
            min: Int,
            max: Int,
            normalizeSunday: Boolean = false,
        ): Set<Int>? {
            if (text.isEmpty()) return null
            val result = linkedSetOf<Int>()
            for (part in text.split(',')) {
                if (part.isEmpty()) return null
                val slash = part.indexOf('/')
                val base = if (slash >= 0) part.substring(0, slash) else part
                val step = if (slash >= 0) {
                    part.substring(slash + 1).toIntOrNull()?.takeIf { it > 0 } ?: return null
                } else {
                    1
                }
                val range = when {
                    base == "*" -> min..max
                    base.contains('-') -> {
                        val pieces = base.split('-', limit = 2)
                        val start = pieces[0].toIntOrNull() ?: return null
                        val end = pieces[1].toIntOrNull() ?: return null
                        if (start > end) return null
                        start..end
                    }

                    else -> {
                        val value = base.toIntOrNull() ?: return null
                        value..value
                    }
                }
                for (value in range step step) {
                    if (value < min || value > max) return null
                    result.add(if (normalizeSunday && value == 7) 0 else value)
                }
            }
            return result.ifEmpty { null }
        }
    }
}
