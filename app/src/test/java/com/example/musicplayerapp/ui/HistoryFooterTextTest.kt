package com.example.musicplayerapp.ui

import com.example.musicplayerapp.ui.HistoryFooterText.Form.FEW
import com.example.musicplayerapp.ui.HistoryFooterText.Form.MANY
import com.example.musicplayerapp.ui.HistoryFooterText.Form.ONE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Russian agreement for history-content's footer (G4b). Every count the list
 * can hold, 0..30, plus the teens and hundreds that trip a naive last-digit rule.
 */
class HistoryFooterTextTest {

    @Test
    fun `thirty - the frame's own count - takes the form the frame prints`() {
        // "Показаны последние 30 треков"
        assertEquals(MANY, HistoryFooterText.formOf(30))
    }

    @Test
    fun `ones`() {
        for (n in listOf(1, 21, 31, 101, 121)) assertEquals("n=$n", ONE, HistoryFooterText.formOf(n))
    }

    @Test
    fun `twos to fours`() {
        for (n in listOf(2, 3, 4, 22, 23, 24, 102, 104)) assertEquals("n=$n", FEW, HistoryFooterText.formOf(n))
    }

    @Test
    fun `everything else, including the teens`() {
        for (n in listOf(0, 5, 9, 10, 11, 12, 13, 14, 15, 19, 20, 25, 29, 30, 111, 112, 114)) {
            assertEquals("n=$n", MANY, HistoryFooterText.formOf(n))
        }
    }

    @Test
    fun `every count the list can hold has a form`() {
        val expected = mapOf(1 to ONE, 21 to ONE, 2 to FEW, 3 to FEW, 4 to FEW, 22 to FEW, 23 to FEW, 24 to FEW)
        for (n in 1..30) assertEquals("n=$n", expected[n] ?: MANY, HistoryFooterText.formOf(n))
    }
}
