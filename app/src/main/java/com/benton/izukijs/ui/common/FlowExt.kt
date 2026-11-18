package com.benton.izukijs.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 把一个 [Flow] 折叠进 UI 状态。
 *
 * 运行页与设置页原先各有一份完全相同的 `collectInto`；集中后只保留一个实现。
 */
internal fun <S, T> MutableStateFlow<S>.collectFrom(
    scope: CoroutineScope,
    flow: Flow<T>,
    reducer: (S, T) -> S,
) {
    scope.launch {
        flow.collect { value ->
            this@collectFrom.update { state -> reducer(state, value) }
        }
    }
}
