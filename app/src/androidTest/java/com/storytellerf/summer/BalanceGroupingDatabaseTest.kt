package com.storytellerf.summer

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import com.storytellerf.summer.data.db.entity.FundSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BalanceGroupingDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun completeGroupsCrossRawPageLimit_ordersDoNotSplit_andOlderPagesRetainSeeds() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val a = repo.insertFundSource(FundSource(name = "Wallet"))
            val b = repo.insertFundSource(FundSource(name = "Bank"))
            val old = repo.insertBalanceChange(BalanceChange(fundSourceId = a, newBalance = 90.0, timestamp = 1000))
            val ids = repo.insertBalanceChanges((0 until 25).map {
                BalanceChange(fundSourceId = a, newBalance = 100.0, timestamp = 700000L + it * 1000,
                    imagePath = "recognition-images/shared.jpg")
            } + BalanceChange(fundSourceId = b, newBalance = 200.0, timestamp = 710000))
            repo.importTransactions(listOf(BalanceImpactRecord(fundSourceId = b, amount = -5.0,
                timestamp = 715000, note = null, transactionId = "other-account-order", imageHash = "fixture", imageRow = 0)))
            val first = repo.loadTimelinePage(0, 1)
            assertEquals(26, first.changes.size)
            assertEquals(setOf(ids.min()), first.changes.map { it.timelineGroupId }.toSet())
            assertEquals(listOf(old), first.precedingBalances.map { it.id })
            assertEquals("other-account-order", first.records.single().transactionId)
            assertTrue(first.hasMore)
            val second = repo.loadTimelinePage(1, 1)
            assertEquals(listOf(old), second.changes.map { it.id })
            assertTrue(second.records.isEmpty())
            assertFalse(second.hasMore)
            assertEquals(1, repo.loadTimelinePage(200, 1).offset)
            assertEquals("recognition-images/shared.jpg", repo.getBalanceChangeById(ids.first())!!.imagePath)
            val stableId = first.changes.first().timelineGroupId
            repo.insertBalanceChange(BalanceChange(fundSourceId = a, newBalance = 100.0, timestamp = 725000))
            assertEquals(setOf(stableId), repo.loadTimelinePage(0, 1).changes.map { it.timelineGroupId }.toSet())
        } finally { db.close() }
    }

    @Test fun accountChangeSplitsGroups_editsAndDeletionRebuildThem_andInvalidBatchRollsBack() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val account = repo.insertFundSource(FundSource(name = "Wallet"))
            val ids = repo.insertBalanceChanges(listOf(
                BalanceChange(fundSourceId = account, newBalance = 100.0, timestamp = 1000),
                BalanceChange(fundSourceId = account, newBalance = 200.0, timestamp = 2000),
                BalanceChange(fundSourceId = account, newBalance = 100.0, timestamp = 3000)))
            assertEquals(3, db.timelineBalanceGroupDao().count())
            repo.updateBalanceChange(repo.getBalanceChangeById(ids[1])!!.copy(newBalance = 100.0))
            assertEquals(1, db.timelineBalanceGroupDao().count())
            repo.updateBalanceChange(repo.getBalanceChangeById(ids[1])!!.copy(timestamp = 700000))
            assertEquals(2, db.timelineBalanceGroupDao().count())
            repo.deleteBalanceChange(repo.getBalanceChangeById(ids[1])!!)
            assertEquals(1, db.timelineBalanceGroupDao().count())
            val before = repo.getAllBalanceChanges().first()
            try {
                repo.insertBalanceChanges(listOf(
                    BalanceChange(fundSourceId = account, newBalance = 999.0, timestamp = 800000),
                    BalanceChange(fundSourceId = 9999, newBalance = 1.0, timestamp = 800001)))
                fail("Foreign-key failure must abort the whole batch")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(before, repo.getAllBalanceChanges().first())
            assertEquals(1, db.timelineBalanceGroupDao().count())
            repo.deleteFundSource(repo.getFundSourceById(account)!!)
            assertEquals(0, db.timelineBalanceGroupDao().count())
            assertTrue(repo.loadTimelinePage(20, 20).changes.isEmpty())
        } finally { db.close() }
    }

    @Test fun ungroupedLegacyRecordsAreBackfilledWithoutChangingTheirValues() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val account = repo.insertFundSource(FundSource(name = "Wallet"))
            db.balanceChangeDao().insertAll(listOf(
                BalanceChange(fundSourceId = account, newBalance = 100.0, timestamp = 1000, note = "Original"),
                BalanceChange(fundSourceId = account, newBalance = 100.0, timestamp = 2000, coveredOrderAmount = -3.0,
                    imagePath = "recognition-images/original.jpg")))
            assertEquals(0, db.timelineBalanceGroupDao().count())
            val page = repo.loadTimelinePage(0, 20)
            assertEquals(2, page.changes.size)
            assertEquals(1, db.timelineBalanceGroupDao().count())
            assertEquals(-3.0, page.changes.first().coveredOrderAmount, 0.0)
            assertEquals("recognition-images/original.jpg", page.changes.first().imagePath)
            assertEquals("Original", page.changes.last().note)
        } finally { db.close() }
    }
}
