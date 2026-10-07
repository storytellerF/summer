package com.storytellerf.summer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.storytellerf.summer.data.db.entity.TimelineBalanceGroup

@Dao
interface TimelineBalanceGroupDao {
    @Query("SELECT COUNT(*) FROM timeline_balance_groups")
    suspend fun count(): Int
    @Query("SELECT * FROM timeline_balance_groups ORDER BY timestamp DESC, sortId DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(offset: Int, limit: Int): List<TimelineBalanceGroup>
    @Query("DELETE FROM timeline_balance_groups")
    suspend fun deleteAll()
    @Insert suspend fun insertAll(groups: List<TimelineBalanceGroup>)
}
