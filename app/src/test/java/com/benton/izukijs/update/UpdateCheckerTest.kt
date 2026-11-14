package com.benton.izukijs.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun comparesNumericSegments() {
        assertTrue(UpdateChecker.compareVersions("1.2.10", "1.2.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.2.9", "1.2.10") < 0)
    }

    @Test
    fun ignoresPrefixPreReleaseAndBuildMetadata() {
        assertEquals(0, UpdateChecker.compareVersions("v1.3.0", "1.3.0"))
        assertEquals(0, UpdateChecker.compareVersions("V1.3.0", "1.3.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.3.0-beta.1", "1.3.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.3.0+build.7", "1.3.0"))
    }

    @Test
    fun treatsMissingSegmentsAsZero() {
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateChecker.compareVersions("1.2.0.1", "1.2") > 0)
        assertTrue(UpdateChecker.compareVersions("1.2", "1.2.0.1") < 0)
    }

    @Test
    fun treatsNonNumericSegmentsAsZero() {
        assertEquals(0, UpdateChecker.compareVersions("1.x.2", "1.0.2"))
        assertEquals(0, UpdateChecker.compareVersions("1.2.3", "1.2.3.0"))
    }

    @Test
    fun normalizesWhitespaceAndPrefix() {
        assertEquals(listOf(1, 2, 3), UpdateChecker.normalizeVersion(" V1.2.3 "))
        assertEquals(listOf(1, 2), UpdateChecker.normalizeVersion("1.2-x"))
    }
}
