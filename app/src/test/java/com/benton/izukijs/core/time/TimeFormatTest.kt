package com.benton.izukijs.core.time

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

class TimeFormatTest {

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousLocale: Locale

    @Before
    fun useFixedZoneAndLocale() {
        previousTimeZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreZoneAndLocale() {
        TimeZone.setDefault(previousTimeZone)
        Locale.setDefault(previousLocale)
    }

    @Test
    fun formatsEpochInMonthDayTimePattern() {
        assertEquals("01-01 00:00", formatMonthDayTime(0L))
    }

    @Test
    fun formatsSpecificInstant() {
        val millis = LocalDateTime.of(2024, 3, 5, 8, 9)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

        assertEquals("03-05 08:09", formatMonthDayTime(millis))
    }
}
