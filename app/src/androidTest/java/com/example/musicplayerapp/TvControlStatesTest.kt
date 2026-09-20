package com.example.musicplayerapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV playback controls' Material 3 states, measured rather than eyeballed.
 *
 * The stream pills and the play button draw their states - unselected, selected,
 * focused, pressed - as state lists, which is exactly the sort of thing that looks
 * right in the one state whoever wrote it was looking at and is wrong in the other
 * three. So each state of each control is drawn to a bitmap here and compared with
 * the others: four combinations of a pill that all have to be distinguishable,
 * because on a remote "which station am I on" and "where is the D-pad" are two
 * different questions and a control that answers both with the same grey is a
 * control that answers neither.
 *
 * What is deliberately not asserted is the exact colour of any state: the point is
 * that they differ, and that the selected label stays the yellow the TV has always
 * used for the station now playing.
 */
@RunWith(AndroidJUnit4::class)
class TvControlStatesTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = ContextThemeWrapper(
            instrumentation.targetContext,
            R.style.TvTheme,
        )

    private fun player(): ViewGroup =
        LayoutInflater.from(context).inflate(R.layout.fragment_tv_player, null) as ViewGroup

    @Test
    fun the_four_combinations_of_the_stream_pill_are_all_different() {
        val pill = player().findViewById<TextView>(R.id.btn_stream_myata)

        val unselected = drawn(pill, 0)
        val selected = drawn(pill, android.R.attr.state_selected)
        val focused = drawn(pill, android.R.attr.state_focused)
        val focusedSelected = drawn(
            pill,
            android.R.attr.state_focused,
            android.R.attr.state_selected,
        )
        val pressed = drawn(pill, android.R.attr.state_pressed)

        assertNotEquals("selected looks like unselected", unselected, selected)
        assertNotEquals("focused looks like unfocused", unselected, focused)
        assertNotEquals("focused+selected looks like selected", selected, focusedSelected)
        assertNotEquals("focused+selected looks like focused", focused, focusedSelected)
        assertNotEquals("pressed looks like unpressed", unselected, pressed)

        val states = listOf(unselected, selected, focused, focusedSelected)
        assertEquals(
            "two of the four states draw the same surface: $states",
            4,
            states.toSet().size,
        )
    }

    @Test
    fun the_selected_label_stays_the_myata_yellow() {
        val pill = player().findViewById<TextView>(R.id.btn_stream_myata)

        assertEquals(
            "the station now playing is no longer the yellow it was",
            0xFFFFFF00.toInt(),
            pill.textColors.getColorForState(intArrayOf(android.R.attr.state_selected), 0),
        )
        assertEquals(
            "an unselected station should be plain white",
            0xFFFFFFFF.toInt(),
            pill.textColors.getColorForState(intArrayOf(), 0),
        )
    }

    @Test
    fun the_play_button_answers_focus_and_press() {
        val button = player().findViewById<ImageButton>(R.id.btn_play_pause)

        val idle = drawn(button, 0)
        val focused = drawn(button, android.R.attr.state_focused)
        val pressed = drawn(button, android.R.attr.state_pressed)

        assertNotEquals("the play button's focused state is invisible", idle, focused)
        assertNotEquals("the play button's pressed state is invisible", idle, pressed)
    }

    @Test
    fun the_play_and_pause_glyphs_are_icons_rather_than_plates() {
        // They used to be 106dp discs with the glyph cut out of them, drawn at the
        // button's full size; they are 24dp icons now, in Material 3's proportion
        // for a 42dp control.
        val density = context.resources.displayMetrics.density
        for (id in listOf(R.drawable.ic_tv_play, R.drawable.ic_tv_pause)) {
            val glyph = context.getDrawable(id)!!
            assertEquals(
                "glyph $id is not 24dp wide",
                24 * density,
                glyph.intrinsicWidth.toFloat(),
                1f,
            )
        }
    }

    /**
     * What one control draws in one state, averaged over the whole shape so both
     * the container and the focus ring are accounted for.
     */
    private fun drawn(view: View, vararg state: Int): Int {
        val source: Drawable = view.background
        val drawable = source.constantState?.newDrawable() ?: source
        drawable.setState(state)
        drawable.jumpToCurrentState()

        val width = 96
        val height = 48
        drawable.setBounds(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))

        var red = 0L
        var green = 0L
        var blue = 0L
        var alpha = 0L
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            alpha += (pixel ushr 24) and 0xFF
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
        }
        bitmap.recycle()

        val count = pixels.size.toLong()
        // Quantised, so a one-unit rounding difference between two states that are
        // meant to be the same does not read as a difference.
        return ((alpha / count / 8).toInt() shl 24) or
            ((red / count / 8).toInt() shl 16) or
            ((green / count / 8).toInt() shl 8) or
            (blue / count / 8).toInt()
    }
}
