package com.storytellerf.summer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord

@Dao
interface BalanceImpactRecordDao {
    @Query("SELECT COUNT(*) FROM balance_impact_records")
    suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<BalanceImpactRecord>): List<Long>

    @Query("SELECT * FROM balance_impact_records ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(offset: Int, limit: Int): List<BalanceImpactRecord>

    // Half-open time intervals keep boundary transactions in exactly one page.
    @Query("""SELECT * FROM balance_impact_records
        WHERE (:lowerInclusive IS NULL OR timestamp >= :lowerInclusive)
          AND (:upperExclusive IS NULL OR timestamp < :upperExclusive)
        ORDER BY timestamp DESC, id DESC""")
    suspend fun getInRange(lowerInclusive: Long?, upperExclusive: Long?): List<BalanceImpactRecord>
}
