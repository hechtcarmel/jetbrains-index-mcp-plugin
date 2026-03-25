package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class PaginationService(private val coroutineScope: CoroutineScope) : Disposable {

    companion object {
        const val TTL_MINUTES = 10L
        const val MAX_CURSORS = 20
        const val MAX_CACHED_RESULTS_PER_CURSOR = 5000
        const val SWEEP_INTERVAL_MINUTES = 5L
        const val DEFAULT_OVERCOLLECT = 500
        const val MAX_PAGE_SIZE = 500
    }

    class CursorEntry(
        val id: String,
        val toolName: String,
        val results: MutableList<SerializedResult>,
        val seenKeys: MutableSet<String>,
        val searchExtender: (suspend (Set<String>, Int) -> List<SerializedResult>)?,
        val psiModCount: Long,
        val projectBasePath: String,
        val createdAt: Instant,
        var lastAccessedAt: Instant,
        val mutex: Mutex = Mutex()
    )

    data class SerializedResult(val key: String, val data: JsonElement)

    data class PaginationPage(
        val items: List<JsonElement>,
        val nextCursor: String?,
        val offset: Int,
        val pageSize: Int,
        val totalCollected: Int,
        val hasMore: Boolean,
        val stale: Boolean
    )

    sealed interface GetPageResult {
        data class Success(val page: PaginationPage) : GetPageResult
        data class Error(val reason: CursorError, val message: String) : GetPageResult
    }

    enum class CursorError {
        MALFORMED,
        EXPIRED,
        NOT_FOUND,
        WRONG_PROJECT,
        SEARCH_INVALIDATED
    }

    private val cursors = ConcurrentHashMap<String, CursorEntry>()

    fun encodeCursor(entryId: String, offset: Int): String {
        val raw = "$entryId:$offset"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decodeCursor(token: String): Pair<String, Int>? {
        if (token.isEmpty()) return null
        return try {
            val decoded = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
            val lastColon = decoded.lastIndexOf(':')
            if (lastColon < 0) return null
            val entryId = decoded.substring(0, lastColon)
            val offset = decoded.substring(lastColon + 1).toInt()
            Pair(entryId, offset)
        } catch (_: Exception) {
            null
        }
    }

    fun createCursor(
        toolName: String,
        results: MutableList<SerializedResult>,
        seenKeys: MutableSet<String>,
        searchExtender: (suspend (Set<String>, Int) -> List<SerializedResult>)?,
        psiModCount: Long,
        projectBasePath: String
    ): String = ""

    fun getPage(
        cursorToken: String,
        pageSize: Int,
        projectBasePath: String,
        currentModCount: Long
    ): GetPageResult = GetPageResult.Error(CursorError.NOT_FOUND, "Not implemented")

    override fun dispose() {}
}
