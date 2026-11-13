package com.benton.izukijs.controller.record

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun writesLoadsListsAndDeletes() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val writer = store.create("demo")
        writer.record(ClickEvent(0, 1, 2, true))
        writer.record(ScreenshotEvent(10, "frames/1.jpg", 100, 200))
        writer.record(InputTextEvent(20, "hi", "AUTO", true))
        writer.close()

        val loaded = store.load(writer.id)
        assertNotNull(loaded)
        assertEquals(3, loaded!!.events.size)
        assertTrue(loaded.events[0] is ClickEvent)
        assertTrue(loaded.events[1] is ScreenshotEvent)
        assertEquals(3, loaded.meta.eventCount)

        val list = store.list()
        assertEquals(1, list.size)
        assertEquals("demo", list[0].name)
        assertEquals(writer.id, list[0].id)

        val info = store.info(writer.id)
        assertEquals("demo", info?.name)

        assertTrue(store.delete(writer.id))
        assertNull(store.load(writer.id))
    }

    @Test
    fun reportsEntryAndTotalSize() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val first = store.create("first")
        first.record(ClickEvent(0, 1, 2, true))
        first.close()
        val second = store.create("second")
        second.record(ClickEvent(0, 3, 4, true))
        second.close()

        val entries = store.entries()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.sizeBytes > 0L })
        assertEquals(entries.sumOf { it.sizeBytes }, store.totalSizeBytes())
        assertEquals(store.sizeBytes(first.id), entries.first { it.meta.id == first.id }.sizeBytes)
    }

    @Test
    fun closeIsIdempotentAndIgnoresWritesAfterClose() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val writer = store.create("demo")
        writer.record(ClickEvent(0, 1, 2, true))
        writer.close()
        writer.close()
        writer.record(ClickEvent(1, 3, 4, true))

        val loaded = store.load(writer.id)
        assertNotNull(loaded)
        assertEquals(1, loaded!!.events.size)
        assertTrue(loaded.events.first() is ClickEvent)
    }

    @Test
    fun mergesContextIntoUserAction() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val writer = store.create("demo", kind = RecordingMeta.KIND_USER)
        val seq = writer.record(
            UserActionEvent(timestamp = 10L, kind = UserActionKind.TAP, x = 1, y = 2),
        )
        writer.record(
            ContextEvent(
                timestamp = 20L,
                refSeq = seq,
                packageName = "com.example",
                targets = listOf(
                    UiTarget(
                        className = "android.widget.Button",
                        text = "登录",
                        viewId = "btn_login",
                        contentDescription = null,
                        left = 0,
                        top = 0,
                        right = 10,
                        bottom = 10,
                        clickable = true,
                        editable = false,
                    ),
                ),
            ),
        )
        writer.close()

        val action = store.load(writer.id)!!.events
            .filterIsInstance<UserActionEvent>()
            .first()
        assertEquals("com.example", action.packageName)
        assertEquals("登录", action.targets.first().text)
    }

    @Test
    fun deriveMetaCountsUniqueFrames() {
        val root = temporary.newFolder("recordings")
        val dir = File(root, "abc").apply { mkdirs() }
        File(dir, "events.jsonl").writeText(
            """
            {"v":2,"type":"screencap","seq":1,"atMs":0,"frame":"frames/a.jpg","width":10,"height":20}
            {"v":2,"type":"screencap","seq":2,"atMs":5,"frame":"frames/a.jpg","width":10,"height":20}
            """.trimIndent() + "\n",
        )

        val meta = RecordingStore(root).info("abc")

        assertNotNull(meta)
        assertEquals(1, meta!!.frameCount)
        assertEquals(2, meta.eventCount)
    }

    @Test
    fun deletesAllRecordings() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val first = store.create("first")
        first.record(ClickEvent(0, 1, 2, true))
        first.close()
        val second = store.create("second")
        second.record(ClickEvent(0, 3, 4, true))
        second.close()

        assertEquals(2, store.deleteAll())
        assertTrue(store.list().isEmpty())
        assertEquals(0L, store.totalSizeBytes())
    }

    @Test
    fun rejectsPathTraversalIds() {
        val root = temporary.newFolder("recordings")
        val parent = root.parentFile!!
        val sentinel = File(parent, "sentinel.txt").apply { writeText("keep") }
        File(parent, "events.jsonl").writeText("""{"type":"click"}""")
        val store = RecordingStore(root)

        assertNull(store.load(".."))
        assertNull(store.info(".."))
        assertFalse(store.delete(".."))
        assertFalse(store.delete("."))
        assertTrue(sentinel.exists())
    }

    @Test
    fun cursorConsumesEventsInOrder() {
        val events = listOf(
            ScreenshotEvent(0, "frames/1.jpg", 10, 10),
            ClickEvent(1, 2, 3, true),
            ScreenshotEvent(2, "frames/2.jpg", 10, 10),
            InputTextEvent(3, "hi", "AUTO", true),
        )
        val cursor = RecordingCursor(events)

        assertEquals("frames/1.jpg", cursor.nextScreenshot()?.frame)
        assertTrue(cursor.nextAction(RecordActionKind.CLICK) is ClickEvent)
        assertEquals("frames/2.jpg", cursor.nextScreenshot()?.frame)
        assertTrue(cursor.nextAction(RecordActionKind.INPUT_TEXT) is InputTextEvent)
        assertEquals(0, cursor.remaining())
        assertTrue(cursor.mismatches.isEmpty())
    }

    @Test
    fun cursorRecordsMismatches() {
        val cursor = RecordingCursor(
            listOf(
                ScreenshotEvent(0, "frames/1.jpg", 10, 10),
                PressKeyEvent(1, 4, true),
            ),
        )
        cursor.nextAction(RecordActionKind.CLICK)
        assertTrue(cursor.mismatches.isNotEmpty())
    }

    @Test
    fun repairsIncompleteRecordingMetadataFromEvents() {
        val root = temporary.newFolder("recordings")
        val dir = File(root, "crashed").apply { mkdirs() }
        File(dir, "meta.json").writeText(
            """{"id":"crashed","name":"crashed-rec","complete":false,"eventCount":0,"frameCount":0,"durationMs":0}""",
        )
        File(dir, "events.jsonl").writeText(
            """
            {"v":2,"type":"click","seq":1,"atMs":10,"x":1,"y":2}
            {"v":2,"type":"screencap","seq":2,"atMs":20,"frame":"frames/a.jpg","width":100,"height":200}
            {"v":2,"type":"context","seq":3,"atMs":500,"refSeq":1,"package":"com.example"}
            {"v":2,"type":"click","seq":4,"atMs":100,"x":3,"y":4}
            """.trimIndent() + "\n",
        )

        val meta = RecordingStore(root).info("crashed")

        assertNotNull(meta)
        assertEquals("crashed-rec", meta!!.name)
        assertEquals(4, meta.eventCount)
        assertEquals(1, meta.frameCount)
        assertEquals(90L, meta.durationMs)
        assertTrue(meta.framesAvailable)
        assertFalse(meta.complete)
    }

    @Test
    fun renameUpdatesListedName() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val writer = store.create("old")
        writer.record(ClickEvent(0, 1, 2, true))
        writer.close()

        assertTrue(store.rename(writer.id, "new name"))
        assertEquals("new name", store.list().single().name)
        assertFalse(store.rename(writer.id, "   "))
        assertEquals("new name", store.list().single().name)
    }

    @Test
    fun summaryStreamsCountsWithoutLoadingEvents() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val writer = store.create("summary")
        writer.record(
            UserActionEvent(timestamp = 0, kind = UserActionKind.TAP, x = 1, y = 2, packageName = "com.a"),
        )
        writer.record(
            AppEvent(timestamp = 10, action = "start", packageName = "com.b", success = true),
        )
        writer.record(ScreenshotEvent(timestamp = 20, frame = "frames/a.jpg", width = 1, height = 1))
        writer.record(ContextEvent(timestamp = 200, refSeq = 1, packageName = "com.a"))
        writer.close()

        val summary = store.summary(writer.id)
        assertNotNull(summary)
        assertEquals(1, summary!!.userActions)
        assertEquals(1, summary.scriptActions)
        assertEquals(1, summary.frames)
        assertEquals(4, summary.eventCount)
        assertEquals(listOf("com.a", "com.b"), summary.packages)
        // context 不计入时长。
        assertEquals(20L, summary.durationMs)
    }

    @Test
    fun mergesAfterFrameFromContextEvent() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val writer = store.create("after-frame")
        val seq = writer.record(
            UserActionEvent(timestamp = 10, kind = UserActionKind.TAP, x = 1, y = 2),
        )
        writer.record(
            ContextEvent(
                timestamp = 220,
                refSeq = seq,
                afterFrame = "frames/after.jpg",
            ),
        )
        writer.close()

        val action = store.load(writer.id)!!.events
            .filterIsInstance<UserActionEvent>()
            .first()
        assertEquals("frames/after.jpg", action.afterFrame)
    }

    @Test
    fun frameCapIsReportedAsTruncated() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val writer = store.create("capped")
        writer.markFrameCapReached()
        writer.record(ClickEvent(0, 1, 2, true))
        writer.close()

        val loaded = store.load(writer.id)
        assertNotNull(loaded)
        assertTrue(loaded!!.meta.truncated)
    }

    @Test
    fun renameFailsForMissingRecording() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        assertFalse(store.rename("missing", "name"))
    }

    @Test
    fun sampleSizeKeepsLongestEdgeAtOrAboveTarget() {
        assertEquals(1, recordingSampleSize(0, 0, 300))
        assertEquals(1, recordingSampleSize(100, 200, 0))
        assertEquals(1, recordingSampleSize(100, 200, 400))
        assertEquals(2, recordingSampleSize(100, 200, 100))
        assertEquals(4, recordingSampleSize(1080, 2400, 360))
    }
}
