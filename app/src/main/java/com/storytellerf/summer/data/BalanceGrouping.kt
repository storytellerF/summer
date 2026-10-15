package com.storytellerf.summer.data

/** Minimal projection for rebuilding the timeline's derived grouping index. */
data class BalanceGroupingRow(val id: Long, val fundSourceId: Long, val newBalance: Double, val timestamp: Long)

const val BALANCE_GROUP_WINDOW_MILLIS = 10 * 60 * 1000L

/** Newest first; a changed balance starts a new group, independent of imported orders. */
fun groupBalanceRows(rows: List<BalanceGroupingRow>): List<List<BalanceGroupingRow>> {
    val groups = mutableListOf<GroupBuilder>()
    for (row in rows.sortedWith(compareByDescending<BalanceGroupingRow> { it.timestamp }.thenByDescending { it.id })) {
        val current = groups.lastOrNull()
        if (current == null || !current.accepts(row)) groups += GroupBuilder(row)
        else current.add(row)
    }
    return groups.map { it.rows.toList() }
}

private class GroupBuilder(first: BalanceGroupingRow) {
    val rows = mutableListOf(first)
    private val balances = mutableMapOf(first.fundSourceId to first.newBalance)
    fun accepts(row: BalanceGroupingRow): Boolean = rows.first().timestamp - row.timestamp <= BALANCE_GROUP_WINDOW_MILLIS &&
        balances[row.fundSourceId]?.let { it == row.newBalance } != false
    fun add(row: BalanceGroupingRow) {
        rows += row
        balances[row.fundSourceId] = row.newBalance
    }
}
