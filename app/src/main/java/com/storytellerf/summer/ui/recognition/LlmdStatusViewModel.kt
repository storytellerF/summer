package com.storytellerf.summer.ui.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.storytellerf.summer.data.llmd.LlmdStatusReader
import com.storytellerf.summer.ui.host.AppDispatchers

class LlmdStatusViewModel(reader: LlmdStatusReader) : ViewModel() {
    val host = LlmdStatusHost(reader, viewModelScope, AppDispatchers.Runtime)
    override fun onCleared() { host.close() }

    class Factory(private val reader: LlmdStatusReader) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LlmdStatusViewModel::class.java))
            return LlmdStatusViewModel(reader) as T
        }
    }
}
