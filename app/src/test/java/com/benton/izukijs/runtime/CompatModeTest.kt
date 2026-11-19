package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatModeTest {

    @Test
    fun detectsHeader() {
        val analysis = CompatMode.analyze(
            """
            // @compat autojs6
            toastLog("hi")
            """.trimIndent(),
        )
        assertTrue(analysis.enabled)
        assertEquals("autojs6", analysis.flavor)
        assertTrue(analysis.unsupportedDirectives.isEmpty())
    }

    @Test
    fun normalizesAliases() {
        assertEquals("autojs6", CompatMode.analyze("// @compat autox\n").flavor)
        assertEquals("autojs6", CompatMode.analyze("// @compat autojs\n").flavor)
    }

    @Test
    fun unknownFlavorIsDisabledWithIssue() {
        val analysis = CompatMode.analyze("// @compat something-else\n")
        assertFalse(analysis.enabled)
        assertEquals("unsupported-flavor", analysis.issues.firstOrNull()?.code)
    }

    @Test
    fun readsLeadingDirectives() {
        val analysis = CompatMode.analyze(
            """
            "ui";
            "auto";
            text("登录").findOne();
            """.trimIndent(),
        )
        assertEquals(setOf("ui", "auto"), analysis.directives)
        assertTrue(analysis.unsupportedDirectives.contains("ui"))
        assertEquals("unsupported-directive", analysis.issues.firstOrNull()?.code)
    }

    @Test
    fun splitsCombinedDirectives() {
        val analysis = CompatMode.analyze("\"ui auto\";\nlog('x')")
        assertEquals(setOf("ui", "auto"), analysis.directives)
    }

    @Test
    fun ignoresUseStrict() {
        val analysis = CompatMode.analyze("'use strict';\nvar x = 1")
        assertTrue(analysis.directives.isEmpty())
    }

    @Test
    fun detectsJavaInterop() {
        val signals = CompatMode.detectLegacySignals("importClass(android.content.Intent);")
        assertTrue(signals.any { it.code == "java-interop" })
    }

    @Test
    fun ignoresCommentsAndStrings() {
        val signals = CompatMode.detectLegacySignals(
            """
            // text("登录").findOne();
            var text = "importClass is not code";
            log("ok");
            """.trimIndent(),
        )
        assertTrue(signals.isEmpty())
    }

    @Test
    fun detectsDevicePropertyButNotCall() {
        assertTrue(
            CompatMode.detectLegacySignals("var w = device.width + 1;")
                .any { it.code == "legacy-device-property" },
        )
        assertTrue(
            CompatMode.detectLegacySignals("var w = device.width();")
                .none { it.code == "legacy-device-property" },
        )
    }

    @Test
    fun detectsUiAndE4x() {
        val signals = CompatMode.detectLegacySignals(
            """
            "ui";
            ui.layout(<vertical><button text="ok"/></vertical>);
            """.trimIndent(),
        )
        assertTrue(signals.any { it.code == "ui-mode" })
        assertTrue(signals.any { it.code == "legacy-ui" })
        assertTrue(signals.any { it.code == "legacy-e4x" })
    }

    @Test
    fun lessThanComparisonIsNotE4x() {
        val signals = CompatMode.detectLegacySignals(
            "for (var i = 0; i < list.length; i++) { log(i); }",
        )
        assertTrue(signals.none { it.code == "legacy-e4x" })
    }

    @Test
    fun currentSelectorApiDoesNotTriggerChainHint() {
        val signals = CompatMode.detectLegacySignals(
            "selector.waitFor({ text: \"登录\" }, 5000);\nselector.find({ id: \"ok\" });",
        )
        assertTrue(signals.none { it.code == "legacy-selector-chain" })
    }

    @Test
    fun detectsRelativeRequire() {
        val signals = CompatMode.detectLegacySignals("var utils = require('./utils');")
        assertTrue(signals.any { it.code == "legacy-require-relative" })
    }

    @Test
    fun blocksKnownUnsupportedApisWhenCompatEnabled() {
        val analysis = CompatMode.analyze(
            "// @compat autojs6\nimportClass(android.content.Intent);",
        )
        assertTrue(analysis.issues.any { it.code == "unsupported-java-interop" })
        assertNotNull(analysis.unsupportedMessage())
    }

    @Test
    fun keepsTemplateInterpolationForSignalDetection() {
        val signals = CompatMode.detectLegacySignals("var s = `x \${text('登录')} y`;")
        assertTrue(signals.any { it.code == "legacy-selector" })
    }

    @Test
    fun headerMatchesProjectImportConstant() {
        assertEquals("// @compat autojs6", CompatMode.HEADER)
    }
}
