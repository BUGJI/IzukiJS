package com.benton.izukijs.update

/** 当前网络对安装包下载的可用性；与 Android API 解耦，便于 JVM 单测。 */
enum class UpdateNetwork { NONE, METERED, UNMETERED }

/**
 * 返回阻止下载的失败原因；null 表示可以开始下载。
 *
 * 这里只做策略判断，真正的网络探测在 [UpdateManager] 中完成。
 */
internal fun downloadBlockReason(network: UpdateNetwork, wifiOnly: Boolean): UpdateFailure? = when {
    network == UpdateNetwork.NONE -> UpdateFailure.Network
    wifiOnly && network == UpdateNetwork.METERED -> UpdateFailure.WifiRequired
    else -> null
}

/** GitHub 的 asset 大小可能缺失（0）；缺失时跳过校验，避免把正常包误判为截断。 */
internal fun matchesExpectedSize(actualBytes: Long, expectedBytes: Long): Boolean =
    expectedBytes <= 0L || actualBytes == expectedBytes
