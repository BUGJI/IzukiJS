package com.benton.izukijs.schedule

import com.benton.izukijs.testing.FakeConditionProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerConditionsTest {

    @Test
    fun emptyConditionsDoNotProbeSystemState() {
        val probe = FakeConditionProbe()

        assertTrue(ScheduleConditions().matches(probe))
        assertEquals(0, probe.chargingCalls + probe.wifiCalls + probe.lockedCalls + probe.foregroundCalls)
    }

    @Test
    fun chargingFailureShortCircuitsLaterChecks() {
        val probe = FakeConditionProbe(charging = false, onWifi = true)

        assertFalse(ScheduleConditions(charging = true, wifi = true).matches(probe))
        assertEquals(1, probe.chargingCalls)
        assertEquals(0, probe.wifiCalls)
        assertEquals(0, probe.lockedCalls)
        assertEquals(0, probe.foregroundCalls)
    }

    @Test
    fun wifiAndUnlockConditionsAreEvaluatedInOrder() {
        val probe = FakeConditionProbe(charging = true, onWifi = true, locked = false)

        assertTrue(ScheduleConditions(charging = true, wifi = true, unlocked = true).matches(probe))
        assertEquals(1, probe.chargingCalls)
        assertEquals(1, probe.wifiCalls)
        assertEquals(1, probe.lockedCalls)
    }

    @Test
    fun lockedDeviceFailsUnlockedCondition() {
        val probe = FakeConditionProbe(locked = true)

        assertFalse(ScheduleConditions(unlocked = true).matches(probe))
        assertEquals(1, probe.lockedCalls)
    }

    @Test
    fun wifiFailureStopsBeforeUnlockCheck() {
        val probe = FakeConditionProbe(onWifi = false)

        assertFalse(ScheduleConditions(wifi = true, unlocked = true).matches(probe))
        assertEquals(1, probe.wifiCalls)
        assertEquals(0, probe.lockedCalls)
    }

    @Test
    fun blankForegroundPackageIsIgnored() {
        val probe = FakeConditionProbe(foreground = "com.example")

        assertTrue(ScheduleConditions(foregroundPackage = "").matches(probe))
        assertEquals(0, probe.foregroundCalls)
    }

    @Test
    fun foregroundPackageMustMatchExactly() {
        val probe = FakeConditionProbe(foreground = "com.example")

        assertTrue(ScheduleConditions(foregroundPackage = "com.example").matches(probe))
        assertEquals(1, probe.foregroundCalls)
        assertFalse(ScheduleConditions(foregroundPackage = "com.other").matches(probe))
        assertEquals(2, probe.foregroundCalls)
    }

    @Test
    fun allConditionsPassTogether() {
        val probe = FakeConditionProbe(
            charging = true,
            onWifi = true,
            locked = false,
            foreground = "com.example",
        )

        assertTrue(
            ScheduleConditions(
                charging = true,
                wifi = true,
                unlocked = true,
                foregroundPackage = "com.example",
            ).matches(probe),
        )
    }
}
