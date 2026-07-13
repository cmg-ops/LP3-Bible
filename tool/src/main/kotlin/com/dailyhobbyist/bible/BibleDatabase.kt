package com.dailyhobbyist.bible

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

// ── Entities ──

/**
 * One saved reading position per version. The versionId is the primary key,
 * so re-saving a version's spot simply overwrites the previous row.
 */
@Entity(tableName = "reading_position")
data class ReadingPositionEntity(
    @PrimaryKey val versionId: String,
    val bookIndex: Int,
    val chapter: Int,
    val verse: Int,
    val savedAt: Long,
)

/**
 * A deliberately saved verse. Stores a text snapshot so the Saved list
 * renders instantly and fully offline, even for verses saved in a version
 * you aren't currently reading.
 */
@Entity(tableName = "saved_verses")
data class SavedVerseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val versionId: String,
    val bookIndex: Int,
    val chapter: Int,
    val verse: Int,
    val text: String,
    val savedAt: Long,
)

// ── DAO ──

@Dao
interface BibleDao {

    // Reading position
    @Query("SELECT * FROM reading_position WHERE versionId = :versionId LIMIT 1")
    fun getPosition(versionId: String): ReadingPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePosition(position: ReadingPositionEntity)

    // Saved verses
    @Query("SELECT * FROM saved_verses ORDER BY savedAt DESC")
    fun listAllSaves(): List<SavedVerseEntity>

    @Query(
        "SELECT id FROM saved_verses WHERE versionId = :versionId " +
            "AND bookIndex = :bookIndex AND chapter = :chapter AND verse = :verse LIMIT 1"
    )
    fun findSaveId(versionId: String, bookIndex: Int, chapter: Int, verse: Int): Long?

    @Insert
    fun insertSave(save: SavedVerseEntity): Long

    @Query("DELETE FROM saved_verses WHERE id = :id")
    fun deleteSave(id: Long)
}

// ── Database ──

@Database(
    entities = [ReadingPositionEntity::class, SavedVerseEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BibleDatabase : RoomDatabase() {
    abstract fun bibleDao(): BibleDao
}

/**
 * Singleton repository, built lazily from a screen's lightContext.
 * All DAO calls happen off the main thread (see callers).
 */
class BibleRepository private constructor(private val dao: BibleDao) {

    fun getPosition(versionId: String): ReadingPositionEntity? = dao.getPosition(versionId)

    fun savePosition(versionId: String, bookIndex: Int, chapter: Int, verse: Int) {
        dao.savePosition(
            ReadingPositionEntity(
                versionId = versionId,
                bookIndex = bookIndex,
                chapter = chapter,
                verse = verse,
                savedAt = System.currentTimeMillis(),
            )
        )
    }

    fun listAllSaves(): List<SavedVerseEntity> = dao.listAllSaves()

    fun isSaved(versionId: String, bookIndex: Int, chapter: Int, verse: Int): Boolean =
        dao.findSaveId(versionId, bookIndex, chapter, verse) != null

    /** Saves the verse if new; removes it if already saved. Returns true if now saved. */
    fun toggleSave(
        versionId: String,
        bookIndex: Int,
        chapter: Int,
        verse: Int,
        text: String,
    ): Boolean {
        val existing = dao.findSaveId(versionId, bookIndex, chapter, verse)
        return if (existing != null) {
            dao.deleteSave(existing)
            false
        } else {
            dao.insertSave(
                SavedVerseEntity(
                    versionId = versionId,
                    bookIndex = bookIndex,
                    chapter = chapter,
                    verse = verse,
                    text = text,
                    savedAt = System.currentTimeMillis(),
                )
            )
            true
        }
    }

    fun deleteSave(id: Long) = dao.deleteSave(id)

    companion object {
        const val DATABASE_NAME = "bible.db"

        @Volatile
        private var instance: BibleRepository? = null

        fun getInstance(build: () -> BibleDatabase): BibleRepository {
            return instance ?: synchronized(this) {
                instance ?: BibleRepository(build().bibleDao()).also { instance = it }
            }
        }
    }
}

/** Convenience builder for screens. */
fun SealedLightContext.bibleRepository(): BibleRepository =
    BibleRepository.getInstance {
        buildDatabase(BibleDatabase::class.java, BibleRepository.DATABASE_NAME)
    }
