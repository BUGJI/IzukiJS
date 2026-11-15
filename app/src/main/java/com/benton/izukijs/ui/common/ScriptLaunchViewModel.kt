package com.benton.izukijs.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benton.izukijs.R
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.model.EnvField
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.runtime.RunStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 需要用户填写运行参数的脚本。 */
data class EnvDialogState(
    val script: ScriptInfo,
    val source: String,
    val fields: List<EnvField>,
    val initial: Map<String, String>,
)

/** 读取脚本后是直接运行，还是先弹出运行参数框。 */
internal sealed interface ScriptLaunchDecision {
    data class Direct(val source: String) : ScriptLaunchDecision

    data class NeedsEnv(
        val source: String,
        val fields: List<EnvField>,
        val initial: Map<String, String>,
    ) : ScriptLaunchDecision
}

/**
 * 解析脚本是否需要运行参数。纯函数，便于单测；合并顺序与 [EnvText.resolve] 一致。
 */
internal fun decideScriptLaunch(
    source: String,
    stored: Map<String, String>,
    global: Map<String, String>,
    script: Map<String, String>,
    useGlobal: Boolean,
): ScriptLaunchDecision {
    val spec = ScriptEnvSpec.parse(source)
    if (spec.fields.isEmpty()) return ScriptLaunchDecision.Direct(source)
    return ScriptLaunchDecision.NeedsEnv(
        source = source,
        fields = spec.fields,
        initial = EnvText.resolve(
            defaults = spec.defaults(),
            global = global,
            script = script,
            runtime = stored,
            useGlobal = useGlobal,
        ),
    )
}

/**
 * 手动运行的共享入口：读取脚本、决定是否弹参数框、启动执行器。
 *
 * 运行页与脚本中心共用这套逻辑，避免两处对 `@env` 的解析 / 合并规则产生分歧。
 * 子类只叠加各自页面的状态，不再重复实现运行请求。
 */
open class ScriptLaunchViewModel(protected val container: AppContainer) : ViewModel() {

    protected val execution = container.scriptExecutionManager
    protected val scriptRepository = container.scriptRepository
    protected val scriptEnvRepository = container.scriptEnvRepository

    private val _envDialog = MutableStateFlow<EnvDialogState?>(null)
    val envDialog: StateFlow<EnvDialogState?> = _envDialog.asStateFlow()

    private val _runError = MutableStateFlow<String?>(null)
    val runError: StateFlow<String?> = _runError.asStateFlow()

    private val _launching = MutableStateFlow(false)

    /** 正在读取 / 解析脚本；用于在启动前禁用重复点击。 */
    val launching: StateFlow<Boolean> = _launching.asStateFlow()

    private var requestJob: Job? = null

    fun requestRun(script: ScriptInfo) {
        // 启动中忽略重复点击，避免多次读取、多个参数框与重复运行；
        // 运行中是否允许再次启动交给执行器的忙时策略决定。
        if (requestJob?.isActive == true) return
        _launching.value = true
        requestJob = viewModelScope.launch {
            try {
                val source = scriptRepository.readAsync(script)
                val decision = withContext(Dispatchers.Default) {
                    decideScriptLaunch(
                        source = source,
                        stored = scriptEnvRepository.values(script.id),
                        global = container.globalEnvRepository.current(),
                        script = scriptEnvRepository.env(script.id),
                        useGlobal = scriptEnvRepository.useGlobalEnv(script.id),
                    )
                }
                when (decision) {
                    is ScriptLaunchDecision.Direct -> {
                        reportLaunchResult(
                            execution.run(script.id, script.name, decision.source),
                        )
                    }

                    is ScriptLaunchDecision.NeedsEnv -> {
                        _envDialog.value = EnvDialogState(
                            script = script,
                            source = decision.source,
                            fields = decision.fields,
                            initial = decision.initial,
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _runError.value = t.message
                    ?: container.application.getString(R.string.run_error_read_failed)
            } finally {
                _launching.value = false
            }
        }
    }

    fun confirmEnv(values: Map<String, String>) {
        val state = _envDialog.value ?: return
        // 只把用户实际改过的字段作为本次输入；继承的全局 / 脚本值不应被固化成脚本覆盖。
        val changed = values.filter { (key, value) -> state.initial[key] != value }
        val result = execution.run(state.script.id, state.script.name, state.source, changed)
        reportLaunchResult(result)
        if (result.accepted) _envDialog.value = null
    }

    /** 把忙时策略结果转成用户可读提示；启动成功不打扰。 */
    private fun reportLaunchResult(result: RunStartResult) {
        _runError.value = when (result) {
            RunStartResult.Started -> null
            RunStartResult.Queued -> container.application.getString(R.string.run_queued)
            is RunStartResult.Terminating -> container.application.getString(
                R.string.run_terminating,
                result.currentScript.orEmpty(),
            )
            is RunStartResult.Rejected -> container.application.getString(
                R.string.run_error_already_running,
            )
        }
    }

    fun consumeRunError() {
        _runError.value = null
    }

    fun dismissEnv() {
        _envDialog.value = null
    }
}
