package com.benton.izukijs.controller.shell

/**
 * 同时排空进程的 stdout / stderr。
 *
 * 若先读完 stdout 再读 stderr，stderr 管道写满后子进程会阻塞，表现为命令“卡住”或输出丢失。
 *
 * 读取带总超时：`su` / Shizuku 进程可能因授权弹窗或 binder 异常永不结束，这里超时后
 * 强制销毁进程并返回已读到的部分输出，避免调用线程被无限期占用。
 */
internal fun readProcessOutput(
    process: Process,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
): Pair<String, String> {
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val outThread = Thread({
        runCatching {
            val text = process.inputStream.bufferedReader().use { it.readText() }
            synchronized(stdout) { stdout.append(text) }
        }
    }, "izuki-shell-stdout").apply { isDaemon = true }
    val errThread = Thread({
        runCatching {
            val text = process.errorStream.bufferedReader().use { it.readText() }
            synchronized(stderr) { stderr.append(text) }
        }
    }, "izuki-shell-stderr").apply { isDaemon = true }
    outThread.start()
    errThread.start()

    val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
    joinUntil(outThread, deadline)
    joinUntil(errThread, deadline)
    if (outThread.isAlive || errThread.isAlive) {
        runCatching { process.destroyForcibly() }
        // 给读取线程一点时间收尾，但不再无限等待。
        joinUntil(outThread, System.currentTimeMillis() + DRAIN_AFTER_KILL_MS)
        joinUntil(errThread, System.currentTimeMillis() + DRAIN_AFTER_KILL_MS)
        synchronized(stderr) {
            if (stderr.isNotEmpty()) stderr.append('\n')
            stderr.append("[izuki] 命令执行超时（${timeoutMs}ms），已强制终止")
        }
    }
    return synchronized(stdout) { stdout.toString() } to synchronized(stderr) { stderr.toString() }
}

private fun joinUntil(thread: Thread, deadline: Long) {
    // Thread.join(0) 表示无限等待，因此必须显式判断剩余时间。
    while (thread.isAlive) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) return
        runCatching { thread.join(remaining) }
        if (!thread.isAlive) return
    }
}

private const val DEFAULT_TIMEOUT_MS = 120_000L
private const val DRAIN_AFTER_KILL_MS = 500L
