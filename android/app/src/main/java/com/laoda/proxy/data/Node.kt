package com.laoda.proxy.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "nodes")
data class Node(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,          // 预共享密钥, 生产改为加密存储
    val useTls: Boolean = true,
    val group: String = "default",
    val sortOrder: Int = 0,
    val lastLatencyMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getById(id: Long): Node?

    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC, id ASC LIMIT 1")
    suspend fun getFirst(): Node?

    @Insert
    suspend fun insert(node: Node): Long

    @Update
    suspend fun update(node: Node)

    @Delete
    suspend fun delete(node: Node)

    @Query("UPDATE nodes SET lastLatencyMs = :ms WHERE id = :id")
    suspend fun updateLatency(id: Long, ms: Long)
}

@Database(entities = [Node::class, Profile::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun profileDao(): ProfileDao
}
