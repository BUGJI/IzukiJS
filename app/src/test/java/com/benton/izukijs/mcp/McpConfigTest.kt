package com.benton.izukijs.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [McpConfig.isPackageBlocked] 的黑名单匹配测试。 */
class McpConfigTest {

    private val config = McpConfig(
        blockedPackages = listOf("com.example.bank", "com.pay.*", "  "),
    )

    @Test
    fun exactMatchIsCaseInsensitive() {
        assertTrue(config.isPackageBlocked("com.example.bank"))
        assertTrue(config.isPackageBlocked("COM.EXAMPLE.BANK"))
    }

    @Test
    fun wildcardMatchesPrefix() {
        assertTrue(config.isPackageBlocked("com.pay.wallet"))
        assertFalse(config.isPackageBlocked("com.pay"))
    }

    @Test
    fun unrelatedOrBlankIsNotBlocked() {
        assertFalse(config.isPackageBlocked("com.other.app"))
        assertFalse(config.isPackageBlocked(""))
        assertFalse(config.isPackageBlocked("   "))
    }

    @Test
    fun emptyListBlocksNothing() {
        assertFalse(McpConfig().isPackageBlocked("com.anything"))
    }
}
