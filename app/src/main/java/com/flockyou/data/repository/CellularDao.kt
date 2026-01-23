package com.flockyou.data.repository

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for persisting seen cell towers.
 */
@Entity(tableName = "seen_cell_towers")
data class SeenCellTowerEntity(
    @PrimaryKey
    val cellId: String,
    val lac: Int?,
    val tac: Int?,
    val mcc: String?,
    val mnc: String?,
    val operator: String?,
    val networkType: String,
    val networkGeneration: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int,
    val minSignal: Int,
    val maxSignal: Int,
    val lastSignal: Int,
    val latitude: Double?,
    val longitude: Double?,
    val isTrusted: Boolean
)

/**
 * Room entity for persisting trusted cell information.
 */
@Entity(tableName = "trusted_cells")
data class TrustedCellEntity(
    @PrimaryKey
    val cellId: String,
    val seenCount: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val locationsJson: String, // JSON array of lat/lon pairs
    val operator: String?,
    val networkType: String?
)

/**
 * Room entity for persisting cellular events timeline.
 */
@Entity(
    tableName = "cellular_events",
    indices = [Index(value = ["timestamp"])]
)
data class CellularEventEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val eventType: String, // CellularEventType name
    val title: String,
    val description: String,
    val cellId: String?,
    val networkType: String?,
    val signalStrength: Int?,
    val isAnomaly: Boolean,
    val threatLevel: String, // ThreatLevel name
    val latitude: Double?,
    val longitude: Double?
)

/**
 * Data Access Object for cellular persistence.
 */
@Dao
interface CellularDao {
    // ==================== Seen Cell Towers ====================

    @Query("SELECT * FROM seen_cell_towers ORDER BY lastSeen DESC")
    fun getAllSeenCellTowers(): Flow<List<SeenCellTowerEntity>>

    @Query("SELECT * FROM seen_cell_towers ORDER BY lastSeen DESC")
    suspend fun getAllSeenCellTowersSnapshot(): List<SeenCellTowerEntity>

    @Query("SELECT * FROM seen_cell_towers WHERE cellId = :cellId")
    suspend fun getSeenCellTower(cellId: String): SeenCellTowerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeenCellTower(tower: SeenCellTowerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeenCellTowers(towers: List<SeenCellTowerEntity>)

    @Update
    suspend fun updateSeenCellTower(tower: SeenCellTowerEntity)

    @Query("DELETE FROM seen_cell_towers")
    suspend fun deleteAllSeenCellTowers()

    @Query("DELETE FROM seen_cell_towers WHERE lastSeen < :before")
    suspend fun deleteOldSeenCellTowers(before: Long)

    // ==================== Trusted Cells ====================

    @Query("SELECT * FROM trusted_cells")
    fun getAllTrustedCells(): Flow<List<TrustedCellEntity>>

    @Query("SELECT * FROM trusted_cells")
    suspend fun getAllTrustedCellsSnapshot(): List<TrustedCellEntity>

    @Query("SELECT * FROM trusted_cells WHERE cellId = :cellId")
    suspend fun getTrustedCell(cellId: String): TrustedCellEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrustedCell(cell: TrustedCellEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrustedCells(cells: List<TrustedCellEntity>)

    @Update
    suspend fun updateTrustedCell(cell: TrustedCellEntity)

    @Query("DELETE FROM trusted_cells")
    suspend fun deleteAllTrustedCells()

    // ==================== Cellular Events ====================

    @Query("SELECT * FROM cellular_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentCellularEvents(limit: Int): Flow<List<CellularEventEntity>>

    @Query("SELECT * FROM cellular_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentCellularEventsSnapshot(limit: Int): List<CellularEventEntity>

    @Query("SELECT * FROM cellular_events ORDER BY timestamp DESC")
    suspend fun getAllCellularEventsSnapshot(): List<CellularEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCellularEvent(event: CellularEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCellularEvents(events: List<CellularEventEntity>)

    @Query("DELETE FROM cellular_events")
    suspend fun deleteAllCellularEvents()

    @Query("DELETE FROM cellular_events WHERE timestamp < :before")
    suspend fun deleteOldCellularEvents(before: Long)

    @Query("SELECT COUNT(*) FROM cellular_events")
    suspend fun getCellularEventCount(): Int

    // Keep only the most recent N events
    @Query("""
        DELETE FROM cellular_events
        WHERE id NOT IN (
            SELECT id FROM cellular_events
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
    """)
    suspend fun trimCellularEvents(keepCount: Int)
}
