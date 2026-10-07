package com.storytellerf.summer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.recognition.parseLocalDateTime
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.FundSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FinanceJourneyTest {
    @Test fun balanceEntryAllowsEditingTheTime_andPersistsTheChosenTime() = runTest {
        val repository = DefaultDataRepository(database)
        withContext(Dispatchers.IO) { repository.insertFundSource(FundSource(name = "Historical Wallet")) }
        composeTestRule.onNodeWithContentDescription("Add Balance Change").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithText("Historical Wallet").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Historical Wallet").performClick()
        composeTestRule.onNodeWithText("New Balance").performTextInput("90.00")
        composeTestRule.onNodeWithText("Local date and time").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithText("Local date and time").performTextInput("2020-01-02T03:04:05")
        composeTestRule.onNodeWithText("Save Balance Change").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithText("Summer Finance").fetchSemanticsNodes().isNotEmpty()
        }
        val record = withContext(Dispatchers.IO) { repository.getAllBalanceChanges().first().single() }
        assertEquals(parseLocalDateTime("2020-01-02T03:04:05"), record.timestamp)
    }

    @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val database by lazy {
        SummerDatabase.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Before
    fun setup() {
        clearDatabase()
    }

    @After
    fun teardown() {
        clearDatabase()
    }

    @Test
    fun addFundSourceAndNegativeBalance_appearsInFeed() {
        composeTestRule.onNodeWithText("Summer Finance").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithContentDescription("Add Fund Source").performClick()
        composeTestRule.onNodeWithText("Fund Source Name").performTextInput(TEST_FUND_SOURCE)
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText(TEST_FUND_SOURCE).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithContentDescription("Add Balance Change").performClick()
        composeTestRule.onNodeWithText(TEST_FUND_SOURCE).performClick()
        composeTestRule.onNodeWithText("New Balance").performTextInput("-245.70")
        composeTestRule.onNodeWithText("Save Balance Change").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Summer Finance").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Summer Finance").assertIsDisplayed()
        val storedBalance = runBlocking {
            withContext(Dispatchers.IO) {
                database.balanceChangeDao().getAll().first().single().newBalance
            }
        }
        assertEquals(-245.70, storedBalance, 0.0)
    }

    @Test
    fun balanceTimeline_showsSnapshotsAndImpactRecords() {
        val now = System.currentTimeMillis()
        val repository = DefaultDataRepository(database)
        runBlocking {
            withContext(Dispatchers.IO) {
                val bankId = database.fundSourceDao().insert(
                    FundSource(name = "Timeline Bank", createdAt = now, updatedAt = now)
                )
                val walletId = database.fundSourceDao().insert(
                    FundSource(name = "Timeline Wallet", createdAt = now + 1, updatedAt = now + 1)
                )
                repository.insertBalanceChange(
                    BalanceChange(
                        fundSourceId = walletId,
                        newBalance = 500.0,
                        note = "Opening cash",
                        timestamp = now + 1_000,
                    )
                )
                repository.insertBalanceChange(
                    BalanceChange(
                        fundSourceId = bankId,
                        newBalance = 8_000.0,
                        note = "Salary income",
                        timestamp = now + 2_000,
                    )
                )
                repository.importTransactions(listOf(
                    BalanceImpactRecord(fundSourceId = walletId, timestamp = now + 2_500, amount = -120.0,
                        note = "Bought groceries", imageHash = "synthetic-image", imageRow = 0, transactionId = "TEST-001"),
                    BalanceImpactRecord(fundSourceId = bankId, timestamp = now + 1_500, amount = 8_000.0,
                        note = "Salary income", imageHash = "synthetic-image", imageRow = 1, transactionId = "TEST-002"),
                ))
                repository.insertBalanceChange(
                    BalanceChange(
                        fundSourceId = walletId,
                        newBalance = 380.0,
                        previousBalance = 500.0,
                        note = "Bought groceries",
                        timestamp = now + 3_000,
                    )
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("¥8,380.00").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("¥8,380.00").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Bought groceries").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("−¥120.00").assertIsDisplayed()
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Salary income"))
        composeTestRule.onNodeWithText("Salary income").assertIsDisplayed()
        composeTestRule.onNodeWithText("+¥8,000.00").assertIsDisplayed()
    }

    @Test
    fun importingOrdersShrinksAndThenRemovesTheUncoveredDifference() = runTest {
        val repository = DefaultDataRepository(database)
        val now = System.currentTimeMillis()
        val account = withContext(Dispatchers.IO) {
            val id = repository.insertFundSource(FundSource(name = "Reconciliation Wallet"))
            repository.insertBalanceChange(BalanceChange(fundSourceId = id, newBalance = 1000.0, timestamp = now))
            repository.insertBalanceChange(BalanceChange(fundSourceId = id, newBalance = 900.0, timestamp = now + 1000))
            id
        }
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithText("−¥100.00").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Balance difference").performScrollTo().assertIsDisplayed()
        withContext(Dispatchers.IO) {
            repository.importTransactions(listOf(BalanceImpactRecord(fundSourceId = account, timestamp = now + 500,
                amount = -70.0, note = "Order A", imageHash = "fixture", imageRow = 0, transactionId = "ORDER-A")))
        }
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithText("−¥30.00").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("−¥30.00").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Order A").performScrollTo().assertIsDisplayed()
        withContext(Dispatchers.IO) {
            repository.importTransactions(listOf(BalanceImpactRecord(fundSourceId = account, timestamp = now + 600,
                amount = -30.0, note = "Order B", imageHash = "fixture", imageRow = 1, transactionId = "ORDER-B")))
        }
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithText("Balance difference").fetchSemanticsNodes().isEmpty() &&
                composeTestRule.onAllNodesWithText("Order B").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Order A").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Order B").performScrollTo().assertIsDisplayed()
    }

    private fun clearDatabase() = runBlocking {
        withContext(Dispatchers.IO) { database.clearAllTables() }
    }

    companion object {
        private const val TEST_FUND_SOURCE = "Instrumentation Wallet"
    }
}
