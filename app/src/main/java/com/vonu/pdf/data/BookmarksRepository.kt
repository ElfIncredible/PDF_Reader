package com.vonu.pdf.data

import com.vonu.pdf.data.db.Bookmark
import com.vonu.pdf.data.db.BookmarkDao
import kotlinx.coroutines.flow.Flow

class BookmarksRepository(private val dao: BookmarkDao) {

    fun observe(documentUri: String): Flow<List<Bookmark>> = dao.observeForDocument(documentUri)

    suspend fun add(documentUri: String, page: Int, label: String) = dao.insert(
        Bookmark(documentUri = documentUri, page = page, label = label, createdAt = System.currentTimeMillis()),
    )

    suspend fun remove(bookmark: Bookmark) = dao.delete(bookmark.id)
}
