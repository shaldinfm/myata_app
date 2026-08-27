package com.example.musicplayerapp.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * One legacy `favorites` row, as it exists in a v1 database on a listener's phone.
 */
data class LegacyFavorite(
    val id: Long,
    val artist: String,
    val track: String,
    val stream: String,
    val addedAt: Long,
)

/**
 * Turning the old `favorites` table into [TrackReaction] rows.
 *
 * Everything in someone's Collection was put there deliberately, so the one thing
 * this migration may not do is lose a row. It is also the first time [TrackKey] is
 * applied to data that already exists, and that is where the interesting part is:
 * `favorites` was uniquely indexed on the **raw** `(artist, track)` pair, so a
 * collection can legitimately hold several rows - a trailing space, a BOM, an en
 * dash instead of a hyphen - that are one track under the key. They have to become
 * one row, and [merge] decides how.
 *
 * The rules, and why:
 *
 *  - **`liked_at` is the earliest `addedAt` in the group.** It answers "since when
 *    has this been in my Collection", and the earliest save is the true answer. It
 *    also keeps the row roughly where the reader last saw it in the list.
 *  - **`updated_at` is the latest.** That is when the collection last changed for
 *    this track.
 *  - **artist, title and stream come from the newest row**, tie-broken by `id`, so
 *    the words shown are the most recent spelling the playout system sent.
 *  - **Everything is LIKED.** A v1 row existing meant exactly one thing.
 *
 * No row is ever dropped: a track whose fields cannot produce a v1 key - an empty
 * artist, or the jingle sentinel, both of which older builds could save - is kept
 * under a [LEGACY_KEY_PREFIX] key built from its raw fields. Those keys are outside
 * the v1 key space, which is 64 hex characters, so they cannot collide with a real
 * one. Such a row can still be seen and removed in the Collection; it just cannot
 * be matched from the PLAYER, which is already true today.
 *
 * Nothing here reports anything. A migration is not a listener expressing an
 * opinion, so no analytics call and no network call happens on this path.
 */
object ReactionMigration {

    /** Namespace for rows the v1 key cannot describe. Never collides with a real key. */
    const val LEGACY_KEY_PREFIX = "legacy:"

    private val LEGACY_SEPARATOR = Char(0x1F)

    /**
     * Exactly the table Room expects for [TrackReaction] at version 2.
     *
     * Written out rather than derived, because a migration has to build the schema
     * the *old* app version knew how to describe, and Room validates what it finds
     * against what it expects on the next open. `ReactionMigrationTest` runs that
     * validation for real.
     */
    private const val CREATE_TRACK_REACTION = "CREATE TABLE IF NOT EXISTS `track_reaction` " +
        "(`track_key` TEXT NOT NULL, `artist` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`stream` TEXT NOT NULL, `reaction` TEXT NOT NULL, `liked_at` INTEGER, " +
        "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`track_key`))"

    private const val INSERT_TRACK_REACTION = "INSERT OR REPLACE INTO `track_reaction` " +
        "(`track_key`, `artist`, `title`, `stream`, `reaction`, `liked_at`, `updated_at`) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?)"

    /**
     * Exactly the table Room expects for [ReactionOutboxEntry] at version 3, and its
     * index, written out for the same reason [CREATE_TRACK_REACTION] is: what a
     * migration builds has to match what the next open validates, and
     * `ReactionOutboxMigrationTest` runs that validation for real.
     */
    internal const val CREATE_REACTION_OUTBOX = "CREATE TABLE IF NOT EXISTS `reaction_outbox` " +
        "(`event_id` TEXT NOT NULL, `track_key` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `stream` TEXT NOT NULL, `event_type` TEXT NOT NULL, " +
        "`occurred_at` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, " +
        "`next_attempt_at` INTEGER NOT NULL, PRIMARY KEY(`event_id`))"

    internal const val CREATE_REACTION_OUTBOX_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_reaction_outbox_next_attempt_at` " +
            "ON `reaction_outbox` (`next_attempt_at`)"

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_TRACK_REACTION)

            for (row in merge(readLegacyFavorites(db))) {
                db.execSQL(
                    INSERT_TRACK_REACTION,
                    arrayOf<Any?>(
                        row.trackKey,
                        row.artist,
                        row.title,
                        row.stream,
                        row.reaction.name,
                        row.likedAt,
                        row.updatedAt,
                    )
                )
            }

            db.execSQL("DROP TABLE IF EXISTS `favorites`")
        }
    }

    /**
     * Adding the reaction outbox: one CREATE TABLE, one CREATE INDEX, nothing else.
     *
     * The interesting property of this migration is everything it does **not** do.
     * It reads no existing row, so a Collection cannot be reshaped by a bug here; it
     * drops nothing, so nothing can be lost; and it back-fills no events, so the
     * queue starts empty.
     *
     * That last one is a decision rather than an omission. Every reaction already on
     * a phone was reported to the sheet when it happened, and the rows carry no
     * record of which. Synthesising a LIKE for each of them would tell the backend
     * that everybody re-liked their whole Collection the day they updated the app -
     * a burst of acts at a timestamp nobody acted at. The outbox is for transitions
     * this build observes; the existing state belongs to a separate, deliberate
     * backfill if one is ever wanted, and that is not this.
     */
    /**
     * The G-A7 protocol cutover: two additive columns, no row read, none rewritten.
     *
     * `sync_protocol` arrives with a SQL default of `LEGACY`, and that default is the
     * whole migration. Every row already in the outbox was written by a build that
     * delivers through the two-call path, and the atomic RPC deliberately refuses an
     * event it has seen but never marked - so a migrated row that reached the new
     * path would be rejected outright. Back-filling them to `LEGACY` in one statement
     * keeps them on the protocol that wrote them.
     *
     * The default never applies to a row this build inserts: Room names every entity
     * column in its generated INSERT, so the value always comes from
     * [ReactionDao.enqueue], which chooses it deliberately per track.
     *
     * `remote_rev` arrives null for the same reason the outbox started empty at 2->3:
     * this device has never been told a revision for any of these rows, and inventing
     * one would be a claim about the server that nothing supports.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `reaction_outbox` ADD COLUMN `sync_protocol` " +
                    "TEXT NOT NULL DEFAULT 'LEGACY'"
            )
            db.execSQL("ALTER TABLE `track_reaction` ADD COLUMN `remote_rev` INTEGER")
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_REACTION_OUTBOX)
            db.execSQL(CREATE_REACTION_OUTBOX_INDEX)
        }
    }

    /**
     * The identity a legacy row migrates under: its v1 key, or a legacy fallback
     * when the row is something v1 refuses to key at all.
     */
    fun keyFor(artist: String, track: String): String =
        TrackKey.of(artist, track)
            ?: (LEGACY_KEY_PREFIX + artist.trim() + LEGACY_SEPARATOR + track.trim())

    /**
     * Legacy rows as reaction rows, one per key. Pure, so the rules above are
     * testable without a database. Ordered oldest first, so the insert order is
     * deterministic.
     */
    fun merge(rows: List<LegacyFavorite>): List<TrackReaction> =
        rows.groupBy { keyFor(it.artist, it.track) }
            .map { (key, group) ->
                val newest = group.maxWith(compareBy({ it.addedAt }, { it.id }))
                TrackReaction(
                    trackKey = key,
                    artist = newest.artist,
                    title = newest.track,
                    stream = newest.stream,
                    reaction = Reaction.LIKED,
                    likedAt = group.minOf { it.addedAt },
                    updatedAt = group.maxOf { it.addedAt },
                )
            }
            .sortedWith(compareBy({ it.likedAt }, { it.trackKey }))

    private fun readLegacyFavorites(db: SupportSQLiteDatabase): List<LegacyFavorite> {
        val rows = mutableListOf<LegacyFavorite>()
        db.query("SELECT `id`, `artist`, `track`, `stream`, `addedAt` FROM `favorites`").use { cursor ->
            while (cursor.moveToNext()) {
                rows += LegacyFavorite(
                    id = cursor.getLong(0),
                    artist = cursor.getString(1) ?: "",
                    track = cursor.getString(2) ?: "",
                    stream = cursor.getString(3) ?: Streams.DEFAULT,
                    addedAt = cursor.getLong(4),
                )
            }
        }
        return rows
    }
}
