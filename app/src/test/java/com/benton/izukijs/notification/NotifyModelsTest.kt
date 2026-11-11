package com.benton.izukijs.notification

import com.benton.izukijs.core.notification.NotificationSettings
import com.benton.izukijs.core.notification.NotificationSettingsCodec
import com.benton.izukijs.core.notification.NotifyChannel
import com.benton.izukijs.core.notification.NotifyChannelType
import com.benton.izukijs.core.notification.NotifyEvent
import com.benton.izukijs.core.notification.NotifyFilters
import com.benton.izukijs.core.notification.NotifyKind
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.core.notification.NotifySource
import com.benton.izukijs.core.notification.NotifyTemplate
import com.benton.izukijs.runtime.RunTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyModelsTest {

    private fun event(
        kind: NotifyKind = NotifyKind.SCRIPT,
        level: NotifyLevel = NotifyLevel.INFO,
        source: NotifySource = NotifySource.SCRIPT,
    ) = NotifyEvent(
        level = level,
        kind = kind,
        title = "标题",
        text = "正文",
        source = source,
        scriptId = "script-1",
        scriptName = "示例脚本",
        runId = "run-1",
        tags = setOf(kind.key, level.key),
    )

    @Test
    fun defaultsContainLocalChannel() {
        val settings = NotificationSettings().withDefaults()
        assertEquals(1, settings.channels.size)
        assertEquals(NotifyChannelType.LOCAL, settings.channels.first().type)
        assertTrue(settings.enabled)
        assertTrue(settings.runAuto)
        assertTrue(settings.runManual)
        assertEquals(NotifyLevel.WARN, settings.runMinLevel)
    }

    @Test
    fun codecRoundTripPreservesChannels() {
        val settings = NotificationSettings(
            runManual = true,
            runMinLevel = NotifyLevel.INFO,
            channels = listOf(
                NotifyChannel.local(),
                NotifyChannel(
                    id = "tg",
                    type = NotifyChannelType.TELEGRAM,
                    name = "Telegram",
                    minLevel = NotifyLevel.WARN,
                    kinds = setOf(NotifyKind.RUN_AUTO, NotifyKind.FLOW_SUMMARY),
                    botToken = "token",
                    chatId = "42",
                ),
                NotifyChannel(
                    id = "hook",
                    type = NotifyChannelType.WEBHOOK,
                    url = "https://example.com/hook",
                    headers = "Authorization: Bearer x",
                    bodyTemplate = "{\"text\":\"{{text}}\"}",
                ),
            ),
        )
        val restored = NotificationSettingsCodec.decode(
            NotificationSettingsCodec.encode(settings),
        )
        assertEquals(3, restored.channels.size)
        assertTrue(restored.runManual)
        assertEquals(NotifyLevel.INFO, restored.runMinLevel)
        val telegram = restored.channels.first { it.id == "tg" }
        assertEquals("token", telegram.botToken)
        assertEquals("42", telegram.chatId)
        assertEquals(setOf(NotifyKind.RUN_AUTO, NotifyKind.FLOW_SUMMARY), telegram.kinds)
        val hook = restored.channels.first { it.id == "hook" }
        assertEquals("https://example.com/hook", hook.url)
        assertEquals("Authorization: Bearer x", hook.headers)
    }

    @Test
    fun runResultDefaultsToFailuresOnly() {
        val settings = NotificationSettings()
        assertTrue(
            NotifyFilters.eventAllowed(
                settings,
                event(NotifyKind.RUN_AUTO, NotifyLevel.ERROR, NotifySource.SCHEDULE),
            ),
        )
        assertFalse(
            NotifyFilters.eventAllowed(
                settings,
                event(NotifyKind.RUN_AUTO, NotifyLevel.SUCCESS, NotifySource.SCHEDULE),
            ),
        )
        assertTrue(
            NotifyFilters.eventAllowed(
                settings,
                event(NotifyKind.RUN_MANUAL, NotifyLevel.ERROR, NotifySource.MANUAL),
            ),
        )
        assertFalse(
            NotifyFilters.eventAllowed(
                settings,
                event(NotifyKind.RUN_MANUAL, NotifyLevel.SUCCESS, NotifySource.MANUAL),
            ),
        )
        assertTrue(
            NotifyFilters.eventAllowed(settings, event(NotifyKind.SCRIPT, NotifyLevel.INFO)),
        )
    }

    @Test
    fun flowStepEventsFollowFlowStepsSwitch() {
        val settings = NotificationSettings()
        assertFalse(
            NotifyFilters.eventAllowed(
                settings,
                event(NotifyKind.RUN_AUTO, NotifyLevel.ERROR, NotifySource.FLOW),
            ),
        )
        assertTrue(
            NotifyFilters.eventAllowed(
                settings.copy(flowSteps = true),
                event(NotifyKind.RUN_AUTO, NotifyLevel.ERROR, NotifySource.FLOW),
            ),
        )
    }

    @Test
    fun flowSummaryHonoursLevelThreshold() {
        val settings = NotificationSettings()
        assertTrue(
            NotifyFilters.eventAllowed(
                settings,
                event(NotifyKind.FLOW_SUMMARY, NotifyLevel.WARN, NotifySource.FLOW),
            ),
        )
        assertFalse(
            NotifyFilters.eventAllowed(
                settings,
                event(NotifyKind.FLOW_SUMMARY, NotifyLevel.SUCCESS, NotifySource.FLOW),
            ),
        )
    }

    @Test
    fun channelFiltersByLevelKindAndTags() {
        val channel = NotifyChannel(
            id = "webhook",
            type = NotifyChannelType.WEBHOOK,
            minLevel = NotifyLevel.WARN,
            kinds = setOf(NotifyKind.RUN_AUTO),
        )
        assertTrue(
            NotifyFilters.channelAccepts(
                channel,
                event(NotifyKind.RUN_AUTO, NotifyLevel.ERROR, NotifySource.SCHEDULE),
            ),
        )
        assertFalse(
            NotifyFilters.channelAccepts(
                channel,
                event(NotifyKind.RUN_AUTO, NotifyLevel.INFO, NotifySource.SCHEDULE),
            ),
        )
        assertFalse(
            NotifyFilters.channelAccepts(
                channel,
                event(NotifyKind.SCRIPT, NotifyLevel.ERROR),
            ),
        )

        val tagged = channel.copy(tags = setOf("deploy"))
        assertFalse(
            NotifyFilters.channelAccepts(
                tagged,
                event(NotifyKind.RUN_AUTO, NotifyLevel.ERROR, NotifySource.SCHEDULE),
            ),
        )
        assertTrue(
            NotifyFilters.channelAccepts(
                tagged,
                event(NotifyKind.RUN_AUTO, NotifyLevel.ERROR, NotifySource.SCHEDULE)
                    .copy(tags = setOf("deploy")),
            ),
        )
    }

    @Test
    fun templateRendersKnownPlaceholders() {
        val rendered = NotifyTemplate.render(
            "{{title}}|{{script}}|{{level}}|{{data.succeeded}}|{{unknown}}",
            event().copy(data = mapOf("succeeded" to "3")),
        )
        assertEquals("标题|示例脚本|info|3|{{unknown}}", rendered)
    }

    @Test
    fun runTriggerMapsToManualAndAutomaticKinds() {
        assertEquals(NotifyKind.RUN_MANUAL, RunTrigger.MANUAL.kind)
        assertEquals(NotifyKind.RUN_MANUAL, RunTrigger.TILE.kind)
        assertEquals(NotifyKind.RUN_AUTO, RunTrigger.SCHEDULE.kind)
        assertEquals(NotifyKind.RUN_AUTO, RunTrigger.FLOW.kind)
        assertEquals(NotifyKind.RUN_AUTO, RunTrigger.MCP.kind)
        assertEquals(NotifySource.MCP, RunTrigger.MCP.source)
    }

    @Test
    fun parsesHeaderLines() {
        val headers = HttpTransport.parseHeaders(
            "Authorization: Bearer abc\n# comment\nX-Empty:\n\nContent-Type: text/plain",
        )
        assertEquals("Bearer abc", headers["Authorization"])
        assertEquals("text/plain", headers["Content-Type"])
        assertFalse(headers.containsKey("X-Empty"))
    }

    @Test
    fun runDedupeKeyGroupsByScript() {
        val first = event(NotifyKind.RUN_AUTO, NotifyLevel.ERROR, NotifySource.SCHEDULE)
        val second = first.copy(runId = "run-2", timestamp = first.timestamp + 1)
        assertEquals(first.dedupeKey, second.dedupeKey)
    }
}
