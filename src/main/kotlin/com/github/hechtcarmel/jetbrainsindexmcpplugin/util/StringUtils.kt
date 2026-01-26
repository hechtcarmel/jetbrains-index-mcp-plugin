package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

/**
 * String utility functions for search and matching operations.
 */
object StringUtils {

    /**
     * Calculates the Levenshtein distance between two strings.
     *
     * The Levenshtein distance is the minimum number of single-character edits
     * (insertions, deletions, or substitutions) required to change one string into another.
     *
     * Used for ranking search results by similarity.
     *
     * @param s1 First string
     * @param s2 Second string
     * @return The edit distance between the two strings
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (s1[i - 1] == s2[j - 1]) 0 else 1
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
