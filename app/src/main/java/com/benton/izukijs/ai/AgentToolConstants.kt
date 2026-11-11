package com.benton.izukijs.ai

import com.benton.izukijs.developer.DeveloperTuning

internal val MAX_DUMP_CHARS: Int get() = DeveloperTuning.current.agentMaxDumpChars
internal const val MAX_TEXT_CHARS = 48
internal const val DEFAULT_LONG_CLICK_MS = 600.0
internal val DEFAULT_WAIT_TIMEOUT_MS: Long
    get() = DeveloperTuning.current.agentDefaultWaitTimeoutMs.toLong()
internal const val MAX_WAIT_TIMEOUT_MS = 120_000L
internal val DEFAULT_WAIT_INTERVAL_MS: Long
    get() = DeveloperTuning.current.agentDefaultWaitIntervalMs.toLong()
internal val SETTLE_DEFAULT_MS: Long get() = DeveloperTuning.current.agentSettleDefaultMs.toLong()
internal val SETTLE_POLL_MS: Long get() = DeveloperTuning.current.agentSettlePollMs.toLong()
internal val SETTLE_MAX_MS: Long get() = DeveloperTuning.current.agentSettleMaxMs.toLong()
internal val TREE_PROBE_BUDGET_MS: Long
    get() = DeveloperTuning.current.agentTreeProbeBudgetMs.toLong()
internal val HIT_PROBE_BUDGET_MS: Long
    get() = DeveloperTuning.current.agentHitProbeBudgetMs.toLong()
internal const val MIN_PROBE_MS = 120L
internal const val DEFAULT_NOTIFICATION_LIMIT = 20
internal const val MAX_NOTIFICATION_LIMIT = 200

internal const val SOURCE_AUTO = "auto"
internal const val SOURCE_UI = "ui"
internal const val SOURCE_OCR = "ocr"
internal const val SOURCE_BOTH = "both"
