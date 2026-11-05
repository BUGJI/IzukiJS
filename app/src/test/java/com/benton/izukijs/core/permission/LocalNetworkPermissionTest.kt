package com.benton.izukijs.core.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {

    @Test
    fun requiredFromAndroid17() {
        assertFalse(LocalNetworkPermission.isRequired(36))
        assertTrue(LocalNetworkPermission.isRequired(37))
        assertTrue(LocalNetworkPermission.isRequired(38))
    }
}
