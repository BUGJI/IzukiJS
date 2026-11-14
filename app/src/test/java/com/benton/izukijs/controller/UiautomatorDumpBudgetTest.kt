package com.benton.izukijs.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UiautomatorDumpBudgetTest {

    @Before
    fun resetBudget() {
        UiautomatorDumpBudget.resetForTest()
    }

    @Test
    fun effectiveBudgetUsesMeasuredDurationWithHeadroom() {
        assertEquals(0L, UiautomatorDumpBudget.effectiveBudgetFor(0L))
        assertEquals(1_500L, UiautomatorDumpBudget.effectiveBudgetFor(100L))
        assertEquals(3_680L, UiautomatorDumpBudget.effectiveBudgetFor(2_300L))
    }

    @Test
    fun effectiveBudgetIsCapped() {
        assertEquals(10_000L, UiautomatorDumpBudget.effectiveBudgetFor(60_000L))
    }

    @Test
    fun retryAmplifiesFirstAttemptWhenNoBaseline() {
        assertEquals(3_750L, UiautomatorDumpBudget.retryBudgetFor(1_500L, 0L))
        assertEquals(3_000L, UiautomatorDumpBudget.retryBudgetFor(1_200L, 0L))
        assertEquals(6_250L, UiautomatorDumpBudget.retryBudgetFor(2_500L, 0L))
    }

    @Test
    fun retryStillHelpsWhenLearnedBudgetIsSmall() {
        assertEquals(3_750L, UiautomatorDumpBudget.retryBudgetFor(1_500L, 400L))
    }

    @Test
    fun retryIsSkippedWhenLearnedBudgetAlreadyLarge() {
        assertNull(UiautomatorDumpBudget.retryBudgetFor(3_680L, 2_300L))
        assertNull(UiautomatorDumpBudget.retryBudgetFor(10_000L, 0L))
        assertNull(UiautomatorDumpBudget.retryBudgetFor(0L, 0L))
    }

    @Test
    fun nextBaselineStartsAtFirstMeasurementAndSmoothsLater() {
        assertEquals(1_000L, UiautomatorDumpBudget.nextBaselineFor(0L, 1_000L))
        assertEquals(1_250L, UiautomatorDumpBudget.nextBaselineFor(1_000L, 2_000L))
        assertEquals(1_000L, UiautomatorDumpBudget.nextBaselineFor(1_000L, 0L))
    }

    @Test
    fun nonPositiveMeasurementsAreIgnored() {
        assertEquals(0L, UiautomatorDumpBudget.measuredMs())
        UiautomatorDumpBudget.recordMeasured(0L)
        UiautomatorDumpBudget.recordMeasured(-1L)
        assertEquals(0L, UiautomatorDumpBudget.measuredMs())

        UiautomatorDumpBudget.recordMeasured(1_000L)
        UiautomatorDumpBudget.recordMeasured(0L)
        assertEquals(1_000L, UiautomatorDumpBudget.measuredMs())
    }

    @Test
    fun recordMeasuredSmoothsSuccessiveSamples() {
        UiautomatorDumpBudget.recordMeasured(1_000L)
        UiautomatorDumpBudget.recordMeasured(2_000L)

        assertEquals(1_250L, UiautomatorDumpBudget.measuredMs())
        assertEquals(2_000L, UiautomatorDumpBudget.effectiveBaselineMs())
    }

    @Test
    fun negativeBudgetIsTreatedAsUnmeasured() {
        assertEquals(0L, UiautomatorDumpBudget.effectiveBudgetFor(-100L))
        assertNull(UiautomatorDumpBudget.retryBudgetFor(-1L, 0L))
    }

    @Test
    fun concurrentMeasurementsStayWithinObservedRange() {
        val threads = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { index ->
                pool.execute {
                    start.await()
                    UiautomatorDumpBudget.recordMeasured(if (index % 2 == 0) 1_000L else 2_000L)
                }
            }
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }

        val measured = UiautomatorDumpBudget.measuredMs()
        assertTrue("measurement=$measured", measured in 1_000L..2_000L)
    }

    @Test
    fun resetForTestClearsLearnedBaseline() {
        UiautomatorDumpBudget.recordMeasured(3_000L)
        assertEquals(3_000L, UiautomatorDumpBudget.measuredMs())

        UiautomatorDumpBudget.resetForTest()

        assertEquals(0L, UiautomatorDumpBudget.measuredMs())
    }
}
