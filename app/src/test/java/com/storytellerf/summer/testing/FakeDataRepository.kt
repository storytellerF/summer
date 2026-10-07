package com.storytellerf.summer.testing

import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.data.TimelinePage
import com.storytellerf.summer.data.BalanceGroupingRow
import com.storytellerf.summer.data.groupBalanceRows
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import kotlinx.coroutines.flow.combine
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.FundSource
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

open class FakeDataRepository(
    fundSources: List<FundSource> = emptyList(),
    balanceChanges: List<BalanceChange> = emptyList(),
    private val expectedDefaultDispatcher: CoroutineDispatcher? = null,
    private val expectedIoDispatcher: CoroutineDispatcher? = null,
) : DataRepository {
    val importedTransactions = MutableStateFlow<List<BalanceImpactRecord>>(emptyList())
    val requestedOffsets = mutableListOf<Int>()

    override fun observeTimelineChanges(): Flow<Unit> = combine(mutableFundSources, mutableBalanceChanges, importedTransactions) { _, _, _ -> }

    override suspend fun importTransactions(records: List<BalanceImpactRecord>): Int {
        assertDispatcher(expectedIoDispatcher)
        val new = records.filter { record -> importedTransactions.value.none {
            it.fundSourceId == record.fundSourceId && ((record.transactionId == null && it.transactionId == null && it.imageHash == record.imageHash && it.imageRow == record.imageRow) ||
                (record.transactionId != null && record.transactionId == it.transactionId))
        } }
        val startId = importedTransactions.value.maxOfOrNull { it.id } ?: 0L
        importedTransactions.value += new.mapIndexed { index, record -> record.copy(id = startId + index + 1) }
        recomputeCoverage()
        return new.size
    }

    override suspend fun loadTimelinePage(offset: Int, snapshotCount: Int): TimelinePage {
        assertDispatcher(expectedIoDispatcher)
        requestedOffsets += offset
        val sorted = mutableBalanceChanges.value.sortedWith(compareByDescending<BalanceChange> { it.timestamp }.thenByDescending { it.id })
        if (sorted.isEmpty()) {
            val records = importedTransactions.value.sortedWith(compareByDescending<BalanceImpactRecord> { it.timestamp }.thenByDescending { it.id }).drop(offset)
            return TimelinePage(emptyList(), emptyList(), mutableFundSources.value, records.take(snapshotCount), records.size > snapshotCount)
        }
        val groups = groupBalanceRows(sorted.map { BalanceGroupingRow(it.id, it.fundSourceId, it.newBalance, it.timestamp) })
        val byId = sorted.associateBy { it.id }
        val changes = groups.drop(offset).take(snapshotCount).flatten().map { byId.getValue(it.id) }
        val older = groups.drop(offset + snapshotCount).flatten().map { byId.getValue(it.id) }
        val upper = groups.getOrNull(offset - 1)?.lastOrNull()?.timestamp
        val lower = if (older.isEmpty()) null else changes.lastOrNull()?.timestamp
        return TimelinePage(changes, older.distinctBy { it.fundSourceId }, mutableFundSources.value,
            importedTransactions.value.filter { (upper == null || it.timestamp < upper) && (lower == null || it.timestamp >= lower) }, older.isNotEmpty())
    }

    private val mutableFundSources = MutableStateFlow(fundSources)
    private val mutableBalanceChanges = MutableStateFlow(balanceChanges)

    val insertedBalanceChanges = mutableListOf<BalanceChange>()
    val insertedFundSources = mutableListOf<FundSource>()
    val updatedFundSources = mutableListOf<FundSource>()
    val deletedFundSources = mutableListOf<FundSource>()

    override fun getAllFundSources(): Flow<List<FundSource>> = mutableFundSources
        .onEach { assertDispatcher(expectedDefaultDispatcher) }

    override suspend fun getFundSourceById(id: Long): FundSource? {
        assertDispatcher(expectedIoDispatcher)
        return mutableFundSources.value.firstOrNull { it.id == id }
    }

    override suspend fun insertFundSource(fundSource: FundSource): Long {
        assertDispatcher(expectedIoDispatcher)
        val inserted = fundSource.copy(id = fundSource.id.takeIf { it != 0L } ?: nextFundSourceId())
        insertedFundSources += inserted
        mutableFundSources.value += inserted
        return inserted.id
    }

    override suspend fun updateFundSource(fundSource: FundSource) {
        assertDispatcher(expectedIoDispatcher)
        updatedFundSources += fundSource
        mutableFundSources.value = mutableFundSources.value.map {
            if (it.id == fundSource.id) fundSource else it
        }
    }

    override suspend fun deleteFundSource(fundSource: FundSource) {
        assertDispatcher(expectedIoDispatcher)
        deletedFundSources += fundSource
        mutableFundSources.value = mutableFundSources.value.filterNot { it.id == fundSource.id }
    }

    override fun getAllBalanceChanges(): Flow<List<BalanceChange>> = mutableBalanceChanges
        .onEach { assertDispatcher(expectedDefaultDispatcher) }

    override fun getBalanceChangesByFundSource(fundSourceId: Long): Flow<List<BalanceChange>> =
        mutableBalanceChanges.onEach { assertDispatcher(expectedIoDispatcher) }
            .map { changes ->
                changes.filter { it.fundSourceId == fundSourceId }
            }

    override suspend fun getBalanceChangeById(id: Long): BalanceChange? {
        assertDispatcher(expectedIoDispatcher)
        return mutableBalanceChanges.value.firstOrNull { it.id == id }
    }

    override suspend fun insertBalanceChange(balanceChange: BalanceChange): Long {
        assertDispatcher(expectedIoDispatcher)
        val inserted = balanceChange.copy(
            id = balanceChange.id.takeIf { it != 0L } ?: nextBalanceChangeId(),
        )
        insertedBalanceChanges += inserted
        mutableBalanceChanges.value = listOf(inserted) + mutableBalanceChanges.value
        recomputeCoverage()
        return inserted.id
    }

    override suspend fun insertBalanceChanges(changes: List<BalanceChange>): List<Long> =
        changes.map { insertBalanceChange(it) }

    override suspend fun updateBalanceChange(balanceChange: BalanceChange) {
        assertDispatcher(expectedIoDispatcher)
        mutableBalanceChanges.value = mutableBalanceChanges.value.map {
            if (it.id == balanceChange.id) balanceChange else it
        }
        recomputeCoverage()
    }

    override suspend fun deleteBalanceChange(balanceChange: BalanceChange) {
        assertDispatcher(expectedIoDispatcher)
        mutableBalanceChanges.value = mutableBalanceChanges.value.filterNot {
            it.id == balanceChange.id
        }
        recomputeCoverage()
    }

    private fun recomputeCoverage() {
        val ordered = mutableBalanceChanges.value.sortedWith(compareBy(BalanceChange::timestamp, BalanceChange::id))
        val previous = mutableMapOf<Long, Long>()
        val coverage = ordered.associate { change ->
            val start = previous.put(change.fundSourceId, change.timestamp)
            change.id to if (start == null) 0.0 else importedTransactions.value.filter {
                it.fundSourceId == change.fundSourceId && it.timestamp > start && it.timestamp <= change.timestamp
            }.sumOf { it.amount }
        }
        mutableBalanceChanges.value = mutableBalanceChanges.value.map { it.copy(coveredOrderAmount = coverage.getValue(it.id)) }
    }

    private fun nextFundSourceId(): Long =
        (mutableFundSources.value.maxOfOrNull(FundSource::id) ?: 0L) + 1L

    private fun nextBalanceChangeId(): Long =
        (mutableBalanceChanges.value.maxOfOrNull(BalanceChange::id) ?: 0L) + 1L
}

private suspend fun assertDispatcher(expected: CoroutineDispatcher?) {
    if (expected == null) return
    check(currentCoroutineContext()[ContinuationInterceptor] === expected) {
        "Expected work on $expected but was on " +
            currentCoroutineContext()[ContinuationInterceptor]
    }
}
