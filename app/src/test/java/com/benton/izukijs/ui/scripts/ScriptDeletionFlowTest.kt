package com.benton.izukijs.ui.scripts

import com.benton.izukijs.flow.FlowStep
import com.benton.izukijs.flow.ScriptFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptDeletionFlowTest {

    @Test
    fun emptyFlowIsNotCancelled() {
        val flow = ScriptFlow(id = "flow", name = "empty")

        assertFalse(shouldCancelFlow(flow, setOf("a", "b")))
    }

    @Test
    fun partialFlowIsNotCancelled() {
        val flow = flowOf(step("s1", "a"), step("s2", "b"))

        assertFalse(shouldCancelFlow(flow, setOf("a")))
    }

    @Test
    fun flowWithOnlyDeletedStepsIsCancelled() {
        val flow = flowOf(step("s1", "a"), step("s2", "b"))

        assertTrue(shouldCancelFlow(flow, setOf("a", "b")))
        assertTrue(shouldCancelFlow(flow, setOf("a", "b", "c")))
    }

    @Test
    fun remapUpdatesOnlyRestoredSteps() {
        val original = listOf(
            step("s1", "a", "A"),
            step("s2", "b", "B"),
            step("s3", "c", "C"),
        )

        val mapped = remapFlowSteps(
            steps = original,
            idMap = mapOf("a" to "a_1", "b" to "b_1"),
            restoredNames = mapOf("a_1" to "A (restored)", "b_1" to "B (restored)"),
        )

        assertEquals(
            listOf(
                step("s1", "a_1", "A (restored)"),
                step("s2", "b_1", "B (restored)"),
                step("s3", "c", "C"),
            ),
            mapped,
        )
    }

    @Test
    fun remapFallsBackToOriginalNameWhenMissing() {
        val mapped = remapFlowSteps(
            steps = listOf(step("s1", "a", "A")),
            idMap = mapOf("a" to "a_1"),
            restoredNames = emptyMap(),
        )

        assertEquals(listOf(step("s1", "a_1", "A")), mapped)
    }

    @Test
    fun insertRestoredStepsKeepsOriginalOrder() {
        val current = listOf(step("s2", "b", "B"))
        val mapped = listOf(
            step("s1", "a_1", "A"),
            step("s2", "b", "B"),
            step("s3", "c_1", "C"),
        )

        val merged = insertRestoredSteps(
            current = current,
            mappedSteps = mapped,
            restoredIds = setOf("a_1", "c_1"),
        )

        assertEquals(
            listOf(
                step("s1", "a_1", "A"),
                step("s2", "b", "B"),
                step("s3", "c_1", "C"),
            ),
            merged,
        )
    }

    @Test
    fun insertRestoredStepsSkipsExistingSteps() {
        val existing = step("s1", "a_1", "A")

        val merged = insertRestoredSteps(
            current = listOf(existing),
            mappedSteps = listOf(existing),
            restoredIds = setOf("a_1"),
        )

        assertEquals(listOf(existing), merged)
    }

    @Test
    fun insertRestoredStepsIntoEmptyFlowKeepsOrder() {
        val mapped = listOf(
            step("s1", "a_1", "A"),
            step("s2", "b_1", "B"),
            step("s3", "c_1", "C"),
        )

        val merged = insertRestoredSteps(
            current = emptyList(),
            mappedSteps = mapped,
            restoredIds = setOf("a_1", "b_1", "c_1"),
        )

        assertEquals(mapped, merged)
    }

    private fun flowOf(vararg steps: FlowStep) = ScriptFlow(
        id = "flow",
        name = "flow",
        steps = steps.toList(),
    )

    private fun step(id: String, scriptId: String, name: String = scriptId) = FlowStep(
        id = id,
        scriptId = scriptId,
        scriptName = name,
    )
}
