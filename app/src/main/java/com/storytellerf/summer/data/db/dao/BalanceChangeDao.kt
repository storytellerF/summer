package com.storytellerf.summer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.BalanceGroupingRow
import kotlinx.coroutines.flow.Flow

@Dao
interface BalanceChangeDao {
    @Query("SELECT id, fundSourceId, newBalance, timestamp FROM balance_changes ORDER BY timestamp DESC, id DESC")
    suspend fun getGroupingRows(): List<BalanceGroupingRow>
    @Query("SELECT EXISTS(SELECT 1 FROM balance_changes WHERE timelineGroupId IS NULL)")
    suspend fun hasUngroupedRows(): Boolean
    @Query("SELECT * FROM balance_changes WHERE timelineGroupId IN (:groupIds) ORDER BY timestamp DESC, id DESC")
    suspend fun getByGroups(groupIds: List<Long>): List<BalanceChange>
    @Update(entity = BalanceChange::class)
    suspend fun updateGroupLinks(links: List<BalanceGroupLink>)
    @Insert suspend fun insertAll(changes: List<BalanceChange>): List<Long>
    @Query("SELECT * FROM balance_changes ORDER BY timestamp DESC, id DESC")
    fun getAll(): Flow<List<BalanceChange>>

    @Query("SELECT * FROM balance_changes WHERE fundSourceId = :fundSourceId ORDER BY timestamp DESC, id DESC")
    fun getByFundSource(fundSourceId: Long): Flow<List<BalanceChange>>

    @Query("SELECT * FROM balance_changes WHERE id = :id")
    suspend fun getById(id: Long): BalanceChange?

    @Query("SELECT * FROM balance_changes ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(offset: Int, limit: Int): List<BalanceChange>

    // One latest balance per account immediately before the oldest snapshot in a page.
    @Query("""SELECT b.* FROM fund_sources source
        JOIN balance_changes b ON b.id = (
            SELECT older.id FROM balance_changes older
            WHERE older.fundSourceId = source.id
              AND (older.timestamp < :timestamp OR (older.timestamp = :timestamp AND older.id < :id))
            ORDER BY older.timestamp DESC, older.id DESC LIMIT 1)""")
    suspend fun getBalancesBefore(timestamp: Long, id: Long): List<BalanceChange>

    @Query(RECOMPUTE_ORDER_COVERAGE_SQL)
    suspend fun recomputeOrderCoverage(fundSourceId: Long?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(balanceChange: BalanceChange): Long

    @Update
    suspend fun update(balanceChange: BalanceChange)

    @Delete
    suspend fun delete(balanceChange: BalanceChange)
}

data class BalanceGroupLink(val id: Long, val timelineGroupId: Long)

// Each order belongs to (previous snapshot time, current snapshot time] for its account.
// Equal-time snapshots use id order: only the first can own orders at that instant.
internal const val RECOMPUTE_ORDER_COVERAGE_SQL = """UPDATE balance_changes
    SET coveredOrderAmount = COALESCE((
        SELECT ROUND(SUM(orders.amount), 2) FROM balance_impact_records orders
        WHERE orders.fundSourceId = balance_changes.fundSourceId
          AND orders.timestamp <= balance_changes.timestamp
          AND orders.timestamp > (
              SELECT older.timestamp FROM balance_changes older
              WHERE older.fundSourceId = balance_changes.fundSourceId
                AND (older.timestamp < balance_changes.timestamp
                  OR (older.timestamp = balance_changes.timestamp AND older.id < balance_changes.id))
              ORDER BY older.timestamp DESC, older.id DESC LIMIT 1)
    ), 0.0)
    WHERE fundSourceId = COALESCE(:fundSourceId, fundSourceId)"""
