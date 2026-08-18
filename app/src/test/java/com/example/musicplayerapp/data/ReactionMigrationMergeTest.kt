package com.example.musicplayerapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what a v1 `favorites` collection becomes.
 *
 * The merge is a pure function precisely so these can be asserted without a
 * database - the database part is `ReactionMigrationTest`, which runs the real
 * migration against a real v1 file. What is checked here is the part that can
 * silently lose someone's collection: which rows collapse together, and which of
 * their values survive.
 */
class ReactionMigrationMergeTest {

    private fun legacy(
        id: Long,
        artist: String,
        track: String,
        stream: String = "myata",
        addedAt: Long,
    ) = LegacyFavorite(id = id, artist = artist, track = track, stream = stream, addedAt = addedAt)

    @Test
    fun `an ordinary collection migrates row for row`() {
        val merged = ReactionMigration.merge(
            listOf(
                legacy(1, "Depeche Mode", "Enjoy the Silence", addedAt = 1_000),
                legacy(2, "Nick Cave", "Red Right Hand", stream = "gold", addedAt = 2_000),
            )
        )

        assertEquals(2, merged.size)
        assertTrue(merged.all { it.reaction == Reaction.LIKED })

        val depeche = merged.single { it.artist == "Depeche Mode" }
        assertEquals(TrackKey.of("Depeche Mode", "Enjoy the Silence"), depeche.trackKey)
        assertEquals("Enjoy the Silence", depeche.title)
        assertEquals("myata", depeche.stream)
        assertEquals(1_000L, depeche.likedAt)
        assertEquals(1_000L, depeche.updatedAt)

        assertEquals("gold", merged.single { it.artist == "Nick Cave" }.stream)
    }

    @Test
    fun `rows that are one track under the key collapse into one row`() {
        // All four are separate rows in v1, because the unique index was on the raw
        // pair: a trailing space, a different case, a BOM and an en dash instead of
        // a hyphen each made a new row.
        val merged = ReactionMigration.merge(
            listOf(
                legacy(1, "Nick Cave", "Red Right Hand - Live", addedAt = 5_000),
                legacy(2, "  nick cave ", "Red Right Hand - Live", addedAt = 3_000),
                legacy(3, "Nick Cave", "Red Right Hand " + Char(0x2013) + " Live", addedAt = 9_000),
                legacy(4, Char(0xFEFF) + "Nick Cave", "Red  Right   Hand - Live", addedAt = 7_000),
            )
        )

        assertEquals(1, merged.size)
        val row = merged.single()
        assertEquals(TrackKey.of("Nick Cave", "Red Right Hand - Live"), row.trackKey)
        assertEquals(Reaction.LIKED, row.reaction)

        // Since when it has been in the Collection: the earliest save.
        assertEquals(3_000L, row.likedAt)
        // When the Collection last changed for this track: the latest.
        assertEquals(9_000L, row.updatedAt)
        // The words come from the newest row, which is the freshest spelling upstream
        // sent - here the en dash one, normalised away in the key but kept for display.
        assertEquals("Nick Cave", row.artist)
        assertEquals("Red Right Hand " + Char(0x2013) + " Live", row.title)
    }

    @Test
    fun `the newest row wins ties by id`() {
        val merged = ReactionMigration.merge(
            listOf(
                legacy(1, "ABBA", "SOS", stream = "myata", addedAt = 4_000),
                legacy(2, "abba", "sos", stream = "gold", addedAt = 4_000),
            )
        )

        val row = merged.single()
        assertEquals("abba", row.artist)
        assertEquals("gold", row.stream)
        assertEquals(4_000L, row.likedAt)
    }

    @Test
    fun `nothing is dropped when a row cannot be keyed`() {
        // Older builds could save these: an empty artist between tracks, and the
        // jingle sentinel. TrackKey refuses both, and they are still someone's
        // collection, so they migrate under a legacy key.
        val merged = ReactionMigration.merge(
            listOf(
                legacy(1, "", "Some Title", addedAt = 1_000),
                legacy(2, "YOUR MUSIC! YOUR STATION!", "Jingle", addedAt = 2_000),
                legacy(3, "Depeche Mode", "Enjoy the Silence", addedAt = 3_000),
            )
        )

        assertEquals(3, merged.size)
        assertTrue(merged.all { it.reaction == Reaction.LIKED })

        val legacyKeys = merged.map { it.trackKey }.filter { it.startsWith(ReactionMigration.LEGACY_KEY_PREFIX) }
        assertEquals(2, legacyKeys.size)
        // Two unkeyable rows are still two different tracks.
        assertNotEquals(legacyKeys[0], legacyKeys[1])
        // And a legacy key can never be mistaken for a v1 key, which is 64 hex chars.
        assertTrue(legacyKeys.none { it.length == 64 && it.all { c -> c in "0123456789abcdef" } })
    }

    @Test
    fun `an unkeyable row still merges with its own duplicates`() {
        val merged = ReactionMigration.merge(
            listOf(
                legacy(1, "", "Some Title", addedAt = 1_000),
                legacy(2, "  ", "Some Title ", addedAt = 4_000),
            )
        )

        assertEquals(1, merged.size)
        assertEquals(1_000L, merged.single().likedAt)
        assertEquals(4_000L, merged.single().updatedAt)
    }

    @Test
    fun `output is ordered oldest first, so inserts are deterministic`() {
        val merged = ReactionMigration.merge(
            listOf(
                legacy(1, "C", "Third", addedAt = 3_000),
                legacy(2, "A", "First", addedAt = 1_000),
                legacy(3, "B", "Second", addedAt = 2_000),
            )
        )

        assertEquals(listOf("A", "B", "C"), merged.map { it.artist })
    }

    @Test
    fun `an empty collection migrates to nothing`() {
        assertEquals(emptyList<TrackReaction>(), ReactionMigration.merge(emptyList()))
    }

    @Test
    fun `keyFor is TrackKey v1 whenever v1 can key the track`() {
        assertEquals(
            TrackKey.of("Depeche Mode", "Enjoy the Silence"),
            ReactionMigration.keyFor("Depeche Mode", "Enjoy the Silence")
        )
    }
}
