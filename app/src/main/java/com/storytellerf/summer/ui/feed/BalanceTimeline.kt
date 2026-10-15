package com.storytellerf.summer.ui.feed

import com.storytellerf.summer.data.TimelinePage
import com.storytellerf.summer.data.BalanceGroupingRow
import com.storytellerf.summer.data.groupBalanceRows
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import com.storytellerf.summer.data.db.entity.FundSource
import java.math.BigDecimal
import java.math.RoundingMode

sealed interface TimelineItem {
    val key: String
    val timestamp: Long
    data class Snapshot(val snapshot: BalanceSnapshot, val isLatest: Boolean) : TimelineItem {
        override val key = "snapshot:${snapshot.id}"
        override val timestamp = snapshot.timestamp
    }
    data class Transaction(val record: BalanceImpactRecord, val fundSourceName: String, val currency: String = "CNY") : TimelineItem {
        override val key = "transaction:${record.id}"
        override val timestamp = record.timestamp
    }
    data class Difference(
        val balanceGroupId: Long,
        override val timestamp: Long,
        val amount: Double,
        val fundSourceName: String,
        val fundSourceId: Long = 0,
        val currency: String = "CNY",
    ) : TimelineItem {
        override val key = "difference:$balanceGroupId:$fundSourceId"
    }
}

data class BalanceSnapshot(
    val id: Long,
    val timestamp: Long,
    val totalBalance: Double?,
    val fundBalances: List<FundBalance>,
    val startTimestamp: Long = timestamp,
    val recordIds: List<Long> = listOf(id),
)

data class FundBalance(val fundSourceId: Long, val fundSourceName: String, val balance: Double, val currency: String = "CNY")

internal fun buildBalanceTimeline(
    balanceChanges: List<BalanceChange>,
    fundSources: List<FundSource>,
    precedingBalances: List<BalanceChange> = emptyList(),
): List<BalanceSnapshot> {
    val sourceById = fundSources.associateBy(FundSource::id)
    val orderedSources = fundSources.sortedWith(compareBy(FundSource::createdAt, FundSource::name, FundSource::id))
    val balances = precedingBalances.associate { it.fundSourceId to it.newBalance }.toMutableMap()
    val groupsByEnd = groupBalanceRows(balanceChanges.map { BalanceGroupingRow(it.id, it.fundSourceId, it.newBalance, it.timestamp) })
        .associateBy { it.first().id }
    return balanceChanges.sortedWith(compareBy(BalanceChange::timestamp, BalanceChange::id)).mapNotNull { change ->
        balances[change.fundSourceId] = change.newBalance
        val group = groupsByEnd[change.id] ?: return@mapNotNull null
        val funds = orderedSources.mapNotNull { source ->
            balances[source.id]?.let { FundBalance(source.id, sourceById.getValue(source.id).name, it, source.currency) }
        }
        BalanceSnapshot(group.minOf { it.id }, change.timestamp, funds.takeIf { it.map(FundBalance::currency).distinct().size <= 1 }?.sumOf(FundBalance::balance), funds,
            group.last().timestamp, group.map { it.id })
    }.asReversed()
}

internal fun flattenTimeline(page: TimelinePage, isFirstPage: Boolean): List<TimelineItem> {
    val snapshots = buildBalanceTimeline(page.changes, page.fundSources, page.precedingBalances)
    val names = page.fundSources.associate { it.id to it.name }
    val currencies = page.fundSources.associate { it.id to it.currency }
    val previous = page.precedingBalances.associate { it.fundSourceId to it.newBalance }.toMutableMap()
    val groupByRecord = snapshots.flatMap { snapshot -> snapshot.recordIds.map { it to snapshot } }.toMap()
    val remainingByAccount = mutableMapOf<Pair<Long, Long>, BigDecimal>()
    page.changes.sortedWith(compareBy(BalanceChange::timestamp, BalanceChange::id)).forEach { change ->
        val baseline = previous.put(change.fundSourceId, change.newBalance) ?: change.previousBalance
        if (baseline != null) {
            val group = groupByRecord.getValue(change.id)
            val key = group.id to change.fundSourceId
            val remaining = BigDecimal.valueOf(change.newBalance).subtract(BigDecimal.valueOf(baseline))
                .subtract(BigDecimal.valueOf(change.coveredOrderAmount))
            remainingByAccount[key] = remainingByAccount.getOrDefault(key, BigDecimal.ZERO).add(remaining)
        }
    }
    val snapshotsById = snapshots.associateBy { it.id }
    val differences = remainingByAccount.mapNotNull { (key, amount) ->
        val digits = java.util.Currency.getInstance(currencies[key.second] ?: "CNY").defaultFractionDigits
        val remaining = amount.setScale(digits, RoundingMode.HALF_UP)
        if (remaining.signum() == 0) null else TimelineItem.Difference(key.first,
            snapshotsById.getValue(key.first).timestamp, remaining.toDouble(), names[key.second] ?: "Unknown fund", key.second, currencies[key.second] ?: "CNY")
    }
    return (snapshots.mapIndexed { index, snapshot ->
        TimelineItem.Snapshot(snapshot, isFirstPage && index == 0)
    } + differences + page.records.map { record ->
        TimelineItem.Transaction(record, names[record.fundSourceId] ?: "Unknown fund", currencies[record.fundSourceId] ?: "CNY")
    }).sortedWith(compareByDescending<TimelineItem> { it.timestamp }
        .thenBy { if (it is TimelineItem.Snapshot) 0 else 1 }
        .thenByDescending { when (it) {
            is TimelineItem.Snapshot -> it.snapshot.id
            is TimelineItem.Transaction -> it.record.id
            is TimelineItem.Difference -> it.balanceGroupId
        } })
}
