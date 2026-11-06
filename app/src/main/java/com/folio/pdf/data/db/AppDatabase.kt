package com.folio.pdf.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
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

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents WHERE isFavorite = 1 ORDER BY lastOpenedAt DESC")
    fun observeFavorites(): Flow<List<RecentDocument>>

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

@Database(entities = [RecentDocument::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentDocumentDao(): RecentDocumentDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "folio.db").build()
    }
}
