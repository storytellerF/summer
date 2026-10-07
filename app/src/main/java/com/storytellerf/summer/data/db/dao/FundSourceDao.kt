package com.storytellerf.summer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.storytellerf.summer.data.db.entity.FundSource
import kotlinx.coroutines.flow.Flow

@Dao
interface FundSourceDao {
    @Query("SELECT * FROM fund_sources ORDER BY name ASC")
    fun getAll(): Flow<List<FundSource>>

    @Query("SELECT * FROM fund_sources WHERE id = :id")
    suspend fun getById(id: Long): FundSource?

    @Query("SELECT * FROM fund_sources ORDER BY createdAt, name, id")
    suspend fun getAllOnce(): List<FundSource>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fundSource: FundSource): Long

    @Update
    suspend fun update(fundSource: FundSource)

    @Delete
    suspend fun delete(fundSource: FundSource)
}
