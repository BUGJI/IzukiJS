package com.benton.izukijs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheImageStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun countsOnlyKnownCacheImages() {
        val dir = temporary.newFolder("cache")
        image(dir, "screenshot_1.png", 10)
        image(dir, "template_2.PNG", 20)
        image(dir, "izuki_shot.png", 30)
        image(dir, "other.png", 40)
        File(dir, "keep.txt").writeText("keep")

        val stats = CacheImageStore(dir).stats()

        assertEquals(3, stats.count)
        assertEquals(60L, stats.bytes)
    }

    @Test
    fun clearDeletesOnlyKnownCacheImages() {
        val dir = temporary.newFolder("cache")
        image(dir, "screenshot_1.png", 10)
        image(dir, "template_2.jpg", 20)
        image(dir, "izuki_shot.png", 30)
        val other = image(dir, "other.png", 40)
        val keep = File(dir, "keep.txt").apply { writeText("keep") }

        val cleared = CacheImageStore(dir).clear()

        assertEquals(3, cleared.count)
        assertEquals(60L, cleared.bytes)
        assertFalse(File(dir, "screenshot_1.png").exists())
        assertFalse(File(dir, "template_2.jpg").exists())
        assertFalse(File(dir, "izuki_shot.png").exists())
        assertTrue(other.exists())
        assertTrue(keep.exists())
        assertEquals(CacheImageStore.Stats(0, 0L), CacheImageStore(dir).stats())
    }

    private fun image(dir: File, name: String, bytes: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }
}
