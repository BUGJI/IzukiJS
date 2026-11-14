package com.benton.izukijs.ai

import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.controller.UiautomatorDumpBudget
import com.benton.izukijs.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TreeProbeTest {

    @Before
    fun resetBudget() {
        UiautomatorDumpBudget.resetForTest()
    }

    @Test
    fun successIsReturnedWithoutRetry() {
        val result = NodeTreeResult.NoBackend
        val budgets = mutableListOf<Long>()

        val actual = TreeProbe.probe(1_000L) { budget ->
            budgets += budget
            result
        }

        assertSame(result, actual)
        assertEquals(listOf(1_000L), budgets)
    }

    @Test
    fun nonTimeoutFailureIsNotRetried() {
        val failure = NodeTreeResult.Failure("parse error", timedOut = false)
        val budgets = mutableListOf<Long>()

        val actual = TreeProbe.probe(1_000L) { budget ->
            budgets += budget
            failure
        }

        assertSame(failure, actual)
        assertEquals(listOf(1_000L), budgets)
    }

    @Test
    fun timeoutRetriesOnceWithAmplifiedBudget() {
        val budgets = mutableListOf<Long>()
        val success = NodeTreeResult.Success(
            com.benton.izukijs.controller.NodeSnapshot(
                className = null,
                text = null,
                viewId = null,
                contentDescription = null,
                left = 0,
                top = 0,
                right = 0,
                bottom = 0,
                clickable = false,
                editable = false,
                children = emptyList(),
            ),
        )

        val actual = TreeProbe.probe(1_500L) { budget ->
            budgets += budget
            if (budgets.size == 1) {
                NodeTreeResult.Failure("timeout", timedOut = true, budgetMs = budget)
            } else {
                success
            }
        }

        assertSame(success, actual)
        assertEquals(listOf(1_500L, 3_750L), budgets)
    }

    @Test
    fun timeoutRetryFailureMentionsBothBudgets() {
        val budgets = mutableListOf<Long>()

        val actual = TreeProbe.probe(1_500L) { budget ->
            budgets += budget
            NodeTreeResult.Failure("timeout", timedOut = true, budgetMs = budget)
        }

        assertEquals(listOf(1_500L, 3_750L), budgets)
        val failure = actual as NodeTreeResult.Failure
        assertTrue(failure.message.contains("1500ms"))
        assertTrue(failure.message.contains("3750ms"))
    }

    @Test
    fun largeLearnedBaselineSkipsRetry() {
        UiautomatorDumpBudget.recordMeasured(2_300L)
        val budgets = mutableListOf<Long>()

        val actual = TreeProbe.probe(3_680L) { budget ->
            budgets += budget
            NodeTreeResult.Failure("timeout", timedOut = true, budgetMs = budget)
        }

        assertEquals(listOf(3_680L), budgets)
        assertTrue(actual is NodeTreeResult.Failure)
    }

    @Test
    fun treeProbeReturnsNullWhenDeadlineAlreadyPassed() {
        val clock = FakeClock(nowMs = 1_000L)
        var called = false

        val actual = TreeProbe.treeProbe(
            deadline = 1_050L,
            now = clock::now,
            provider = {
                called = true
                NodeTreeResult.NoBackend
            },
        )

        assertNull(actual)
        assertEquals(false, called)
    }

    @Test
    fun treeProbeClampsInitialBudgetToRemainingTime() {
        val clock = FakeClock(nowMs = 1_000L)
        val budgets = mutableListOf<Long>()

        val actual = TreeProbe.treeProbe(
            deadline = 1_500L,
            now = clock::now,
            provider = { budget ->
                budgets += budget
                NodeTreeResult.NoBackend
            },
        )

        assertSame(NodeTreeResult.NoBackend, actual)
        assertEquals(listOf(500L), budgets)
    }

    @Test
    fun treeProbeSkipsRetryWhenItWouldExceedDeadline() {
        val clock = FakeClock(nowMs = 1_000L)
        val budgets = mutableListOf<Long>()

        val actual = TreeProbe.treeProbe(
            deadline = 1_500L,
            now = clock::now,
            provider = { budget ->
                budgets += budget
                NodeTreeResult.Failure("timeout", timedOut = true, budgetMs = budget)
            },
        )

        assertEquals(listOf(500L), budgets)
        assertTrue(actual is NodeTreeResult.Failure)
    }

    @Test
    fun treeProbeRetriesWithinDeadline() {
        val clock = FakeClock(nowMs = 1_000L)
        val budgets = mutableListOf<Long>()

        val actual = TreeProbe.treeProbe(
            deadline = 10_000L,
            now = clock::now,
            provider = { budget ->
                budgets += budget
                NodeTreeResult.Failure("timeout", timedOut = true, budgetMs = budget)
            },
        )

        assertEquals(listOf(1_500L, 3_750L), budgets)
        assertTrue(actual is NodeTreeResult.Failure)
    }
}
