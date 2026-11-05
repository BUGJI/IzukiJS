package com.benton.izukijs.privileged.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPackageResolverTest {

    @Test
    fun shellUidPrefersShellPackage() {
        assertEquals(
            "com.android.shell",
            DisplayPackageResolver.resolve(
                DisplayPackageResolver.SHELL_UID,
                listOf("com.benton.izukijs", "com.android.shell"),
                "com.benton.izukijs",
            ),
        )
    }

    @Test
    fun shellUidUsesInstalledPackageWhenShellMissing() {
        assertEquals(
            "com.android.shell.alias",
            DisplayPackageResolver.resolve(
                DisplayPackageResolver.SHELL_UID,
                listOf("com.android.shell.alias"),
                "com.benton.izukijs",
            ),
        )
    }

    @Test
    fun shellUidFallsBackWhenPackagesMissing() {
        assertEquals(
            "com.android.shell",
            DisplayPackageResolver.resolve(
                DisplayPackageResolver.SHELL_UID,
                null,
                "com.benton.izukijs",
            ),
        )
    }

    @Test
    fun systemUidPrefersAndroidPackage() {
        assertEquals(
            "android",
            DisplayPackageResolver.resolve(
                DisplayPackageResolver.SYSTEM_UID,
                emptyList(),
                "com.benton.izukijs",
            ),
        )
    }

    @Test
    fun otherUidUsesFirstInstalledPackage() {
        assertEquals(
            "com.benton.izukijs",
            DisplayPackageResolver.resolve(
                10123,
                listOf("  com.benton.izukijs  ", ""),
                "fallback",
            ),
        )
    }

    @Test
    fun otherUidWithoutPackagesUsesFallback() {
        assertEquals(
            "fallback",
            DisplayPackageResolver.resolve(10123, null, "fallback"),
        )
    }
}
