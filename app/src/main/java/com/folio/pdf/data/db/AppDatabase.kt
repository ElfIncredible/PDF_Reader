package com.folio.pdf.data.db

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
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent_documents")
data class RecentDocument(
    @PrimaryKey val uri: String,
    val name: String,
    val sizeBytes: Long,
    val pageCount: Int,
    /** Zero-based page the reader was last on, so we can resume. */
    val lastPage: Int,
    val lastOpenedAt: Long,
    val isFavorite: Boolean = false,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["documentUri", "page"], unique = true)],
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    /** Zero-based page index. */
    val page: Int,
    /** A short snippet of the page's text, shown under "Page N". */
    val label: String,
    val createdAt: Long,
)

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents WHERE isFavorite = 1 ORDER BY lastOpenedAt DESC")
    fun observeFavorites(): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents WHERE uri = :uri")
    fun observe(uri: String): Flow<RecentDocument?>

    @Query("SELECT * FROM recent_documents WHERE uri = :uri")
    suspend fun get(uri: String): RecentDocument?

    @Upsert
    suspend fun upsert(document: RecentDocument)

    @Query("UPDATE recent_documents SET lastPage = :page WHERE uri = :uri")
    suspend fun updateLastPage(uri: String, page: Int)

    @Query("UPDATE recent_documents SET isFavorite = NOT isFavorite WHERE uri = :uri")
    suspend fun toggleFavorite(uri: String)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_documents WHERE isFavorite = 0")
    suspend fun clearNonFavorites()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE documentUri = :uri ORDER BY page")
    fun observeForDocument(uri: String): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [RecentDocument::class, Bookmark::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentDocumentDao(): RecentDocumentDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /** Phase 1 → Phase 2: adds bookmarks without touching recents. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`documentUri` TEXT NOT NULL, " +
                        "`page` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_documentUri_page` " +
                        "ON `bookmarks` (`documentUri`, `page`)",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "folio.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
