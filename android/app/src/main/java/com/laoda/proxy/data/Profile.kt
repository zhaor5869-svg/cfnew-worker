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

    // @Insert/@Update/@Delete 快捷方法也不能返回 Unit：
    // Room 2.6.x 的 KSP 处理器对 void 签名 V 同样抛
    // "unexpected jvm signature V"（运行 #25 的堆栈走到的是
    // DeletionMethodProcessor）。一律改返回 Int（受影响行数）。
    @Update
    suspend fun update(profile: Profile): Int

    @Delete
    suspend fun delete(profile: Profile): Int

    // @Query UPDATE 必须返回 Int（受影响行数），不能返回 Unit：
    // Room 2.6.x 的 KSP 处理器处理返回 Unit 的 @Query 方法时会抛
    // "unexpected jvm signature V"（V 是 void 的 JVM 签名）。
    @Query("UPDATE profiles SET isCurrent = 0")
    suspend fun clearCurrent(): Int

    @Query("UPDATE profiles SET isCurrent = 1 WHERE id = :id")
    suspend fun markCurrent(id: Long): Int

    @Query("UPDATE profiles SET updatedAt = :t WHERE id = :id")
    suspend fun touch(id: Long, t: Long): Int
}
