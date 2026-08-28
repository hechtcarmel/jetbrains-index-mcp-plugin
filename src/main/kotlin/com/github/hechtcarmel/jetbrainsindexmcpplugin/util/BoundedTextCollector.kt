package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

/**
 * Collects streamed text under a hard memory bound, keeping the first ~2/3 and last ~1/3 of
 * [maxLength] chars with the same elision marker as [TestResultsCollector.truncateStackTrace]:
 * for console output the head (startup, first prints) and the tail (the crash right before the
 * process died) are the interesting parts, and the middle of a chatty log is the expendable one.
 * Unlike truncating a fully materialized string, the middle is never held in memory — a test
 * spraying hundreds of MB of output costs O(maxLength), not O(output).
 *
 * Text at or under the cap is returned verbatim. Not thread-safe; confine one instance to one
 * collection pass.
 */
internal class BoundedTextCollector(private val maxLength: Int) {

    init {
        require(maxLength > 0) { "maxLength must be positive, was $maxLength" }
    }

    private val headLimit = (maxLength * 2 / 3).coerceAtLeast(1)
    private val tailLimit = (maxLength - headLimit).coerceAtLeast(1)
    private val head = StringBuilder()

    /** Ring buffer holding the last [tailLimit] chars seen after the head filled up. */
    private val tail = CharArray(tailLimit)
    private var tailStart = 0
    private var tailSize = 0
    private var totalChars = 0L

    fun append(text: String) {
        if (text.isEmpty()) return
        totalChars += text.length
        var from = 0
        val headRoom = headLimit - head.length
        if (headRoom > 0) {
            val take = minOf(headRoom, text.length)
            head.append(text, 0, take)
            from = take
        }
        if (from >= text.length) return
        if (text.length - from >= tailLimit) {
            // The chunk alone overwrites the whole ring: bulk-copy its last tailLimit chars.
            text.toCharArray(tail, 0, text.length - tailLimit, text.length)
            tailStart = 0
            tailSize = tailLimit
            return
        }
        for (i in from until text.length) {
            if (tailSize < tailLimit) {
                tail[(tailStart + tailSize) % tailLimit] = text[i]
                tailSize++
            } else {
                tail[tailStart] = text[i]
                tailStart = (tailStart + 1) % tailLimit
            }
        }
    }

    /**
     * The collected text: verbatim when everything fit, otherwise head + elision marker + tail.
     * Null when nothing was appended. A cut point never leaves an unpaired surrogate — the
     * bordering char is dropped into the elided count instead, mirroring
     * [TestResultsCollector.truncateStackTrace].
     */
    fun build(): String? {
        if (totalChars == 0L) return null
        val tailText = buildString(tailSize) {
            for (k in 0 until tailSize) append(tail[(tailStart + k) % tailLimit])
        }
        val remainderBeyondHead = totalChars - head.length
        if (remainderBeyondHead <= tailSize) {
            // The ring holds the entire remainder — nothing was lost.
            return head.toString() + tailText
        }
        var headText = head.toString()
        var elided = remainderBeyondHead - tailSize
        var keptTail = tailText
        if (headText.isNotEmpty() && headText.last().isHighSurrogate()) {
            headText = headText.dropLast(1)
            elided++
        }
        if (keptTail.isNotEmpty() && keptTail.first().isLowSurrogate()) {
            keptTail = keptTail.drop(1)
            elided++
        }
        return headText + "\n... [$elided chars truncated] ...\n" + keptTail
    }
}
