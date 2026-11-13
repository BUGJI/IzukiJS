package com.benton.izukijs.mcp.tools

import com.benton.izukijs.developer.DeveloperTuning

internal val MAX_SCRIPT_BYTES: Int get() = DeveloperTuning.current.maxScriptBytes
internal const val MAX_SEARCH_PATTERN = 300
internal val CAPTURE_WAIT_MS: Long get() = DeveloperTuning.current.captureWaitMs.toLong()
internal const val DEFAULT_INPUT_FRAME_WAIT_MS = 600
internal const val MAX_INPUT_FRAME_WAIT_MS = 3_000
internal const val DEFAULT_CAPTURE_FRAME_WAIT_MS = 1_000
internal const val MAX_CAPTURE_FRAME_WAIT_MS = 3_000
internal const val DEFAULT_CAPTURE_STABLE_MS = 300
internal const val MAX_CAPTURE_STABLE_MS = 5_000

/** MCP 工具在单线程执行器上运行，所有阻塞桥都必须有硬超时。 */
internal const val UNLOCK_TIMEOUT_MS = 60_000L
internal const val INPUT_TIMEOUT_MS = 15_000L

internal val READ_ONLY_SHARED = setOf(
    "ocr",
    "ui_dump",
    "find",
    "info",
    "notifications",
)

internal val TARGETED_SHARED = setOf("ocr", "ui_dump", "find")

/** 执行前可按需自动解锁的主屏工具；虚拟屏 / 脚本 / 查询类工具不在此列。 */
internal val AUTO_UNLOCK_TOOLS = setOf(
    "screenshot",
    "ocr",
    "ui_dump",
    "find",
    "click",
    "swipe",
    "gesture",
    "text",
    "press",
    "wait",
    "app",
    "batch",
)
