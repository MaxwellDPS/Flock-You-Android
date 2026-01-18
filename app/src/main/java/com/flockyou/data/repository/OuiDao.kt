package com.flockyou.data.repository

import androidx.room.*
import com.flockyou.data.model.OuiEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface OuiDao {
    @Query("SELECT * FROM oui_entries WHERE ouiPrefix = :prefix LIMIT 1")
    suspend fun getByPrefix(prefix: String): OuiEntry?

    @Query("SELECT organizationName FROM oui_entries WHERE ouiPrefix = :prefix LIMIT 1")
    suspend fun getOrganizationName(prefix: String): String?

    @Query("SELECT COUNT(*) FROM oui_entries")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM oui_entries")
    fun getCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: OuiEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<OuiEntry>)

    @Query("DELETE FROM oui_entries")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(entries: List<OuiEntry>) {
        deleteAll()
        insertAll(entries)
    }

    @Query("SELECT MAX(lastUpdated) FROM oui_entries")
    suspend fun getLastUpdateTime(): Long?
}
