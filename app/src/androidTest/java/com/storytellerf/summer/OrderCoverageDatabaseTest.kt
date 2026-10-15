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

class OrderCoverageDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun order(account: Long, time: Long, amount: Double, id: String) = BalanceImpactRecord(
        fundSourceId = account, timestamp = time, amount = amount, note = null,
        imageHash = "fixture", imageRow = 0, transactionId = id,
    )

    @Test fun delayedImportsCoverOnlyTheirAccountInterval_andDuplicatesDoNotDoubleCoverage() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val bank = repo.insertFundSource(FundSource(name = "Bank"))
            val wallet = repo.insertFundSource(FundSource(name = "Wallet"))
            val first = repo.insertBalanceChange(BalanceChange(fundSourceId = bank, newBalance = 1000.0, timestamp = 10))
            val second = repo.insertBalanceChange(BalanceChange(fundSourceId = bank, newBalance = 900.0, timestamp = 20))
            val third = repo.insertBalanceChange(BalanceChange(fundSourceId = bank, newBalance = 950.0, timestamp = 30))
            val records = listOf(order(bank, 10, -999.0, "before"), order(bank, 15, -80.0, "expense"),
                order(bank, 20, 10.0, "income"), order(bank, 30, 50.0, "next"),
                order(bank, 31, -500.0, "after"), order(wallet, 15, -500.0, "other"))
            assertEquals(6, repo.importTransactions(records))
            assertEquals(0.0, repo.getBalanceChangeById(first)!!.coveredOrderAmount, 0.0)
            assertEquals(-70.0, repo.getBalanceChangeById(second)!!.coveredOrderAmount, 0.0)
            assertEquals(50.0, repo.getBalanceChangeById(third)!!.coveredOrderAmount, 0.0)
            assertEquals(0, repo.importTransactions(records))
            assertEquals(-70.0, repo.getBalanceChangeById(second)!!.coveredOrderAmount, 0.0)
            repo.importTransactions(listOf(order(bank, 18, -30.0, "later-import")))
            assertEquals(-100.0, repo.getBalanceChangeById(second)!!.coveredOrderAmount, 0.0)
        } finally { db.close() }
    }

    @Test fun insertingEditingMovingAndDeletingSnapshotsReassignsExistingOrders() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val bank = repo.insertFundSource(FundSource(name = "Bank"))
            val wallet = repo.insertFundSource(FundSource(name = "Wallet"))
            repo.importTransactions(listOf(order(bank, 15, -20.0, "a"), order(bank, 25, -50.0, "b"), order(wallet, 15, -5.0, "c")))
            repo.insertBalanceChange(BalanceChange(fundSourceId = bank, newBalance = 1000.0, timestamp = 10))
            repo.insertBalanceChange(BalanceChange(fundSourceId = wallet, newBalance = 100.0, timestamp = 10))
            val end = repo.insertBalanceChange(BalanceChange(fundSourceId = bank, newBalance = 900.0, timestamp = 30))
            assertEquals(-70.0, repo.getBalanceChangeById(end)!!.coveredOrderAmount, 0.0)
            val middle = repo.insertBalanceChange(BalanceChange(fundSourceId = bank, newBalance = 950.0, timestamp = 20))
            assertEquals(-20.0, repo.getBalanceChangeById(middle)!!.coveredOrderAmount, 0.0)
            assertEquals(-50.0, repo.getBalanceChangeById(end)!!.coveredOrderAmount, 0.0)
            repo.updateBalanceChange(repo.getBalanceChangeById(middle)!!.copy(timestamp = 26, coveredOrderAmount = 12345.0))
            assertEquals(-70.0, repo.getBalanceChangeById(middle)!!.coveredOrderAmount, 0.0)
            assertEquals(0.0, repo.getBalanceChangeById(end)!!.coveredOrderAmount, 0.0)
            repo.updateBalanceChange(repo.getBalanceChangeById(middle)!!.copy(fundSourceId = wallet))
            assertEquals(-5.0, repo.getBalanceChangeById(middle)!!.coveredOrderAmount, 0.0)
            assertEquals(-70.0, repo.getBalanceChangeById(end)!!.coveredOrderAmount, 0.0)
            repo.deleteBalanceChange(repo.getBalanceChangeById(middle)!!.copy(fundSourceId = bank)) // stale caller account
            assertNull(repo.getBalanceChangeById(middle))
            repo.deleteBalanceChange(repo.getBalanceChangeById(end)!!)
            val recreated = repo.insertBalanceChange(BalanceChange(fundSourceId = bank, newBalance = 900.0, timestamp = 30))
            assertEquals(-70.0, repo.getBalanceChangeById(recreated)!!.coveredOrderAmount, 0.0)
        } finally { db.close() }
    }

    @Test fun sameTimestampSnapshotsNeverDoubleCountBoundaryOrders() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        try {
            val repo = DefaultDataRepository(db)
            val account = repo.insertFundSource(FundSource(name = "Bank"))
            repo.insertBalanceChange(BalanceChange(fundSourceId = account, newBalance = 100.0, timestamp = 10))
            val first = repo.insertBalanceChange(BalanceChange(fundSourceId = account, newBalance = 90.0, timestamp = 20))
            val sameTime = repo.insertBalanceChange(BalanceChange(fundSourceId = account, newBalance = 80.0, timestamp = 20))
            repo.importTransactions(listOf(order(account, 20, -10.0, "boundary")))
            assertEquals(-10.0, repo.getBalanceChangeById(first)!!.coveredOrderAmount, 0.0)
            assertEquals(0.0, repo.getBalanceChangeById(sameTime)!!.coveredOrderAmount, 0.0)
            repo.deleteBalanceChange(repo.getBalanceChangeById(first)!!)
            assertEquals(-10.0, repo.getBalanceChangeById(sameTime)!!.coveredOrderAmount, 0.0)
        } finally { db.close() }
    }

    @Test fun versionOneMigrationPreservesBalances_andLaterImportsUpdateCoverage() = runTest {
        val name = "order-coverage-migration-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE fund_sources (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE balance_changes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fundSourceId INTEGER NOT NULL, newBalance REAL NOT NULL, previousBalance REAL, note TEXT, timestamp INTEGER NOT NULL, FOREIGN KEY(fundSourceId) REFERENCES fund_sources(id) ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_balance_changes_fundSourceId ON balance_changes(fundSourceId)")
                    db.execSQL("CREATE INDEX index_balance_changes_timestamp ON balance_changes(timestamp)")
                    db.execSQL("INSERT INTO fund_sources VALUES(1, 'Bank', 1, 1)")
                    db.execSQL("INSERT INTO balance_changes VALUES(1, 1, 1000.0, NULL, NULL, 10)")
                    db.execSQL("INSERT INTO balance_changes VALUES(2, 1, 900.0, 1000.0, 'Snapshot', 20)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, SummerDatabase::class.java, name).build()
        try {
            val repo = DefaultDataRepository(db)
            val snapshot = repo.getBalanceChangeById(2)!!
            assertEquals("CNY", repo.getFundSourceById(1)!!.currency)
            val euro = repo.insertFundSource(FundSource(name = "Euro", currency = "EUR"))
            assertEquals("EUR", repo.getFundSourceById(euro)!!.currency)
            assertEquals(900.0, snapshot.newBalance, 0.0)
            assertEquals("Snapshot", snapshot.note)
            assertEquals(0.0, snapshot.coveredOrderAmount, 0.0)
            assertNull(snapshot.imagePath)
            assertEquals(0.0, repo.getBalanceChangeById(1)!!.coveredOrderAmount, 0.0)
            assertTrue(db.balanceImpactRecordDao().getInRange(null, null).isEmpty())
            repo.importTransactions(listOf(order(1, 15, -70.0, "TX-001")))
            assertEquals(-70.0, repo.getBalanceChangeById(2)!!.coveredOrderAmount, 0.0)
            assertEquals("TX-001", db.balanceImpactRecordDao().getInRange(null, null).single().transactionId)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
