package com.benton.izukijs.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    @Test
    fun selectsCanonicalAbiAsset() {
        val names = listOf(
            "izukijs-arm64-v8a-release.apk",
            "izukijs-universal-release.apk",
        )

        assertEquals(
            "izukijs-arm64-v8a-release.apk",
            UpdateChecker.selectAssetName(names, abis),
        )
    }

    @Test
    fun selectsLegacyAbiAsset() {
        val names = listOf(
            "app-arm64-v8a-release.apk",
            "app-universal-release.apk",
        )

        assertEquals(
            "app-arm64-v8a-release.apk",
            UpdateChecker.selectAssetName(names, abis),
        )
    }

    @Test
    fun prefersAbiAssetOverUniversal() {
        val names = listOf(
            "izukijs-universal-release.apk",
            "izukijs-armeabi-v7a-release.apk",
        )

        assertEquals(
            "izukijs-armeabi-v7a-release.apk",
            UpdateChecker.selectAssetName(names, abis),
        )
    }

    @Test
    fun fallsBackToUniversalWhenNoAbiMatches() {
        val names = listOf("izukijs-universal-release.apk")

        assertEquals(
            "izukijs-universal-release.apk",
            UpdateChecker.selectAssetName(names, listOf("x86")),
        )
    }

    @Test
    fun respectsSupportedAbiOrder() {
        val names = listOf(
            "izukijs-armeabi-v7a-release.apk",
            "izukijs-arm64-v8a-release.apk",
        )

        assertEquals(
            "izukijs-arm64-v8a-release.apk",
            UpdateChecker.selectAssetName(names, abis),
        )
    }

    @Test
    fun returnsNullWhenNoAssetMatches() {
        val names = listOf("izukijs-arm64-v8a-release.apk.asc", "checksums.txt")

        assertNull(UpdateChecker.selectAssetName(names, abis))
    }
}
