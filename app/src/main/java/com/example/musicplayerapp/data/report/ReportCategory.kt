package com.example.musicplayerapp.data.report

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.musicplayerapp.R

/**
 * The five categories on `report-empty` 2517:2129, in the frozen order.
 *
 * The [wire] key is what leaves the device, and the label is what the listener
 * reads. They are separate on purpose: the Russian label is copy and may be
 * rewritten, while the key is what whoever triages a report groups by, so a
 * copy edit must not silently split one bucket into two. `ReportCategoryTest`
 * pins the keys the way `AboutLinksTest` pins the destinations "О нас" opens.
 *
 * Nothing is preselected. The frozen empty state has no selection and a disabled
 * Send, and the category is the only thing that enables it.
 */
enum class ReportCategory(
    val wire: String,
    @StringRes val label: Int,
    @DrawableRes val glyph: Int,
) {
    PLAYBACK_WONT_START(
        wire = "playback_wont_start",
        label = R.string.report_category_playback_wont_start,
        glyph = R.drawable.ic_report_play_note,
    ),
    STOPPED_BY_ITSELF(
        wire = "stopped_by_itself",
        label = R.string.report_category_stopped_by_itself,
        glyph = R.drawable.ic_report_pause,
    ),
    HEADPHONES(
        wire = "headphones",
        label = R.string.report_category_headphones,
        glyph = R.drawable.ic_report_headphone,
    ),
    INTERFACE(
        wire = "ui",
        label = R.string.report_category_interface,
        glyph = R.drawable.ic_report_layout,
    ),
    OTHER(
        wire = "other",
        label = R.string.report_category_other,
        glyph = R.drawable.ic_report_question,
    );

    companion object {
        /** Restores a selection across a rotation without persisting a label. */
        fun fromWire(value: String?): ReportCategory? =
            entries.firstOrNull { it.wire == value }
    }
}
