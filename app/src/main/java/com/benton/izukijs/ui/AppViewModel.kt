package com.benton.izukijs.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.benton.izukijs.di.AppContainer

/**
 * 用 [AppContainer] 构造一个与当前 ViewModelStoreOwner 绑定的 ViewModel。
 *
 * 页面不再直接持有仓库，只通过 ViewModel 暴露的状态与动作与数据层交互；
 * 后续页面迁移到 ViewModel 时可直接复用本入口。
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = rememberAppContainer()
    val factory = remember(container) {
        viewModelFactory { initializer { create(container) } }
    }
    return viewModel(factory = factory)
}
