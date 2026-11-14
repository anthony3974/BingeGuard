package com.tony3974.bingeguard.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * An app being watched, and its limits. Keyed by package name, so a stock and a modded build
 * of the same app are separate entries. Times are in seconds.
 */
@Entity(tableName = "tracked_apps")
data class TrackedApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    val addedAtMs: Long = 0,
    /** A gap shorter than this keeps the same session going. */
    val sessionGapSec: Int = Defaults.SESSION_GAP_SEC,
    /** Sessions up to this long trigger nothing. */
    val freeHitSec: Int = Defaults.FREE_HIT_SEC,
    val nudgeAtSec: Int = Defaults.NUDGE_AT_SEC,
    val sessionCapSec: Int = Defaults.SESSION_CAP_SEC,
    /** Cooldown = session length × this, for sessions longer than the free hit. */
    val cooldownMultiplier: Double = Defaults.COOLDOWN_MULTIPLIER,
    val rollingWindowSec: Int = Defaults.ROLLING_WINDOW_SEC,
    val rollingBudgetSec: Int = Defaults.ROLLING_BUDGET_SEC,
    val dailyCeilingSec: Int = Defaults.DAILY_CEILING_SEC,
    val overrideWaitSec: Int = Defaults.OVERRIDE_WAIT_SEC,
    /** How long an override lets you back in. */
    val overrideGrantSec: Int = Defaults.OVERRIDE_GRANT_SEC,
)

object Defaults {
    const val SESSION_GAP_SEC = 2 * 60
    const val FREE_HIT_SEC = 5 * 60
    const val NUDGE_AT_SEC = 6 * 60
    const val SESSION_CAP_SEC = 8 * 60
    const val COOLDOWN_MULTIPLIER = 1.5
    const val ROLLING_WINDOW_SEC = 60 * 60
    const val ROLLING_BUDGET_SEC = 15 * 60
    const val DAILY_CEILING_SEC = 90 * 60
    const val OVERRIDE_WAIT_SEC = 30
    const val OVERRIDE_GRANT_SEC = 5 * 60
}

/** One stretch of time a tracked app was on screen (and allowed to be). */
@Entity(
    tableName = "usage_intervals",
    indices = [Index("packageName", "startMs"), Index("endMs")],
)
data class UsageInterval(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
)

/** Each time the block screen was shown. [reason] is a BlockReason name. */
@Entity(tableName = "block_events", indices = [Index("atMs")])
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val atMs: Long,
    val reason: String,
    val untilMs: Long,
)

/** Each friction override: what it overrode and how long it let you back in. */
@Entity(tableName = "override_events", indices = [Index("atMs"), Index("packageName", "grantedUntilMs")])
data class OverrideEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val atMs: Long,
    val blockReason: String,
    val waitedSec: Int,
    val phrase: String,
    val grantedUntilMs: Long,
)

@Dao
interface TrackedAppDao {
    @Query("SELECT * FROM tracked_apps ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackedApp>>

    @Query("SELECT * FROM tracked_apps WHERE packageName = :packageName")
    fun observe(packageName: String): Flow<TrackedApp?>

    @Upsert
    suspend fun upsert(app: TrackedApp)

    @Query("DELETE FROM tracked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(interval: UsageInterval): Long

    @Query("UPDATE usage_intervals SET endMs = :endMs WHERE id = :id")
    suspend fun setEnd(id: Long, endMs: Long)

    @Query("SELECT * FROM usage_intervals WHERE packageName = :packageName AND endMs >= :sinceMs ORDER BY startMs")
    suspend fun intervalsFor(packageName: String, sinceMs: Long): List<UsageInterval>

    @Query("SELECT * FROM usage_intervals WHERE endMs >= :sinceMs ORDER BY startMs")
    fun observeSince(sinceMs: Long): Flow<List<UsageInterval>>

    @Query("SELECT * FROM usage_intervals WHERE packageName = :packageName AND endMs >= :sinceMs ORDER BY startMs")
    fun observeFor(packageName: String, sinceMs: Long): Flow<List<UsageInterval>>

    @Query("DELETE FROM usage_intervals WHERE endMs < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long)
}

@Dao
interface EventDao {
    @Insert
    suspend fun insertBlock(event: BlockEvent): Long

    @Insert
    suspend fun insertOverride(event: OverrideEvent): Long

    @Query("SELECT * FROM block_events WHERE atMs >= :sinceMs ORDER BY atMs DESC")
    fun observeBlocks(sinceMs: Long): Flow<List<BlockEvent>>

    @Query("SELECT * FROM override_events WHERE atMs >= :sinceMs ORDER BY atMs DESC")
    fun observeOverrides(sinceMs: Long): Flow<List<OverrideEvent>>

    @Query("SELECT * FROM override_events WHERE packageName = :packageName AND grantedUntilMs >= :sinceMs ORDER BY atMs")
    suspend fun overridesFor(packageName: String, sinceMs: Long): List<OverrideEvent>

    @Query("DELETE FROM block_events WHERE atMs < :beforeMs")
    suspend fun deleteBlocksBefore(beforeMs: Long)

    @Query("DELETE FROM override_events WHERE atMs < :beforeMs")
    suspend fun deleteOverridesBefore(beforeMs: Long)
}

@Database(
    entities = [TrackedApp::class, UsageInterval::class, BlockEvent::class, OverrideEvent::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackedApps(): TrackedAppDao
    abstract fun usage(): UsageDao
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "binge-guard.db").build()
    }
}
