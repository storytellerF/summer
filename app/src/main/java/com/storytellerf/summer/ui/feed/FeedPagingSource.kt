package com.storytellerf.summer.ui.feed

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.ui.host.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Keys count complete balance groups (transactions without snapshots), not flattened rows. */
class FeedPagingSource(
    private val repository: DataRepository,
    private val dispatchers: AppDispatchers,
    private val snapshotPageSize: Int = 20,
) : PagingSource<Int, TimelineItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TimelineItem> = try {
        val page = withContext(dispatchers.io) { repository.loadTimelinePage(params.key ?: 0, snapshotPageSize) }
        val offset = page.offset ?: params.key ?: 0
        val items = withContext(dispatchers.default) { flattenTimeline(page, offset == 0) }
        LoadResult.Page(items, if (offset == 0) null else (offset - snapshotPageSize).coerceAtLeast(0),
            if (page.hasMore) offset + snapshotPageSize else null)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    override fun getRefreshKey(state: PagingState<Int, TimelineItem>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(snapshotPageSize) ?: page.nextKey?.minus(snapshotPageSize) ?: 0
    }
}
