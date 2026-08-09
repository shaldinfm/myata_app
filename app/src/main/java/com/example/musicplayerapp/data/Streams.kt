package com.example.musicplayerapp.data

/**
 * The only stream identifiers the app recognises.
 *
 * These keys are used as map keys, as intent extras and to pick a MediaItem, and
 * several of those lookups are `when` blocks with no else branch - an unrecognised
 * key silently ends up with no media item and nothing plays. Anything arriving
 * from outside (an intent action, a deep link, a restored state) has to come
 * through [normalise] before it is treated as a stream (issue #14).
 */
object Streams {

    const val MYATA = "myata"
    const val GOLD = "gold"
    const val XTRA = "myata_hits"

    /** What the app plays when nothing valid has been chosen. */
    const val DEFAULT = MYATA

    private val KNOWN = setOf(MYATA, GOLD, XTRA)

    /** "xtra" is the display name for the myata_hits stream; accept it as an alias. */
    private val ALIASES = mapOf("xtra" to XTRA, "hits" to XTRA)

    fun isKnown(value: String?): Boolean = value != null && value in KNOWN

    /**
     * Returns the canonical stream key, or null if this is not a stream at all.
     * Framework intent actions such as android.intent.action.PLAY land here and
     * must return null rather than being used as a key.
     */
    fun normalise(value: String?): String? {
        val trimmed = value?.trim()?.lowercase() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed in KNOWN) return trimmed
        return ALIASES[trimmed]
    }
}
