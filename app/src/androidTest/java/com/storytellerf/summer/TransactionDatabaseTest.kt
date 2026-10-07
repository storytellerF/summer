package com.storytellerf.summer

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import com.storytellerf.summer.data.db.entity.FundSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TransactionDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun idsDeduplicateAcrossScreenshots_perAccount_andDeletingAccountCascades() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val source = FundSource(id = db.fundSourceDao().insert(FundSource(name = "Wallet")), name = "Wallet")
            val other = db.fundSourceDao().insert(FundSource(name = "Other account"))
            val original = BalanceImpactRecord(fundSourceId = source.id, timestamp = 100, amount = -12.5,
                note = "Purchase", imageHash = "image-one", imageRow = 0, transactionId = "TX-001", imagePath = "recognition-images/image-one.jpg")
            assertEquals(1, repo.importTransactions(listOf(original)))
            assertEquals(0, repo.importTransactions(listOf(original.copy(imageHash = "image-two", imageRow = 4))))
            assertEquals(1, repo.importTransactions(listOf(original.copy(fundSourceId = other))))
            assertEquals(1, repo.importTransactions(listOf(original.copy(imageHash = "no-id", transactionId = null))))
            assertEquals(0, repo.importTransactions(listOf(original.copy(imageHash = "no-id", transactionId = null))))
            assertEquals(1, repo.importTransactions(listOf(original.copy(imageHash = "no-id", imageRow = 1, transactionId = null))))
            // A new real ID must not be lost if the model's row positions change on a later recognition.
            assertEquals(1, repo.importTransactions(listOf(original.copy(transactionId = "TX-002"))))
            assertEquals(5, db.balanceImpactRecordDao().getInRange(null, null).size)
            assertEquals(original.imagePath, db.balanceImpactRecordDao().getInRange(null, null).first().imagePath)
            db.fundSourceDao().delete(source)
            assertEquals(other, db.balanceImpactRecordDao().getInRange(null, null).single().fundSourceId)
        } finally { db.close() }
    }

    @Test fun pagesOwnBoundaryTransactionsExactlyOnce_andSeedOlderAccountBalances() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val bank = db.fundSourceDao().insert(FundSource(name = "Bank"))
            val wallet = db.fundSourceDao().insert(FundSource(name = "Wallet"))
            for ((index, time) in listOf(10L, 20L, 30L, 30L, 40L).withIndex()) {
                db.balanceChangeDao().insert(BalanceChange(fundSourceId = bank, newBalance = (index + 1) * 100.0, timestamp = time))
            }
            db.balanceChangeDao().insert(BalanceChange(fundSourceId = wallet, newBalance = 75.0, timestamp = 1))
            repo.importTransactions(listOf(50L, 30L, 20L, 10L, 1L).mapIndexed { i, time ->
                BalanceImpactRecord(fundSourceId = bank, timestamp = time, amount = -1.0, note = null,
                    imageHash = "image", imageRow = i, transactionId = "TX-$i")
            })
            val pages = listOf(0, 2, 4).map { repo.loadTimelinePage(it, 2) }
            assertEquals(listOf(50L, 30L), pages[0].records.map { it.timestamp })
            assertEquals(listOf(20L), pages[1].records.map { it.timestamp })
            assertEquals(listOf(10L, 1L), pages[2].records.map { it.timestamp })
            assertEquals(5, pages.flatMap { it.records }.map { it.id }.distinct().size)
            val seed = pages[1].precedingBalances.associateBy { it.fundSourceId }
            assertEquals(100.0, seed.getValue(bank).newBalance, 0.0)
            assertEquals(75.0, seed.getValue(wallet).newBalance, 0.0)
            assertFalse(pages.last().hasMore)
        } finally { db.close() }
    }

    @Test fun importedTransactionsAppearEvenWithoutBalanceSnapshots() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val account = db.fundSourceDao().insert(FundSource(name = "Bank"))
            repo.importTransactions(listOf(BalanceImpactRecord(fundSourceId = account, timestamp = 100,
                amount = -10.0, note = null, imageHash = "image", imageRow = 0)))
            val page = repo.loadTimelinePage(0, 20)
            assertTrue(page.changes.isEmpty())
            assertEquals(1, page.records.size)
            assertFalse(page.hasMore)
        } finally { db.close() }
    }

    @Test fun migrationPreservesExistingBalances_withoutInventingTransactions() = runTest {
        val name = "transaction-migration-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE fund_sources (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE balance_changes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fundSourceId INTEGER NOT NULL, newBalance REAL NOT NULL, previousBalance REAL, note TEXT, timestamp INTEGER NOT NULL, FOREIGN KEY(fundSourceId) REFERENCES fund_sources(id) ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_balance_changes_fundSourceId ON balance_changes(fundSourceId)")
                    db.execSQL("CREATE INDEX index_balance_changes_timestamp ON balance_changes(timestamp)")
                    db.execSQL("INSERT INTO fund_sources VALUES(1, 'Bank', 1, 1)")
                    db.execSQL("INSERT INTO balance_changes VALUES(1, 1, 123.5, NULL, 'Snapshot', 10)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, SummerDatabase::class.java, name).build()
        try {
            val page = DefaultDataRepository(db).loadTimelinePage(0, 20)
            assertEquals(123.5, page.changes.single().newBalance, 0.0)
            assertEquals("Bank", page.fundSources.single().name)
            assertTrue(page.records.isEmpty())
            assertNull(page.changes.single().imagePath)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
