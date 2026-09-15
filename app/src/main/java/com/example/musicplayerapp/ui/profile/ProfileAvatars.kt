package com.example.musicplayerapp.ui.profile

import androidx.annotation.DrawableRes
import com.example.musicplayerapp.R

/**
 * One of the 24 avatars in `Выбор аватара`.
 *
 * [key] is what the account stores: `myata-01` … `myata-24`, a Radio Myata identifier that
 * is independent of the artwork, so an image can be re-exported without touching anything
 * an account has saved. [number] is the one-based place in the grid, for the spoken
 * description.
 */
data class ProfileAvatar(
    val key: String,
    val number: Int,
    @DrawableRes val drawable: Int,
)

/**
 * The fixed avatar set, in grid reading order - left to right, top to bottom, four across.
 *
 * The artwork is bundled (`drawable-nodpi/avatar_myata_NN.webp`), owner-created and prepared
 * by `tools/avatars/export_avatars.py` from the approved candidates; `tools/avatars/manifest.json`
 * records each one's source file, hash and crop. Nothing here is fetched, and nothing is drawn
 * in code.
 *
 * ## What an account's stored key means
 *
 * [resolve] is the one place that answers it. A key this build does not know - blank,
 * mistyped by hand in the dashboard, the pre-release `m3-NN` keys, or one from a future build
 * with more avatars - is the same as no avatar: the profile falls back to its default, the
 * initial on `primary`, rather than to some other avatar. Guessing a picture for somebody is
 * worse than showing their letter.
 */
object ProfileAvatars {

    val all: List<ProfileAvatar> = listOf(
        R.drawable.avatar_myata_01,
        R.drawable.avatar_myata_02,
        R.drawable.avatar_myata_03,
        R.drawable.avatar_myata_04,
        R.drawable.avatar_myata_05,
        R.drawable.avatar_myata_06,
        R.drawable.avatar_myata_07,
        R.drawable.avatar_myata_08,
        R.drawable.avatar_myata_09,
        R.drawable.avatar_myata_10,
        R.drawable.avatar_myata_11,
        R.drawable.avatar_myata_12,
        R.drawable.avatar_myata_13,
        R.drawable.avatar_myata_14,
        R.drawable.avatar_myata_15,
        R.drawable.avatar_myata_16,
        R.drawable.avatar_myata_17,
        R.drawable.avatar_myata_18,
        R.drawable.avatar_myata_19,
        R.drawable.avatar_myata_20,
        R.drawable.avatar_myata_21,
        R.drawable.avatar_myata_22,
        R.drawable.avatar_myata_23,
        R.drawable.avatar_myata_24,
    ).mapIndexed { index, drawable ->
        val number = index + 1
        // padStart, not "%02d".format: a stored key must not depend on the locale's digits.
        ProfileAvatar(key = "myata-" + number.toString().padStart(2, '0'), number = number, drawable = drawable)
    }

    /** The avatar [storedKey] names, or null for none - including a key nobody knows. */
    fun resolve(storedKey: String?): ProfileAvatar? {
        val key = storedKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return all.firstOrNull { it.key == key }
    }
}
