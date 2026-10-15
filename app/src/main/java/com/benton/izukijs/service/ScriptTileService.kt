package com.benton.izukijs.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.benton.izukijs.R
import com.benton.izukijs.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 快捷设置磁贴：一键运行 / 停止「钉到磁贴」的固定脚本。
 *
 * 点击行为：脚本运行中 → 停止；空闲 → 运行绑定脚本。未绑定脚本或磁贴在设置页被关闭时置为不可用。
 */
class ScriptTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var observeJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        observeJob?.cancel()
        observeJob = scope.launch {
            appContainer.scriptExecutionManager.running.collect { updateTile() }
        }
    }

    override fun onStopListening() {
        observeJob?.cancel()
        observeJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val container = appContainer
        if (!container.runtimeSettingsRepository.current().tileEnabled) {
            updateTile()
            return
        }
        if (container.scriptExecutionManager.running.value) {
            container.scriptExecutionManager.requestStop()
        } else {
            val id = container.tileSettingsRepository.current()
            val script = id?.let { container.scriptRepository.find(it) }
            if (script != null) {
                container.scriptExecutionManager.run(script.name, container.scriptRepository.read(script))
            }
        }
        updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val container = appContainer
        val enabled = container.runtimeSettingsRepository.current().tileEnabled
        val running = container.scriptExecutionManager.running.value
        val scriptName = runCatching {
            container.tileSettingsRepository.current()?.let { id ->
                container.scriptRepository.find(id)?.name
            }
        }.getOrNull()

        tile.state = when {
            !enabled || scriptName == null -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = scriptName?.let { getString(R.string.tile_script_named, it) }
            ?: getString(R.string.tile_script)
        tile.updateTile()
    }
}
