package com.storytellerf.summer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Rebuildable display index; original balance records remain independent. */
@Entity(tableName = "timeline_balance_groups", indices = [Index(value = ["timestamp", "sortId"])])
data class TimelineBalanceGroup(
    @PrimaryKey val id: Long,
    val timestamp: Long,
    val startTimestamp: Long,
    val sortId: Long,
)
