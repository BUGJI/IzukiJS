package com.benton.izukijs.model

/** 锁屏自主解锁方式。 */
enum class UnlockMethod {
    SWIPE,
    PIN,
    PATTERN,
    ;

    companion object {
        fun fromStorage(raw: String?): UnlockMethod =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SWIPE
    }
}
