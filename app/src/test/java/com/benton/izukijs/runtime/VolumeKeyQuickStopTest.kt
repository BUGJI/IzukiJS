package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeyQuickStopTest {

    @Test
    fun parsesSettingsKeysAndFallsBackToDefaults() {
        QuickStopKey.entries.forEach { key ->
            assertEquals(key, QuickStopKey.fromKey(key.key))
        }
        QuickStopGesture.entries.forEach { gesture ->
            assertEquals(gesture, QuickStopGesture.fromKey(gesture.key))
        }
        assertEquals(QuickStopKey.VOLUME_DOWN, QuickStopKey.fromKey(null))
        assertEquals(QuickStopKey.VOLUME_DOWN, QuickStopKey.fromKey("unknown"))
        assertEquals(QuickStopGesture.SINGLE, QuickStopGesture.fromKey(null))
        assertEquals(QuickStopGesture.SINGLE, QuickStopGesture.fromKey("unknown"))
    }

    @Test
    fun matchesSelectedVolumeKey() {
        assertTrue(QuickStopKey.VOLUME_DOWN.matchesVolumeKey(VOLUME_KEY_DOWN))
        assertFalse(QuickStopKey.VOLUME_DOWN.matchesVolumeKey(VOLUME_KEY_UP))
        assertTrue(QuickStopKey.VOLUME_UP.matchesVolumeKey(VOLUME_KEY_UP))
        assertFalse(QuickStopKey.VOLUME_UP.matchesVolumeKey(VOLUME_KEY_DOWN))
        assertTrue(QuickStopKey.BOTH.matchesVolumeKey(VOLUME_KEY_UP))
        assertTrue(QuickStopKey.BOTH.matchesVolumeKey(VOLUME_KEY_DOWN))
        assertTrue(isVolumeKey(VOLUME_KEY_UP))
        assertTrue(isVolumeKey(VOLUME_KEY_DOWN))
        assertFalse(isVolumeKey(26))
    }

    @Test
    fun singlePressStopsOnFirstDownAndConsumesRepeatsUntilUp() {
        val tracker = QuickStopGestureTracker()

        assertEquals(
            QuickStopEffect.STOP,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 0, QuickStopGesture.SINGLE, 1_000L),
        )
        assertEquals(
            QuickStopEffect.CONSUME,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 1, QuickStopGesture.SINGLE, 1_100L),
        )
        assertEquals(QuickStopEffect.CONSUME, tracker.onKeyUp(VOLUME_KEY_DOWN))
    }

    @Test
    fun doublePressRequiresSecondPressInsideWindow() {
        val tracker = QuickStopGestureTracker(doublePressWindowMs = 600L)

        assertEquals(
            QuickStopEffect.CONSUME,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 0, QuickStopGesture.DOUBLE, 1_000L),
        )
        assertEquals(QuickStopEffect.CONSUME, tracker.onKeyUp(VOLUME_KEY_DOWN))
        assertEquals(
            QuickStopEffect.STOP,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 0, QuickStopGesture.DOUBLE, 1_500L),
        )
        assertEquals(QuickStopEffect.CONSUME, tracker.onKeyUp(VOLUME_KEY_DOWN))
    }

    @Test
    fun doublePressAfterWindowBecomesNewFirstPress() {
        val tracker = QuickStopGestureTracker(doublePressWindowMs = 600L)

        assertEquals(
            QuickStopEffect.CONSUME,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 0, QuickStopGesture.DOUBLE, 1_000L),
        )
        assertEquals(QuickStopEffect.CONSUME, tracker.onKeyUp(VOLUME_KEY_DOWN))
        assertEquals(
            QuickStopEffect.CONSUME,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 0, QuickStopGesture.DOUBLE, 1_700L),
        )
    }

    @Test
    fun longPressStopsOnTimeoutAndCancelsOnUp() {
        val tracker = QuickStopGestureTracker()

        assertEquals(
            QuickStopEffect.CONSUME_AND_ARM_LONG_PRESS,
            tracker.onKeyDown(VOLUME_KEY_UP, 0, QuickStopGesture.LONG, 1_000L),
        )
        assertEquals(
            QuickStopEffect.CONSUME,
            tracker.onKeyDown(VOLUME_KEY_UP, 1, QuickStopGesture.LONG, 1_100L),
        )
        assertEquals(QuickStopEffect.STOP, tracker.onLongPressTimeout(VOLUME_KEY_UP))
        assertEquals(QuickStopEffect.IGNORE, tracker.onLongPressTimeout(VOLUME_KEY_UP))
        assertEquals(
            QuickStopEffect.CONSUME_AND_CANCEL_LONG_PRESS,
            tracker.onKeyUp(VOLUME_KEY_UP),
        )
    }

    @Test
    fun releasingBeforeLongPressTimeoutDoesNotStop() {
        val tracker = QuickStopGestureTracker()

        assertEquals(
            QuickStopEffect.CONSUME_AND_ARM_LONG_PRESS,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 0, QuickStopGesture.LONG, 1_000L),
        )
        assertEquals(
            QuickStopEffect.CONSUME_AND_CANCEL_LONG_PRESS,
            tracker.onKeyUp(VOLUME_KEY_DOWN),
        )
        assertEquals(QuickStopEffect.IGNORE, tracker.onLongPressTimeout(VOLUME_KEY_DOWN))
    }

    @Test
    fun lostKeyUpIsRecoveredByNextFreshPress() {
        val tracker = QuickStopGestureTracker()

        assertEquals(
            QuickStopEffect.STOP,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 0, QuickStopGesture.SINGLE, 1_000L),
        )
        assertEquals(
            QuickStopEffect.STOP,
            tracker.onKeyDown(VOLUME_KEY_DOWN, 0, QuickStopGesture.SINGLE, 1_500L),
        )
    }
}
