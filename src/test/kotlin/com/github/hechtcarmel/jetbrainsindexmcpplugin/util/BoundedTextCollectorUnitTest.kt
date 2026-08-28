package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

/**
 * Pins the bounded head+tail collection behind ide_run_tests console-output capture
 * (issue #346): text under the cap passes through verbatim, oversized text keeps its head and
 * tail around the same elision marker [TestResultsCollector.truncateStackTrace] uses, and the
 * middle is never held in memory.
 */
class BoundedTextCollectorUnitTest : TestCase() {

    fun testNothingAppendedBuildsNull() {
        assertNull(BoundedTextCollector(100).build())
    }

    fun testTextUnderTheCapPassesThroughVerbatim() {
        val collector = BoundedTextCollector(100)
        collector.append("Hello ")
        collector.append("world\n")
        assertEquals("Hello world\n", collector.build())
    }

    fun testTextExactlyAtTheCapPassesThroughVerbatim() {
        val text = "x".repeat(100)
        val collector = BoundedTextCollector(100)
        // Split across appends so both the head fill and the tail ring are exercised.
        collector.append(text.substring(0, 37))
        collector.append(text.substring(37))
        assertEquals(text, collector.build())
    }

    fun testOversizedTextKeepsHeadAndTailWithElisionMarker() {
        val head = "START-" + "h".repeat(500)
        val tail = "t".repeat(500) + "-END"
        val collector = BoundedTextCollector(300)
        collector.append(head)
        collector.append("m".repeat(10_000))
        collector.append(tail)

        val built = collector.build()!!
        assertTrue("must keep the head", built.startsWith("START-"))
        assertTrue("must keep the tail", built.endsWith("-END"))
        assertTrue("must mark the elision", built.contains("chars truncated"))
        assertTrue(
            "result must stay near the cap, was ${built.length}",
            built.length < 300 + 60
        )
    }

    fun testElidedCountAccountsForEveryDroppedChar() {
        val total = 10_000
        val collector = BoundedTextCollector(300)
        collector.append("y".repeat(total))

        val built = collector.build()!!
        val marker = Regex("""\.\.\. \[(\d+) chars truncated] \.\.\.""").find(built)
            ?: fail("no elision marker in: $built").let { error("unreachable") }
        val elided = marker.groupValues[1].toInt()
        // kept head + kept tail + elided must reconstruct the original length exactly
        val keptHead = built.substringBefore("\n... [").length
        val keptTail = built.substringAfter(" ...\n").length
        assertEquals(total, keptHead + keptTail + elided)
    }

    fun testManySmallAppendsMatchOneBigAppend() {
        val one = BoundedTextCollector(200)
        val many = BoundedTextCollector(200)
        val text = buildString { repeat(150) { append("line $it\n") } }
        one.append(text)
        text.chunked(7).forEach { many.append(it) }
        assertEquals(one.build(), many.build())
    }

    fun testCutNeverSplitsASurrogatePair() {
        // Emoji (2-char surrogate pairs) everywhere: whatever chars the head/tail cut lands on
        // must not leave an unpaired surrogate in the result. 301 puts the head cut on a pair
        // boundary and the tail cut inside a pair; 302 flips the parity so the head cut lands
        // inside a pair too.
        for (maxLength in listOf(301, 302)) {
            val collector = BoundedTextCollector(maxLength)
            collector.append("😀".repeat(5_000))

            val built = collector.build()!!
            var i = 0
            while (i < built.length) {
                val c = built[i]
                if (c.isHighSurrogate()) {
                    assertTrue(
                        "unpaired high surrogate at $i (cap $maxLength)",
                        i + 1 < built.length && built[i + 1].isLowSurrogate()
                    )
                    i += 2
                } else {
                    assertFalse("unpaired low surrogate at $i (cap $maxLength)", c.isLowSurrogate())
                    i++
                }
            }
        }
    }

    fun testMemoryStaysBoundedForHugeInput() {
        val collector = BoundedTextCollector(1_000)
        // ~50MB streamed through; the collector must only ever hold O(cap).
        repeat(5_000) { collector.append("z".repeat(10_000)) }
        val built = collector.build()!!
        assertTrue("result must stay near the cap, was ${built.length}", built.length < 1_100)
        assertTrue(built.contains("chars truncated"))
    }
}
