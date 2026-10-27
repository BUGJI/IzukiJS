package com.benton.izukijs.subscription

data class ScriptSubscription(
    val scriptId: String,
    val url: String,
    val intervalMinutes: Long,
    val enabled: Boolean = true,
    val lastSyncAtMillis: Long = 0L,
    val nextSyncAtMillis: Long = 0L,
    val lastError: String = "",
    val contentHash: String = "",
    val etag: String = "",
    val lastModified: String = "",
)
