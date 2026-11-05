package com.benton.izukijs.controller.shell

/**
 * 同时排空进程的 stdout / stderr。
 *
 * 若先读完 stdout 再读 stderr，stderr 管道写满后子进程会阻塞，表现为命令“卡住”或输出丢失。
 */
internal fun readProcessOutput(process: Process): Pair<String, String> {
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val outThread = Thread({
        runCatching { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
    }, "izuki-shell-stdout").apply { isDaemon = true }
    val errThread = Thread({
        runCatching { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
    }, "izuki-shell-stderr").apply { isDaemon = true }
    outThread.start()
    errThread.start()
    outThread.join()
    errThread.join()
    return stdout.toString() to stderr.toString()
}
