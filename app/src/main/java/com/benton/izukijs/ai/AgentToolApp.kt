package com.benton.izukijs.ai

import org.json.JSONObject

// ---- shell ----

internal fun AgentTools.doShell(args: JSONObject): String {
    if (!shellAvailable) {
        return errorJson("Shell 工具未启用或当前无 Shizuku/Root 后端")
    }
    val started = System.currentTimeMillis()
    val result = shellApi.run(args.optString("command"))
        ?: return jsonOf {
            put("ok", false)
            put("stage", "exec")
            put("message", "Shell 执行失败：无可用后端")
            put("elapsed_ms", System.currentTimeMillis() - started)
        }
    return jsonOf {
        put("ok", result.exitCode == 0)
        put("stdout", result.stdout)
        put("stderr", result.stderr)
        put("exit_code", result.exitCode)
        put("elapsed_ms", System.currentTimeMillis() - started)
    }
}

// ---- app ----

internal fun AgentTools.doApp(args: JSONObject): String {
    val dryRun = args.optBoolean("dry_run", false)
    return when (val action = args.optString("action")) {
        "launch" -> {
            val pkg = args.optString("package")
            if (pkg.isBlank()) return errorJson("app: action=launch 需要 package")
            if (dryRun) return jsonOf { put("ok", true); put("dry_run", true); put("action", action); put("package", pkg) }
            okResult(appApi.launch(pkg), "启动应用")
        }

        "open_url" -> {
            val url = args.optString("url")
            if (url.isBlank()) return errorJson("app: action=open_url 需要 url")
            if (dryRun) return jsonOf { put("ok", true); put("dry_run", true); put("action", action); put("url", url) }
            okResult(appApi.openUrl(url), "打开链接")
        }

        else -> errorJson("app: 未知 action=$action")
    }
}
