package com.storytellerf.summer.ui.feed

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.data.TimelinePage
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.testing.FakeDataRepository
import com.storytellerf.summer.testing.createHostTestEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FeedPagingSourceTest {
    @Test fun pageKeysCountSnapshotsInsteadOfFlattenedRows_andRefreshUsesPageOffset() = runTest {
        val env = createHostTestEnvironment()
        val repo = object : FakeDataRepository() {
            override suspend fun loadTimelinePage(offset: Int, snapshotCount: Int): TimelinePage {
                assertEquals(20, snapshotCount)
                return TimelinePage((1L..20).map { BalanceChange(id = it, fundSourceId = 1, newBalance = it.toDouble(), timestamp = it) },
                    emptyList(), listOf(FundSource(id = 1, name = "Bank")),
                    (1L..50).map { BalanceImpactRecord(id = it, fundSourceId = 1, timestamp = it, amount = -1.0,
                        note = null, imageHash = "image", imageRow = it.toInt()) }, true)
            }
        }
        try {
            val source = FeedPagingSource(repo, env.dispatchers)
            val page = source.load(PagingSource.LoadParams.Append(20, 20, false)) as PagingSource.LoadResult.Page
            assertEquals(89, page.data.size) // 20 snapshots, 50 orders, 19 uncovered differences.
            assertEquals(0, page.prevKey)
            assertEquals(40, page.nextKey)
            val state = PagingState(listOf(page), 35, PagingConfig(20, enablePlaceholders = false), 0)
            assertEquals(20, source.getRefreshKey(state))
        } finally { env.close() }
    }

    @Test fun databaseFailuresBecomeLoadErrors_butCancellationPropagates() = runTest {
        val env = createHostTestEnvironment()
        try {
            for (error in listOf(IllegalStateException("database unavailable"), CancellationException("cancelled"))) {
                val repo = object : FakeDataRepository() {
                    override suspend fun loadTimelinePage(offset: Int, snapshotCount: Int): TimelinePage = throw error
                }
                val source = FeedPagingSource(repo, env.dispatchers)
                if (error is CancellationException) {
                    try { source.load(PagingSource.LoadParams.Refresh(null, 20, false)); fail("Expected cancellation") }
                    catch (_: CancellationException) { }
                } else assertTrue(source.load(PagingSource.LoadParams.Refresh(null, 20, false)) is PagingSource.LoadResult.Error)
            }
        } finally { env.close() }
    }
}
