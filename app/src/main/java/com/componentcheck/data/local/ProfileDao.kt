package com.componentcheck.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM monitored_profiles ORDER BY lastCheckedTimestamp DESC")
    fun getAllProfilesFlow(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM monitored_profiles")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM monitored_profiles WHERE username = :username LIMIT 1")
    suspend fun getProfile(username: String): ProfileEntity?

    @Upsert
    suspend fun insertOrUpdateProfile(profile: ProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Query("DELETE FROM monitored_profiles WHERE username = :username")
    suspend fun deleteByUsername(username: String)

    @Query("DELETE FROM change_history WHERE username = :username")
    suspend fun deleteChangesForProfile(username: String)

    @Insert
    suspend fun insertChange(change: ChangeHistoryEntity)

    @Query("SELECT * FROM change_history WHERE username = :username ORDER BY timestamp DESC")
    fun getChangesForProfileFlow(username: String): Flow<List<ChangeHistoryEntity>>

    @Query("SELECT * FROM change_history WHERE username = :username ORDER BY timestamp DESC")
    suspend fun getChangesForProfile(username: String): List<ChangeHistoryEntity>

    @Query("SELECT COUNT(*) FROM change_history WHERE timestamp >= :sinceTimestamp")
    suspend fun getChangeCountSince(sinceTimestamp: Long): Int

    @Query("DELETE FROM monitored_profiles")
    suspend fun clearAllProfiles()
}
