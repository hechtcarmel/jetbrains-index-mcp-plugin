package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

class StringUtilsUnitTest : TestCase() {

    fun testLevenshteinDistanceIdenticalStrings() {
        assertEquals(0, StringUtils.levenshteinDistance("hello", "hello"))
        assertEquals(0, StringUtils.levenshteinDistance("", ""))
        assertEquals(0, StringUtils.levenshteinDistance("a", "a"))
    }

    fun testLevenshteinDistanceEmptyString() {
        assertEquals(5, StringUtils.levenshteinDistance("hello", ""))
        assertEquals(5, StringUtils.levenshteinDistance("", "hello"))
    }

    fun testLevenshteinDistanceSingleCharDifference() {
        assertEquals(1, StringUtils.levenshteinDistance("hello", "hallo"))
        assertEquals(1, StringUtils.levenshteinDistance("cat", "bat"))
    }

    fun testLevenshteinDistanceInsertion() {
        assertEquals(1, StringUtils.levenshteinDistance("hello", "helloo"))
        assertEquals(1, StringUtils.levenshteinDistance("cat", "cats"))
    }

    fun testLevenshteinDistanceDeletion() {
        assertEquals(1, StringUtils.levenshteinDistance("hello", "hell"))
        assertEquals(1, StringUtils.levenshteinDistance("cats", "cat"))
    }

    fun testLevenshteinDistanceCompletelyDifferent() {
        assertEquals(3, StringUtils.levenshteinDistance("abc", "xyz"))
        assertEquals(4, StringUtils.levenshteinDistance("java", "rust"))
    }

    fun testLevenshteinDistanceCaseSensitive() {
        assertEquals(1, StringUtils.levenshteinDistance("Hello", "hello"))
        assertEquals(5, StringUtils.levenshteinDistance("HELLO", "hello"))
    }
}
