package com.storytellerf.summer.ui.feed

import androidx.paging.testing.asSnapshot
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import com.storytellerf.summer.testing.FakeDataRepository
import com.storytellerf.summer.testing.createHostTestEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedHostTest {
    @Test fun closingHost_cancelsItsDatabaseObserver() = runTest {
        val environment = createHostTestEnvironment()
        var observing = false
        val repository = object : FakeDataRepository() {
            override fun observeTimelineChanges() = super.observeTimelineChanges()
                .onStart { observing = true }.onCompletion { observing = false }
        }
        val host = FeedHost(repository, environment.scope, environment.dispatchers)
        try {
            runCurrent()
            assertTrue(observing)
            host.close()
            runCurrent()
            assertFalse(observing)
        } finally { host.close(); environment.close() }
    }

    @Test fun renamingAnAccount_refreshesAroundTheHistoricalAnchor() = runTest {
        val items = verifyHistoricalRefresh { it.updateFundSource(FundSource(id = 1, name = "Renamed wallet")) }
        assertTrue(items.filterIsInstance<TimelineItem.Snapshot>().all {
            it.snapshot.fundBalances.single().fundSourceName == "Renamed wallet"
        })
    }

    @Test fun importingTransactions_refreshesAroundTheHistoricalAnchor() = runTest {
        val items = verifyHistoricalRefresh { it.importTransactions(listOf(BalanceImpactRecord(
            fundSourceId = 1, timestamp = 68, amount = -1.0, note = "Imported purchase", imageHash = "fixture", imageRow = 0,
        ))) }
        assertTrue(items.filterIsInstance<TimelineItem.Transaction>().any { it.record.note == "Imported purchase" })
    }

    @Test fun addingABalance_refreshesAroundTheHistoricalAnchor() = runTest {
        verifyHistoricalRefresh { it.insertBalanceChange(BalanceChange(fundSourceId = 1, newBalance = 101.0, timestamp = 101)) }
    }

    private suspend fun TestScope.verifyHistoricalRefresh(update: suspend (FakeDataRepository) -> Unit): List<TimelineItem> {
        val environment = createHostTestEnvironment()
        val repository = FakeDataRepository(fundSources = listOf(FundSource(id = 1, name = "Wallet")),
            balanceChanges = (1L..100L).map { BalanceChange(id = it, fundSourceId = 1, newBalance = it.toDouble(), timestamp = it) },
            expectedIoDispatcher = environment.ioDispatcher)
        val host = FeedHost(repository, environment.scope, environment.dispatchers)
        try {
            return host.items.asSnapshot {
                scrollTo(65) // Snapshot offsets 20..39, after the first page's flattened rows.
                repository.requestedOffsets.clear()
                withContext(environment.ioDispatcher) { update(repository) }
                advanceUntilIdle()
                assertTrue("A database change must reload the timeline", repository.requestedOffsets.isNotEmpty())
                assertEquals("Refresh must use the historical page instead of offset zero", 20, repository.requestedOffsets.first())
            }
        } finally { host.close(); environment.close() }
    }

    @Test fun pagerLoadsBoundedSnapshotWindowsOnIo_andCancelsWithHost() = runTest {
        val environment = createHostTestEnvironment()
        val repository = FakeDataRepository(fundSources = listOf(FundSource(id = 1, name = "Wallet")),
            balanceChanges = (1L..45L).map { BalanceChange(id = it, fundSourceId = 1, newBalance = it.toDouble(), timestamp = it) },
            expectedIoDispatcher = environment.ioDispatcher)
        val host = FeedHost(repository, environment.scope, environment.dispatchers)
        try {
            val items = host.items.asSnapshot { scrollTo(88) }
            assertEquals(89, items.size)
            assertEquals(45.0, (items.first() as TimelineItem.Snapshot).snapshot.totalBalance, 0.0)
            assertEquals(1.0, (items.last() as TimelineItem.Snapshot).snapshot.totalBalance, 0.0)
            assertEquals(listOf(0, 20, 40), repository.requestedOffsets.distinct())
        } finally { host.close(); environment.close() }
    }
}
