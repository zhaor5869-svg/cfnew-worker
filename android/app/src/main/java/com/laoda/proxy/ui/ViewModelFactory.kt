package com.laoda.proxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 简单的 ViewModel 工厂辅助（模块 C）。
 * 用法：`val vm: FooViewModel = viewModel(factory = vmFactory { FooViewModel(dep) })`。
 */
inline fun <reified T : ViewModel> vmFactory(
    crossinline create: () -> T,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T2 : ViewModel> create(modelClass: Class<T2>): T2 {
            return create() as T2
        }
    }
