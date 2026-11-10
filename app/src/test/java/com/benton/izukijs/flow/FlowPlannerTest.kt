package com.benton.izukijs.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FlowPlannerTest {

    private val steps = listOf(
        FlowStep("a", "script-a", "A"),
        FlowStep("b", "script-b", "B"),
        FlowStep("c", "script-c", "C"),
    )

    @Test
    fun sequentialKeepsConfiguredOrder() {
        assertEquals(steps, FlowPlanner.orderedSteps(steps, FlowOrder.SEQUENTIAL))
    }

    @Test
    fun randomKeepsAllSteps() {
        val shuffled = FlowPlanner.orderedSteps(steps, FlowOrder.RANDOM, Random(42))
        assertEquals(steps.size, shuffled.size)
        assertEquals(steps.toSet(), shuffled.toSet())
    }

    @Test
    fun failureContinuesOnlyWhenEnabled() {
        assertTrue(FlowPlanner.shouldContinue(continueOnError = true, success = false))
        assertFalse(FlowPlanner.shouldContinue(continueOnError = false, success = false))
        assertTrue(FlowPlanner.shouldContinue(continueOnError = false, success = true))
    }
}
