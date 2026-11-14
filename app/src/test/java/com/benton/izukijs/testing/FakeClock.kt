package com.benton.izukijs.testing

/** 可手动推进的时钟，供需要「现在」的纯逻辑测试使用。 */
class FakeClock(var nowMs: Long = 0L) {

    fun now(): Long = nowMs

    fun advance(millis: Long) {
        nowMs += millis
    }
}
