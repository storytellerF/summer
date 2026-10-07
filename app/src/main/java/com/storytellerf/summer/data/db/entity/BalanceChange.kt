package com.storytellerf.summer.data.db.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "balance_changes",
    foreignKeys = [
        ForeignKey(
            entity = FundSource::class,
            parentColumns = ["id"],
            childColumns = ["fundSourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fundSourceId"), Index("timestamp"), Index("timelineGroupId"), Index(value = ["fundSourceId", "timestamp", "id"])],
)
data class BalanceChange(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fundSourceId: Long,
    val newBalance: Double,
    val previousBalance: Double? = null,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    @ColumnInfo(defaultValue = "0.0")
    val coveredOrderAmount: Double = 0.0,
    val timelineGroupId: Long? = null,
)
