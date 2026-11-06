package com.folio.pdf.data

import com.folio.pdf.data.db.RecentDocument
import com.folio.pdf.data.db.RecentDocumentDao
import kotlinx.coroutines.flow.Flow

class RecentsRepository(private val dao: RecentDocumentDao) {

    fun observeRecent(limit: Int = 50): Flow<List<RecentDocument>> = dao.observeRecent(limit)

    fun observeFavorites(): Flow<List<RecentDocument>> = dao.observeFavorites()

    suspend fun get(uri: String): RecentDocument? = dao.get(uri)

    /** Records an open, keeping reading progress and favourite flag if we've seen it before. */
    suspend fun recordOpened(info: DocumentInfo, pageCount: Int): RecentDocument {
        val existing = dao.get(info.uri.toString())
        val doc = RecentDocument(
            uri = info.uri.toString(),
            name = info.name,
            sizeBytes = info.sizeBytes,
            pageCount = pageCount,
            lastPage = existing?.lastPage?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0,
            lastOpenedAt = System.currentTimeMillis(),
            isFavorite = existing?.isFavorite ?: false,
        )
        dao.upsert(doc)
        return doc
    }

    suspend fun saveProgress(uri: String, page: Int) = dao.updateLastPage(uri, page)

    suspend fun toggleFavorite(uri: String) = dao.toggleFavorite(uri)

    suspend fun remove(uri: String) = dao.delete(uri)

    suspend fun clearHistory() = dao.clearNonFavorites()
}
