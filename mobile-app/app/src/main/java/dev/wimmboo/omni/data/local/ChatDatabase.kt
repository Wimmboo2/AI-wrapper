package dev.wimmboo.omni.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.data.model.ChatComparator
import dev.wimmboo.omni.data.model.migrateChatBranches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinnedAt: Long?,
    val payloadJson: String,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY CASE WHEN pinnedAt IS NOT NULL THEN 0 ELSE 1 END, updatedAt DESC, createdAt DESC")
    suspend fun getAll(): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()
}

@Database(entities = [ChatEntity::class], version = 1, exportSchema = false)
abstract class OmniDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var instance: OmniDatabase? = null

        fun getInstance(context: Context): OmniDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniDatabase::class.java,
                    "omni_chats.db",
                ).build().also { instance = it }
            }
    }
}

/**
 * Room-backed persistence for non-incognito chats.
 * Strict invariant: incognito chats are NEVER persisted to Room or disk.
 */
class ChatRepository(private val dao: ChatDao) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    suspend fun loadAllChats(): List<Chat> = withContext(Dispatchers.IO) {
        dao.getAll().mapNotNull { entity ->
            try {
                val decoded = json.decodeFromString(Chat.serializer(), entity.payloadJson)
                if (decoded.incognito) null
                else migrateChatBranches(decoded.copy(incognito = false))
            } catch (_: Exception) {
                null
            }
        }.sortedWith(ChatComparator)
    }

    suspend fun saveChat(chat: Chat) = withContext(Dispatchers.IO) {
        if (chat.incognito || chat.id.isBlank()) return@withContext
        val clean = chat.copy(incognito = false)
        val payload = json.encodeToString(Chat.serializer(), clean)
        dao.upsert(
            ChatEntity(
                id = clean.id,
                title = clean.title,
                createdAt = clean.createdAt,
                updatedAt = clean.recency(),
                pinnedAt = clean.pinnedAt,
                payloadJson = payload,
            ),
        )
    }

    suspend fun deleteChat(id: String) = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext
        dao.deleteById(id)
    }
}
