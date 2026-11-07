package com.benton.izukijs.model

/**
 * 自动解锁结果；不含任何凭据内容。
 *
 * 放在 model 层，供 service / privileged / runtime / mcp 共用，
 * 避免 privileged 反向依赖 service。
 */
enum class UnlockResult {
    OK,
    ALREADY_UNLOCKED,
    DISABLED,
    NO_BACKEND,
    LOCK_FAILED,
    WAKE_FAILED,
    DISMISS_FAILED,
    CREDENTIAL_REQUIRED,
    CREDENTIAL_INVALID,
    CREDENTIAL_REJECTED,
    PATTERN_SCREEN_MISMATCH,
    UNSUPPORTED,
    TIMEOUT,
    NO_INPUT,
    RECORDING_TIMEOUT,
    CANCELLED,
}
