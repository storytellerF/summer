package com.storytellerf.summer.data

import androidx.room.withTransaction
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import kotlinx.coroutines.flow.map
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.FundSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.storytellerf.summer.data.db.dao.BalanceGroupLink
import com.storytellerf.summer.data.db.entity.TimelineBalanceGroup

interface DataRepository {
    fun observeTimelineChanges(): Flow<Unit>
    suspend fun loadTimelinePage(offset: Int, snapshotCount: Int): TimelinePage
    suspend fun importTransactions(records: List<BalanceImpactRecord>): Int

    // Fund Sources
    fun getAllFundSources(): Flow<List<FundSource>>
    suspend fun getFundSourceById(id: Long): FundSource?
    suspend fun insertFundSource(fundSource: FundSource): Long
    suspend fun updateFundSource(fundSource: FundSource)
    suspend fun deleteFundSource(fundSource: FundSource)

    // Balance Changes
    fun getAllBalanceChanges(): Flow<List<BalanceChange>>
    fun getBalanceChangesByFundSource(fundSourceId: Long): Flow<List<BalanceChange>>
    suspend fun getBalanceChangeById(id: Long): BalanceChange?
    suspend fun insertBalanceChange(balanceChange: BalanceChange): Long
    /** Inserts the whole batch atomically, including coverage and timeline grouping updates. */
    suspend fun insertBalanceChanges(changes: List<BalanceChange>): List<Long>
    suspend fun updateBalanceChange(balanceChange: BalanceChange)
    suspend fun deleteBalanceChange(balanceChange: BalanceChange)
}

class DefaultDataRepository(private val database: SummerDatabase) : DataRepository {
    override fun observeTimelineChanges(): Flow<Unit> = database.invalidationTracker
        .createFlow("fund_sources", "balance_changes", "balance_impact_records", "timeline_balance_groups").map { }

    override suspend fun importTransactions(records: List<BalanceImpactRecord>): Int {
        require(records.all { it.amount.isFinite() && it.timestamp > 0 && it.imageHash.isNotBlank() && it.imageRow >= 0 })
        val normalized = records.map { record ->
            val id = record.transactionId?.trim()?.takeIf(String::isNotEmpty)
            record.copy(transactionId = id, imageDedupKey = if (id == null) "${record.imageHash}:${record.imageRow}" else null)
        }
        return database.withTransaction {
            val inserted = database.balanceImpactRecordDao().insertAll(normalized)
            normalized.zip(inserted).filter { it.second != -1L }.map { it.first.fundSourceId }.distinct()
                .forEach { balanceChangeDao.recomputeOrderCoverage(it) }
            inserted.count { it != -1L }
        }
    }

    override suspend fun loadTimelinePage(offset: Int, snapshotCount: Int): TimelinePage = database.withTransaction {
        require(offset >= 0 && snapshotCount > 0)
        if (balanceChangeDao.hasUngroupedRows()) rebuildTimelineGroups()
        val count = database.timelineBalanceGroupDao().count()
        val pageOffset = offset.coerceAtMost((count - 1).coerceAtLeast(0) / snapshotCount * snapshotCount)
        val window = database.timelineBalanceGroupDao().getPage(pageOffset, snapshotCount + 1)
        val groups = window.take(snapshotCount)
        val changes = balanceChangeDao.getByGroups(groups.map { it.id })
        val hasMore = window.size > snapshotCount
        // With no snapshot anchors, still page transaction-only timelines instead of loading every row.
        if (changes.isEmpty() && balanceChangeDao.getPage(0, 1).isEmpty()) {
            val transactionCount = database.balanceImpactRecordDao().count()
            val transactionOffset = offset.coerceAtMost((transactionCount - 1).coerceAtLeast(0) / snapshotCount * snapshotCount)
            val transactions = database.balanceImpactRecordDao().getPage(transactionOffset, snapshotCount + 1)
            return@withTransaction TimelinePage(emptyList(), emptyList(), fundSourceDao.getAllOnce(),
                transactions.take(snapshotCount), transactions.size > snapshotCount, transactionOffset)
        }
        val oldest = changes.lastOrNull()
        val upper = if (pageOffset == 0) null else database.timelineBalanceGroupDao().getPage(pageOffset - 1, 1).firstOrNull()?.startTimestamp
        TimelinePage(
            changes = changes,
            precedingBalances = oldest?.let { balanceChangeDao.getBalancesBefore(it.timestamp, it.id) }.orEmpty(),
            fundSources = fundSourceDao.getAllOnce(),
            records = database.balanceImpactRecordDao().getInRange(if (hasMore) oldest?.timestamp else null, upper),
            hasMore = hasMore,
            offset = pageOffset,
        )
    }

    private val fundSourceDao = database.fundSourceDao()
    private val balanceChangeDao = database.balanceChangeDao()

    private suspend fun rebuildTimelineGroups() {
        val rows = balanceChangeDao.getGroupingRows()
        val groups = withContext(Dispatchers.Default) { groupBalanceRows(rows) }
        database.timelineBalanceGroupDao().deleteAll()
        database.timelineBalanceGroupDao().insertAll(groups.map { group ->
            TimelineBalanceGroup(group.minOf { it.id }, group.first().timestamp, group.last().timestamp, group.first().id)
        })
        balanceChangeDao.updateGroupLinks(groups.flatMap { group ->
            val id = group.minOf { it.id }
            group.map { BalanceGroupLink(it.id, id) }
        })
    }

    override suspend fun insertBalanceChanges(changes: List<BalanceChange>): List<Long> = database.withTransaction {
        require(changes.isNotEmpty() && changes.all { it.id == 0L && it.newBalance.isFinite() && it.timestamp > 0 })
        val ids = balanceChangeDao.insertAll(changes.map { it.copy(coveredOrderAmount = 0.0, timelineGroupId = null) })
        changes.map { it.fundSourceId }.distinct().forEach { balanceChangeDao.recomputeOrderCoverage(it) }
        rebuildTimelineGroups()
        ids
    }

    override fun getAllFundSources(): Flow<List<FundSource>> = fundSourceDao.getAll()
    override suspend fun getFundSourceById(id: Long): FundSource? = fundSourceDao.getById(id)
    override suspend fun insertFundSource(fundSource: FundSource): Long = fundSourceDao.insert(fundSource)
    override suspend fun updateFundSource(fundSource: FundSource) = fundSourceDao.update(fundSource)
    override suspend fun deleteFundSource(fundSource: FundSource) = database.withTransaction {
        fundSourceDao.delete(fundSource)
        rebuildTimelineGroups()
    }

    override fun getAllBalanceChanges(): Flow<List<BalanceChange>> = balanceChangeDao.getAll()
    override fun getBalanceChangesByFundSource(fundSourceId: Long): Flow<List<BalanceChange>> =
        balanceChangeDao.getByFundSource(fundSourceId)
    override suspend fun getBalanceChangeById(id: Long): BalanceChange? = balanceChangeDao.getById(id)
    override suspend fun insertBalanceChange(balanceChange: BalanceChange): Long =
        database.withTransaction {
            val oldSource = balanceChange.id.takeIf { it != 0L }?.let { balanceChangeDao.getById(it)?.fundSourceId }
            val id = balanceChangeDao.insert(balanceChange.copy(coveredOrderAmount = 0.0))
            listOfNotNull(oldSource, balanceChange.fundSourceId).distinct().forEach { balanceChangeDao.recomputeOrderCoverage(it) }
            rebuildTimelineGroups()
            id
        }
    override suspend fun updateBalanceChange(balanceChange: BalanceChange) =
        database.withTransaction {
            val oldSource = balanceChangeDao.getById(balanceChange.id)?.fundSourceId
            balanceChangeDao.update(balanceChange.copy(coveredOrderAmount = 0.0))
            listOfNotNull(oldSource, balanceChange.fundSourceId).distinct().forEach { balanceChangeDao.recomputeOrderCoverage(it) }
            rebuildTimelineGroups()
        }
    override suspend fun deleteBalanceChange(balanceChange: BalanceChange) =
        database.withTransaction {
            val oldSource = balanceChangeDao.getById(balanceChange.id)?.fundSourceId
            balanceChangeDao.delete(balanceChange)
            oldSource?.let { balanceChangeDao.recomputeOrderCoverage(it) }
            rebuildTimelineGroups()
            Unit
        }
}

/** A consistent database window plus the account balances needed to reconstruct its snapshots. */
data class TimelinePage(
    val changes: List<BalanceChange>,
    val precedingBalances: List<BalanceChange>,
    val fundSources: List<FundSource>,
    val records: List<BalanceImpactRecord>,
    val hasMore: Boolean,
    val offset: Int? = null,
)
