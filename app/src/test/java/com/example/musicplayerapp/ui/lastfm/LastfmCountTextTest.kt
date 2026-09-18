package com.example.musicplayerapp.ui.lastfm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The connected card's count line.
 *
 * The forms are passed in as literals rather than read from resources, which is
 * what keeps this a JVM test - and is also the whole design point: the agreement
 * is decided by the number, in Kotlin, and never by the device's locale.
 */
class LastfmCountTextTest {

    private val one = "скроббл"
    private val few = "скроббла"
    private val many = "скробблов"

    private fun noun(n: Long) = LastfmCountText.noun(n, one, few, many)

    @Test
    fun `the one form is 1, 21, 31 and so on`() {
        listOf(1L, 21L, 31L, 101L, 1_241L).forEach {
            assertEquals("$it", one, noun(it))
        }
    }

    @Test
    fun `the few form is 2 to 4 and their decades`() {
        listOf(2L, 3L, 4L, 22L, 23L, 24L, 1_242L, 1_243L).forEach {
            assertEquals("$it", few, noun(it))
        }
    }

    @Test
    fun `the many form is 0, 5 to 20, and everything else`() {
        listOf(0L, 5L, 9L, 10L, 15L, 20L, 25L, 100L, 1_248L).forEach {
            assertEquals("$it", many, noun(it))
        }
    }

    @Test
    fun `11 to 14 take the many form even though they end in 1 to 4`() {
        // The exception the whole rule exists for. 11 ends in 1 but is not `одиннадцать
        // скроббл`, and a naive `% 10` would say it is.
        listOf(11L, 12L, 13L, 14L, 111L, 112L, 1_011L, 5_014L).forEach {
            assertEquals("$it", many, noun(it))
        }
    }

    @Test
    fun `thousands are grouped with a non-breaking space`() {
        // The frozen card draws `22 258`. The separator is part of the language this
        // string is written in, not of the device's locale, so it is fixed here -
        // NumberFormat would print `22,258` on an English-locale phone.
        assertEquals("0", LastfmCountText.group(0))
        assertEquals("999", LastfmCountText.group(999))
        assertEquals("1 000", LastfmCountText.group(1_000))
        assertEquals("1 248", LastfmCountText.group(1_248))
        assertEquals("22 258", LastfmCountText.group(22_258))
        assertEquals("1 234 567", LastfmCountText.group(1_234_567))
    }

    @Test
    fun `the whole line reads as the frozen card does`() {
        assertEquals("1 248 скробблов", LastfmCountText.scrobbles(1_248, one, few, many))
        assertEquals("22 258 скробблов", LastfmCountText.scrobbles(22_258, one, few, many))
        assertEquals("1 скроббл", LastfmCountText.scrobbles(1, one, few, many))
        assertEquals("2 скроббла", LastfmCountText.scrobbles(2, one, few, many))
        assertEquals("0 скробблов", LastfmCountText.scrobbles(0, one, few, many))
    }

    @Test
    fun `the number and its noun never wrap apart`() {
        // A count that broke across two lines would read as two different facts.
        val line = LastfmCountText.scrobbles(1_248, one, few, many)
        assertEquals("no ordinary space anywhere in the line", false, line.contains(' '))
    }

    @Test
    fun `a negative count is treated as zero rather than printed`() {
        // The service cannot return one. If it somehow did, a minus sign in this
        // line would be nonsense, and nonsense about somebody's account.
        assertEquals("0 скробблов", LastfmCountText.scrobbles(-5, one, few, many))
        assertEquals(many, noun(-1))
    }
}
