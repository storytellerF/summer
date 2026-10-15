package com.storytellerf.summer.data.db

import android.content.Context
import androidx.room.AutoMigration
import com.storytellerf.summer.data.db.dao.BalanceImpactRecordDao
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.storytellerf.summer.data.db.dao.BalanceChangeDao
import com.storytellerf.summer.data.db.dao.FundSourceDao
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.db.entity.TimelineBalanceGroup
import com.storytellerf.summer.data.db.dao.TimelineBalanceGroupDao

@Database(
    entities = [FundSource::class, BalanceChange::class, BalanceImpactRecord::class, TimelineBalanceGroup::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class SummerDatabase : RoomDatabase() {
    abstract fun fundSourceDao(): FundSourceDao
    abstract fun balanceChangeDao(): BalanceChangeDao

    abstract fun balanceImpactRecordDao(): BalanceImpactRecordDao
    abstract fun timelineBalanceGroupDao(): TimelineBalanceGroupDao

    companion object {
        @Volatile
        private var instance: SummerDatabase? = null

        fun getInstance(context: Context): SummerDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SummerDatabase::class.java,
                    "summer_finance.db",
                ).build().also { instance = it }
            }
        }
    }
}
