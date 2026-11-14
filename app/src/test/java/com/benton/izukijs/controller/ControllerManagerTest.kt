package com.benton.izukijs.controller

import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.testing.FakeDeviceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerManagerTest {

    private fun controller(
        mode: ControlMode,
        capabilities: Set<Capability>,
        ready: Boolean = true,
        displayTarget: Boolean = false,
    ) = FakeDeviceController(mode, ready = ready, capabilities = capabilities, displayTarget = displayTarget)

    @Test
    fun registeringReadyControllerPublishesReadyMode() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))

        manager.register(accessibility)

        assertEquals(setOf(ControlMode.ACCESSIBILITY), manager.readyModes.value)
        assertEquals(setOf(ControlMode.ACCESSIBILITY), manager.effectiveReadyModes.value)
    }

    @Test
    fun unregisterRemovesMode() {
        val manager = ControllerManager()
        manager.register(controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE)))

        manager.unregister(ControlMode.ACCESSIBILITY)

        assertTrue(manager.all().isEmpty())
        assertFalse(manager.anyReady())
    }

    @Test
    fun reRegisteringSameModeReplacesOldController() {
        val manager = ControllerManager()
        val old = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))
        val replacement = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))
        manager.register(old)

        manager.register(replacement)

        assertEquals(1, manager.all().size)
        assertSame(replacement, manager.all().single())
    }

    @Test
    fun controllerForRequiresDeclaredCapability() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))
        manager.register(accessibility)

        assertSame(accessibility, manager.controllerFor(Capability.GESTURE))
        assertNull(manager.controllerFor(Capability.SHELL))
    }

    @Test
    fun controllerForSkipsNotReadyBackend() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE), ready = false)
        manager.register(accessibility)

        assertNull(manager.controllerFor(Capability.GESTURE))

        accessibility.setReady(true)
        assertSame(accessibility, manager.controllerFor(Capability.GESTURE))
    }

    @Test
    fun disabledModeIsExcludedButStillRegistered() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))
        manager.register(accessibility)

        manager.setDisabledModes(setOf(ControlMode.ACCESSIBILITY))

        assertNull(manager.controllerFor(Capability.GESTURE))
        assertFalse(manager.anyReady())
        assertEquals(emptySet<ControlMode>(), manager.effectiveReadyModes.value)
        assertEquals(setOf(ControlMode.ACCESSIBILITY), manager.readyModes.value)
        assertEquals(1, manager.all().size)
    }

    @Test
    fun groupPreferenceAppliesToReadAndOperateCapabilities() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE, Capability.SCREENSHOT))
        val shizuku = controller(ControlMode.SHIZUKU, setOf(Capability.GESTURE, Capability.SCREENSHOT))
        manager.register(accessibility)
        manager.register(shizuku)

        manager.operateMode = ControlMode.SHIZUKU
        manager.readMode = ControlMode.ACCESSIBILITY

        assertSame(shizuku, manager.controllerFor(Capability.GESTURE))
        assertSame(accessibility, manager.controllerFor(Capability.SCREENSHOT))
        assertEquals(ControlMode.SHIZUKU, manager.preferredFor(Capability.GESTURE))
        assertEquals(ControlMode.ACCESSIBILITY, manager.preferredFor(Capability.SCREENSHOT))
    }

    @Test
    fun capabilityPreferenceOverridesGroupPreference() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))
        val shizuku = controller(ControlMode.SHIZUKU, setOf(Capability.GESTURE))
        manager.register(accessibility)
        manager.register(shizuku)
        manager.operateMode = ControlMode.SHIZUKU
        manager.setCapabilityPreferences(mapOf(Capability.GESTURE to ControlMode.ACCESSIBILITY))

        assertSame(accessibility, manager.controllerFor(Capability.GESTURE))
    }

    @Test
    fun clearingCapabilityPreferencesFallsBackToGroupPreference() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))
        val shizuku = controller(ControlMode.SHIZUKU, setOf(Capability.GESTURE))
        manager.register(accessibility)
        manager.register(shizuku)
        manager.operateMode = ControlMode.SHIZUKU
        manager.setCapabilityPreferences(mapOf(Capability.GESTURE to ControlMode.ACCESSIBILITY))

        manager.setCapabilityPreferences(emptyMap())

        assertSame(shizuku, manager.controllerFor(Capability.GESTURE))
    }

    @Test
    fun preservesRegistrationOrderWithoutPreference() {
        val manager = ControllerManager()
        val first = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))
        val second = controller(ControlMode.SHIZUKU, setOf(Capability.GESTURE))
        manager.register(first)
        manager.register(second)

        assertSame(first, manager.controllerFor(Capability.GESTURE))
    }

    @Test
    fun preferredBackendWithoutCapabilityFallsBack() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE))
        val shizuku = controller(ControlMode.SHIZUKU, emptySet())
        manager.register(accessibility)
        manager.register(shizuku)
        manager.operateMode = ControlMode.SHIZUKU

        assertSame(accessibility, manager.controllerFor(Capability.GESTURE))
    }

    @Test
    fun displayTargetOnlySelectsSupportingBackend() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE), displayTarget = false)
        val shizuku = controller(ControlMode.SHIZUKU, setOf(Capability.GESTURE), displayTarget = true)
        manager.register(accessibility)
        manager.register(shizuku)

        manager.targetDisplayId = 2

        assertSame(shizuku, manager.controllerFor(Capability.GESTURE))
    }

    @Test
    fun displayTargetWithoutSupportingBackendReturnsNull() {
        val manager = ControllerManager()
        manager.register(
            controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE), displayTarget = false),
        )

        manager.targetDisplayId = 2

        assertNull(manager.controllerFor(Capability.GESTURE))
    }

    @Test
    fun clearingDisplayTargetRestoresPrimarySelection() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE), displayTarget = false)
        val shizuku = controller(ControlMode.SHIZUKU, setOf(Capability.GESTURE), displayTarget = true)
        manager.register(accessibility)
        manager.register(shizuku)
        manager.targetDisplayId = 2
        assertSame(shizuku, manager.controllerFor(Capability.GESTURE))

        manager.targetDisplayId = null

        assertSame(accessibility, manager.controllerFor(Capability.GESTURE))
    }

    @Test
    fun capabilitiesByModeReflectsDeclaredCapabilities() {
        val manager = ControllerManager()
        manager.register(controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE)))
        manager.register(controller(ControlMode.SHIZUKU, setOf(Capability.SHELL, Capability.SCREENSHOT)))

        val byMode = manager.capabilitiesByMode()

        assertEquals(setOf(Capability.GESTURE), byMode[ControlMode.ACCESSIBILITY])
        assertEquals(setOf(Capability.SHELL, Capability.SCREENSHOT), byMode[ControlMode.SHIZUKU])
    }

    @Test
    fun refreshPublishesReadinessChanges() {
        val manager = ControllerManager()
        val accessibility = controller(ControlMode.ACCESSIBILITY, setOf(Capability.GESTURE), ready = true)
        manager.register(accessibility)

        accessibility.setReady(false)

        assertEquals(setOf(ControlMode.ACCESSIBILITY), manager.readyModes.value)
        manager.refresh()
        assertEquals(emptySet<ControlMode>(), manager.readyModes.value)
    }
}
