package com.benton.izukijs.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    @Test
    fun offlineBlocksDownload() {
        assertEquals(UpdateFailure.Network, downloadBlockReason(UpdateNetwork.NONE, wifiOnly = false))
    }

    @Test
    fun wifiOnlyBlocksMeteredNetwork() {
        assertEquals(
            UpdateFailure.WifiRequired,
            downloadBlockReason(UpdateNetwork.METERED, wifiOnly = true),
        )
    }

    @Test
    fun wifiOnlyAllowsUnmeteredNetwork() {
        assertNull(downloadBlockReason(UpdateNetwork.UNMETERED, wifiOnly = true))
    }

    @Test
    fun meteredNetworkAllowedWhenWifiOnlyOff() {
        assertNull(downloadBlockReason(UpdateNetwork.METERED, wifiOnly = false))
    }

    @Test
    fun missingExpectedSizeSkipsVerification() {
        assertTrue(matchesExpectedSize(actualBytes = 123L, expectedBytes = 0L))
        assertTrue(matchesExpectedSize(actualBytes = 123L, expectedBytes = -1L))
    }

    @Test
    fun matchingSizePasses() {
        assertTrue(matchesExpectedSize(actualBytes = 456L, expectedBytes = 456L))
    }

    @Test
    fun sizeMismatchIsDetected() {
        assertFalse(matchesExpectedSize(actualBytes = 123L, expectedBytes = 456L))
    }
}
