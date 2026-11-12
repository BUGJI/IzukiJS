package com.benton.izukijs.controller.record

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingUserActionTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun roundTripsUserActionWithContext() {
        val event = UserActionEvent(
            timestamp = 10L,
            kind = UserActionKind.TAP,
            x = 12,
            y = 34,
            nx = 0.1,
            ny = 0.2,
            durationMs = 90L,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
            packageName = "com.example",
            targets = listOf(
                UiTarget(
                    className = "android.widget.Button",
                    text = "登录",
                    viewId = "btn_login",
                    contentDescription = null,
                    left = 10,
                    top = 20,
                    right = 100,
                    bottom = 80,
                    clickable = true,
                    editable = false,
                ),
            ),
            beforeFrame = "frames/frame_00001.jpg",
            inputSource = "shizuku",
        )

        val decoded = RecordEvent.fromJson(event.toJson()) as UserActionEvent
        assertEquals(event.toJson().toString(), decoded.toJson().toString())
        assertEquals("user", event.toJson().optString("source"))
        assertEquals("登录", decoded.targets.first().text)
    }

    @Test
    fun exporterPrefersTargetText() {
        val meta = RecordingMeta(id = "id", name = "demo", kind = RecordingMeta.KIND_USER)
        val reader = RecordingReader(
            meta = meta,
            dir = File("."),
            events = listOf(
                UserActionEvent(
                    timestamp = 100L,
                    kind = UserActionKind.TAP,
                    x = 50,
                    y = 60,
                    targets = listOf(
                        UiTarget(
                            className = "android.widget.Button",
                            text = "提交",
                            viewId = null,
                            contentDescription = null,
                            left = 10,
                            top = 20,
                            right = 100,
                            bottom = 80,
                            clickable = true,
                            editable = false,
                        ),
                    ),
                ),
            ),
        )

        val script = RecordingScriptExporter.build(reader)
        assertTrue(script.contains("selector.clickByText(\"提交\")"))
    }

    @Test
    fun resolvesCrossProcessEventWallTime() {
        val wall = resolveEventWall(
            nowWall = 1_000_000L,
            nowUptime = 50_000L,
            sessionStartWall = 990_000L,
            sessionStartUptime = 40_000L,
            batchStartUptime = 40_100L,
            tMs = 1_500L,
        )
        assertEquals(991_600L, wall)
    }

    @Test
    fun fallsBackWhenPrivilegedClockIsMissingOrStale() {
        val missing = resolveEventWall(
            nowWall = 1_000_000L,
            nowUptime = 50_000L,
            sessionStartWall = 990_000L,
            sessionStartUptime = 40_000L,
            batchStartUptime = 0L,
            tMs = 1_500L,
        )
        assertEquals(991_500L, missing)

        val stale = resolveEventWall(
            nowWall = 1_000_000L,
            nowUptime = 50_000L,
            sessionStartWall = 990_000L,
            sessionStartUptime = 40_000L,
            batchStartUptime = 1_000L,
            tMs = 500L,
        )
        assertEquals(990_500L, stale)
    }

    @Test
    fun storeDerivesUserKindFromEvents() {
        val store = RecordingStore(temporary.newFolder("recordings"))
        val writer = store.create("demo", kind = RecordingMeta.KIND_USER)
        writer.setInputSource("shizuku")
        writer.record(
            UserActionEvent(
                timestamp = 1L,
                kind = UserActionKind.TAP,
                x = 1,
                y = 2,
                inputSource = "shizuku",
            ),
        )
        writer.close()

        val info = store.info(writer.id)
        assertEquals(RecordingMeta.KIND_USER, info?.kind)
        assertEquals("shizuku", info?.inputSource)
    }
}
