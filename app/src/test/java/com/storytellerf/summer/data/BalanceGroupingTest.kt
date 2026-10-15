package com.storytellerf.summer.data

import org.junit.Assert.*
import org.junit.Test

class BalanceGroupingTest {
    private fun row(id: Long, account: Long, balance: Double, minute: Long) = BalanceGroupingRow(id, account, balance, minute * 60_000)
    @Test fun tenMinutesIsInclusive_butGroupsNeverGrowThroughANeighbourChain() {
        val groups = groupBalanceRows(listOf(row(1, 1, 100.0, 0), row(2, 2, 200.0, 10), row(3, 3, 300.0, 20)))
        assertEquals(listOf(listOf(3L, 2L), listOf(1L)), groups.map { it.map { row -> row.id } })
        assertTrue(groups.all { it.first().timestamp - it.last().timestamp <= BALANCE_GROUP_WINDOW_MILLIS })
    }
    @Test fun repeatedEqualAccountBalancesShareANode_butChangedBalancesStartAnother() {
        val groups = groupBalanceRows(listOf(row(1, 1, 100.0, 0), row(2, 2, 200.0, 2), row(3, 1, 100.0, 3), row(4, 1, 90.0, 4)))
        assertEquals(listOf(listOf(4L), listOf(3L, 2L, 1L)), groups.map { it.map { row -> row.id } })
    }
    @Test fun equalTimestampChangesStayDistinct_andAccountIdentityIsNotItsName() {
        val groups = groupBalanceRows(listOf(row(1, 1, 100.0, 1), row(2, 2, 100.0, 1), row(3, 1, 90.0, 1)))
        assertEquals(listOf(listOf(3L, 2L), listOf(1L)), groups.map { it.map { row -> row.id } })
    }
}
