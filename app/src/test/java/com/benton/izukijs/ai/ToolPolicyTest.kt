package com.benton.izukijs.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ToolPolicy] 的黑名单匹配测试。 */
class ToolPolicyTest {

    @Test
    fun blocksExactPackageIgnoringCase() {
        val policy = ToolPolicy(blockedPackages = listOf("com.bank.app"))
        assertTrue(policy.isPackageBlocked("com.bank.app"))
        assertTrue(policy.isPackageBlocked("COM.BANK.APP"))
        assertFalse(policy.isPackageBlocked("com.bank.app2"))
    }

    @Test
    fun blocksWildcardPrefix() {
        val policy = ToolPolicy(blockedPackages = listOf("com.bank.*"))
        assertTrue(policy.isPackageBlocked("com.bank.wallet"))
        assertTrue(policy.isPackageBlocked("com.bank.secure.login"))
        assertFalse(policy.isPackageBlocked("com.other.bank"))
    }

    @Test
    fun blankEntriesAndTargetsAreNotBlocked() {
        val policy = ToolPolicy(blockedPackages = listOf("", "  ", "com.bank.app"))
        assertFalse(policy.isPackageBlocked(""))
        assertFalse(policy.isPackageBlocked("   "))
        assertTrue(policy.isPackageBlocked("com.bank.app"))
    }

    @Test
    fun emptyBlacklistAllowsEverything() {
        val policy = ToolPolicy()
        assertFalse(policy.isPackageBlocked("com.any.app"))
        assertFalse(policy.isPackageBlocked(""))
    }
}
