package com.laoda.proxy.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "subscription" 或 "file" */
    val type: String,
    val url: String?,
    /** filesDir/profiles/ 下的文件名 */
    val fileName: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val isCurrent: Boolean = false,
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id ASC")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): Profile?

    @Query("SELECT * FROM profiles WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrent(): Profile?

    @Insert
    suspend fun insert(profile: Profile): Long

    @Update
    suspend fun update(profile: Profile)

    @Delete
    suspend fun delete(profile: Profile)

    @Query("UPDATE profiles SET isCurrent = 0")
    suspend fun clearCurrent()

    @Query("UPDATE profiles SET isCurrent = 1 WHERE id = :id")
    suspend fun markCurrent(id: Long)

    @Query("UPDATE profiles SET updatedAt = :t WHERE id = :id")
    suspend fun touch(id: Long, t: Long)
}
