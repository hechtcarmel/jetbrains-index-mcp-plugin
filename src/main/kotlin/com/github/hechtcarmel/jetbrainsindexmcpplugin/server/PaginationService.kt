package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import org.jetbrains.annotations.VisibleForTesting
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

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
        @Volatile var lastAccessedAt: Instant,
        val metadata: Map<String, String> = emptyMap(),
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
        val stale: Boolean,
        val metadata: Map<String, String> = emptyMap()
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

    init {
        coroutineScope.launch {
            while (true) {
                delay(SWEEP_INTERVAL_MINUTES * 60 * 1000)
                sweepExpired()
            }
        }
    }

    data class DecodedCursor(val entryId: String, val offset: Int, val pageSize: Int? = null)

    fun encodeCursor(entryId: String, offset: Int, pageSize: Int? = null): String {
        val raw = if (pageSize != null) "$entryId:$offset:$pageSize" else "$entryId:$offset"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decodeCursor(token: String): DecodedCursor? {
        if (token.isEmpty()) return null
        return try {
            val decoded = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
            // Format: "entryId:offset" (legacy) or "entryId:offset:pageSize"
            // entryId is a 32-char hex string (no colons), so we split from the right.
            val lastColon = decoded.lastIndexOf(':')
            if (lastColon < 0) return null
            val beforeLast = decoded.substring(0, lastColon)
            val afterLast = decoded.substring(lastColon + 1).toInt()

            val secondLastColon = beforeLast.lastIndexOf(':')
            if (secondLastColon < 0) {
                // Legacy format: entryId:offset
                DecodedCursor(beforeLast, afterLast)
            } else {
                // New format: entryId:offset:pageSize
                val entryId = beforeLast.substring(0, secondLastColon)
                val offset = beforeLast.substring(secondLastColon + 1).toInt()
                DecodedCursor(entryId, offset, pageSize = afterLast)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun createCursor(
        toolName: String,
        results: List<SerializedResult>,
        seenKeys: Set<String>,
        searchExtender: (suspend (Set<String>, Int) -> List<SerializedResult>)?,
        psiModCount: Long,
        projectBasePath: String,
        metadata: Map<String, String> = emptyMap()
    ): String {
        val entryId = UUID.randomUUID().toString().replace("-", "")
        val now = Instant.now()
        val entry = CursorEntry(
            id = entryId,
            toolName = toolName,
            results = ArrayList(results),
            seenKeys = HashSet(seenKeys),
            searchExtender = searchExtender,
            psiModCount = psiModCount,
            projectBasePath = projectBasePath,
            createdAt = now,
            lastAccessedAt = now,
            metadata = metadata
        )
        // ConcurrentHashMap.size is approximate under contention, so eviction count may be
        // slightly off. Acceptable at MAX_CURSORS=20 with typical 1-3 concurrent agents.
        if (cursors.size >= MAX_CURSORS) {
            val oldest = cursors.entries.minByOrNull { it.value.lastAccessedAt }
            if (oldest != null) {
                cursors.remove(oldest.key)
            }
        }
        cursors[entryId] = entry
        return encodeCursor(entryId, 0)
    }

    suspend fun getPage(
        cursorToken: String,
        requestedPageSize: Int?,
        projectBasePath: String,
        currentModCount: Long
    ): GetPageResult {
        val decoded = decodeCursor(cursorToken)
            ?: return GetPageResult.Error(CursorError.MALFORMED, "Invalid cursor format. Please re-search.")

        val pageSize = requestedPageSize
            ?: decoded.pageSize
            ?: return GetPageResult.Error(CursorError.MALFORMED, "No pageSize provided and cursor does not contain one. Please re-search.")
        val (entryId, offset) = decoded

        val entry = cursors[entryId]
            ?: return GetPageResult.Error(CursorError.NOT_FOUND, "Cursor not found. Please re-search.")

        if (Duration.between(entry.lastAccessedAt, Instant.now()).toMinutes() >= TTL_MINUTES) {
            return GetPageResult.Error(CursorError.EXPIRED, "Cursor expired. Please re-search.")
        }

        if (entry.projectBasePath != projectBasePath) {
            return GetPageResult.Error(CursorError.WRONG_PROJECT, "Cursor belongs to a different project.")
        }

        entry.lastAccessedAt = Instant.now()

        return entry.mutex.withLock {
            val stale = entry.psiModCount != currentModCount

            // Extend cache if needed and possible.
            // Use >= so that when we're exactly at the boundary we still probe the extender,
            // allowing an accurate hasMore answer without an extra client round-trip.
            if (offset + pageSize >= entry.results.size
                && entry.searchExtender != null
                && entry.results.size < MAX_CACHED_RESULTS_PER_CURSOR
            ) {
                try {
                    val newResults = entry.searchExtender!!(entry.seenKeys.toSet(), DEFAULT_OVERCOLLECT)
                    for (result in newResults) {
                        entry.results.add(result)
                        entry.seenKeys.add(result.key)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: LinkageError) {
                    return@withLock GetPageResult.Error(
                        CursorError.SEARCH_INVALIDATED,
                        "Search context invalidated due to IDE/plugin API incompatibility. Please re-search."
                    )
                } catch (_: Exception) {
                    return@withLock GetPageResult.Error(
                        CursorError.SEARCH_INVALIDATED,
                        "Search context invalidated due to file changes. Please re-search."
                    )
                }
            }

            val end = minOf(offset + pageSize, entry.results.size)
            if (offset >= entry.results.size) {
                return@withLock GetPageResult.Success(
                    PaginationPage(
                        items = emptyList(),
                        nextCursor = null,
                        offset = offset,
                        pageSize = 0,
                        totalCollected = entry.results.size,
                        hasMore = false,
                        stale = stale,
                        metadata = entry.metadata
                    )
                )
            }

            val items = entry.results.subList(offset, end).map { it.data }
            val actualPageSize = items.size
            // After extension (if triggered), the cache reflects the true result set.
            // hasMore is simply whether more items exist beyond this page.
            val hasMore = end < entry.results.size
            val nextCursor = if (hasMore && actualPageSize > 0) encodeCursor(entryId, offset + actualPageSize, pageSize) else null

            GetPageResult.Success(
                PaginationPage(
                    items = items,
                    nextCursor = nextCursor,
                    offset = offset,
                    pageSize = actualPageSize,
                    totalCollected = entry.results.size,
                    hasMore = hasMore,
                    stale = stale,
                    metadata = entry.metadata
                )
            )
        }
    }

    private fun sweepExpired() {
        val now = Instant.now()
        cursors.entries.removeIf { (_, entry) ->
            Duration.between(entry.lastAccessedAt, now).toMinutes() >= TTL_MINUTES
        }
    }

    @VisibleForTesting
    internal fun expireEntryForTesting(entryId: String) {
        cursors[entryId]?.lastAccessedAt = Instant.MIN
    }

    override fun dispose() {
        cursors.clear()
    }
}
