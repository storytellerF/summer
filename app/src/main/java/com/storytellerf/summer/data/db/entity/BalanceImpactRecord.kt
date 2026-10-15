package com.storytellerf.summer.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A real transaction imported from a screenshot; never inferred from balance snapshots. */
@Entity(
    tableName = "balance_impact_records",
    foreignKeys = [ForeignKey(entity = FundSource::class, parentColumns = ["id"],
        childColumns = ["fundSourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("fundSourceId"), Index("timestamp"),
        Index(value = ["fundSourceId", "timestamp"]),
        Index(value = ["fundSourceId", "imageDedupKey"], unique = true),
        Index(value = ["fundSourceId", "transactionId"], unique = true)],
)
data class BalanceImpactRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fundSourceId: Long,
    val timestamp: Long,
    /** Signed amount in the fund source currency: income positive, expense negative. */
    val amount: Double,
    val note: String?,
    val imageHash: String,
    val imageRow: Int,
    val transactionId: String? = null,
    val imagePath: String? = null,
    /** Only rows without an original ID use screenshot-position deduplication. */
    val imageDedupKey: String? = if (transactionId == null) "$imageHash:$imageRow" else null,
)
