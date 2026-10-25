// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 历史记录实体：保存完整媒体列表，点击卡片可直接下载。 */
@Entity(tableName = "history", indices = [Index(value = ["awemeId"], unique = true)])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val awemeId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val workType: String,
    val mediaJson: String,
    val createdAt: Long,
) {
    fun toWorkInfo(): WorkInfo = WorkInfo.fromJson(mediaJson)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    suspend fun all(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dwdouyin.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}

object HistoryDb {
    suspend fun all(context: Context): List<HistoryEntity> =
        withContext(Dispatchers.IO) { AppDatabase.get(context).historyDao().all() }

    suspend fun upsert(context: Context, entity: HistoryEntity) =
        withContext(Dispatchers.IO) { AppDatabase.get(context).historyDao().upsert(entity) }

    suspend fun delete(context: Context, id: Long) =
        withContext(Dispatchers.IO) { AppDatabase.get(context).historyDao().delete(id) }

    suspend fun clear(context: Context) =
        withContext(Dispatchers.IO) { AppDatabase.get(context).historyDao().clear() }
}
