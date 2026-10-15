package com.storytellerf.summer.ui.feed

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.ui.host.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class FeedHost(repository: DataRepository, scope: CoroutineScope, dispatchers: AppDispatchers) : AutoCloseable {
    private val hostScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + dispatchers.coordination)
    // Source creation and invalidation share the serial coordination dispatcher.
    private var activeSource: FeedPagingSource? = null
    private val pager = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false)) {
        FeedPagingSource(repository, dispatchers).also { activeSource = it }
    }
    val items: Flow<PagingData<TimelineItem>> = pager.flow.flowOn(dispatchers.coordination).cachedIn(hostScope)

    init {
        hostScope.launch {
            repository.observeTimelineChanges().collect { activeSource?.invalidate() }
        }
    }

    override fun close() = hostScope.cancel()
}
