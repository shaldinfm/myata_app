package com.example.musicplayerapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionEvent
import com.example.musicplayerapp.data.ReactionMigration
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v2 -> v3 migration against a real v2 database file.
 *
 * The v2 `track_reaction` table is created here with the exact SQL Room exported
 * for it and filled the way a listener's phone would have it - likes, a withdrawn
 * like, a dislike - so what runs is the real migration on real existing data,
 * including Room's own validation of the result, which throws on open if the
 * migrated schema is not what v3 expects.
 *
 * Two things are being protected. One is somebody's Collection, as in
 * [ReactionMigrationTest]: this migration must not touch it, and the test says so
 * row by row rather than by counting. The other is that the new queue starts
 * **empty** - a back-filled outbox would tell the backend that every listener
 * re-liked their whole Collection on the day they updated the app.
 */
@RunWith(AndroidJUnit4::class)
class ReactionOutboxMigrationTest {

    private val dbName = "outbox_migration_test.db"

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val depecheKey = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!
    private val caveKey = TrackKey.of("Nick Cave", "Red Right Hand")!!
    private val bowieKey = TrackKey.of("David Bowie", "Heroes")!!

    /** Exactly what Room exported for the v2 `track_reaction` entity. */
    private val createTrackReactionV2 =
        "CREATE TABLE IF NOT EXISTS `track_reaction` (`track_key` TEXT NOT NULL, " +
            "`artist` TEXT NOT NULL, `title` TEXT NOT NULL, `stream` TEXT NOT NULL, " +
            "`reaction` TEXT NOT NULL, `liked_at` INTEGER, `updated_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`track_key`))"

    @Before
    fun deleteAnyLeftover() {
        context.deleteDatabase(dbName)
    }

    private fun createV2Database(rows: List<Array<Any?>>) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(createTrackReactionV2)
        for (row in rows) {
            db.execSQL(
                "INSERT INTO `track_reaction` (`track_key`, `artist`, `title`, `stream`, " +
                    "`reaction`, `liked_at`, `updated_at`) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row,
            )
        }
        db.version = 2
        db.close()
    }

    /** A Collection with one of each state in it. */
    private fun createOrdinaryV2Database() = createV2Database(
        listOf(
            arrayOf(depecheKey, "Depeche Mode", "Enjoy the Silence", "myata", "LIKED", 1_000L, 1_000L),
            arrayOf(caveKey, "Nick Cave", "Red Right Hand", "gold", "LIKED", 2_000L, 2_000L),
            // Withdrawn: kept as a row, out of the Collection.
            arrayOf(bowieKey, "David Bowie", "Heroes", "myata", "NEUTRAL", 3_000L, 4_000L),
        )
    )

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(ReactionMigration.MIGRATION_1_2, ReactionMigration.MIGRATION_2_3)
            .build()

    // ==================== nothing that exists is disturbed ====================

    @Test
    fun existing_reactions_survive_unchanged() = runBlocking {
        createOrdinaryV2Database()

        val db = open()
        try {
            // Opening at all is the schema check: Room validates the migrated
            // database against the v3 schema and throws if it does not match.
            val collection = db.reactionDao().likedTracks().first()

            assertEquals(listOf("Nick Cave", "Depeche Mode"), collection.map { it.artist })
            assertEquals(listOf(2_000L, 1_000L), collection.map { it.addedAt })
            assertEquals(listOf("gold", "myata"), collection.map { it.stream })
            assertEquals(listOf(caveKey, depecheKey), collection.map { it.trackKey })

            // Every state comes across as itself, including the withdrawn one - it
            // is still a row, and still not a dislike.
            assertEquals(Reaction.LIKED, db.reactionDao().find(depecheKey)!!.reaction)
            assertEquals(Reaction.LIKED, db.reactionDao().find(caveKey)!!.reaction)

            val bowie = db.reactionDao().find(bowieKey)
            assertNotNull(bowie)
            assertEquals(Reaction.NEUTRAL, bowie!!.reaction)
            assertEquals("David Bowie", bowie.artist)
            assertEquals("Heroes", bowie.title)
            assertEquals(3_000L, bowie.likedAt)
            assertEquals(4_000L, bowie.updatedAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun the_new_outbox_starts_empty() = runBlocking {
        createOrdinaryV2Database()

        val db = open()
        try {
            // Three reactions already on the phone, and nothing owed to the backend
            // for them: they were reported when they happened. The queue is for
            // transitions this build observes.
            assertEquals(0, db.reactionOutboxDao().count())
            assertEquals(emptyList<ReactionOutboxEntry>(), db.reactionOutboxDao().pending())
        } finally {
            db.close()
        }
    }

    @Test
    fun an_empty_v2_database_migrates_cleanly() = runBlocking {
        createV2Database(emptyList())

        val db = open()
        try {
            assertEquals(emptyList<Any>(), db.reactionDao().likedTracks().first())
            assertEquals(0, db.reactionOutboxDao().count())
        } finally {
            db.close()
        }
    }

    @Test
    fun the_migrated_database_has_both_tables_and_the_outbox_index() = runBlocking {
        createOrdinaryV2Database()

        val db = open()
        try {
            db.reactionDao().likedTracks().first()

            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name IN ('track_reaction', 'reaction_outbox')"
            ).use { cursor ->
                val tables = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertTrue("track_reaction" in tables)
                assertTrue("reaction_outbox" in tables)
            }

            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND tbl_name = 'reaction_outbox'"
            ).use { cursor ->
                val indices = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertTrue(
                    "expected the next_attempt_at index, got $indices",
                    "index_reaction_outbox_next_attempt_at" in indices,
                )
            }
        } finally {
            db.close()
        }
    }

    // ==================== and then it works ====================

    @Test
    fun a_reaction_after_the_migration_queues_its_event() = runBlocking {
        createOrdinaryV2Database()

        val db = open()
        try {
            // Withdrawing a Like that existed before the migration: the event has to
            // carry words that were written by the previous version of the app.
            assertTrue(db.reactionDao().unlike(depecheKey, 9_000L))

            val event = db.reactionOutboxDao().pending().single()
            assertEquals(ReactionEvent.UNLIKE, event.eventType)
            assertEquals(depecheKey, event.trackKey)
            assertEquals("Depeche Mode", event.artist)
            assertEquals("Enjoy the Silence", event.title)
            assertEquals("myata", event.stream)
            assertEquals(9_000L, event.occurredAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun pending_events_survive_a_process_restart() = runBlocking {
        createOrdinaryV2Database()

        // First run: migrate, then act twice. Nothing sends anything, so both are
        // still owed when the process goes away.
        val first = open()
        try {
            assertTrue(first.reactionDao().unlike(caveKey, 5_000L))
            assertTrue(
                first.reactionDao()
                    .dislike(bowieKey, "David Bowie", "Heroes", "myata", 6_000L)
            )
            assertEquals(2, first.reactionOutboxDao().count())
        } finally {
            first.close()
        }

        // Second run, as after process death. This is the whole reason the queue is
        // a table: the phone that recorded these may have been in a lift, and the
        // process is gone before it came out.
        val second = open()
        try {
            val pending = second.reactionOutboxDao().pending()
            assertEquals(2, pending.size)
            assertEquals(
                listOf(ReactionEvent.UNLIKE, ReactionEvent.DISLIKE),
                pending.map { it.eventType },
            )
            assertEquals(listOf(caveKey, bowieKey), pending.map { it.trackKey })
            assertEquals(listOf(5_000L, 6_000L), pending.map { it.occurredAt })
            assertEquals(listOf("Nick Cave", "David Bowie"), pending.map { it.artist })

            // And the state they describe survived with them.
            assertEquals(Reaction.NEUTRAL, second.reactionDao().find(caveKey)!!.reaction)
            assertEquals(Reaction.DISLIKED, second.reactionDao().find(bowieKey)!!.reaction)
            assertEquals(listOf(depecheKey), second.reactionDao().likedTracks().first().map { it.trackKey })

            // A delivered event leaves; the rest waits for the run after this one.
            assertEquals(1, second.reactionOutboxDao().delete(pending.first().eventId))
        } finally {
            second.close()
        }

        val third = open()
        try {
            assertEquals(
                listOf(ReactionEvent.DISLIKE),
                third.reactionOutboxDao().pending().map { it.eventType },
            )
        } finally {
            third.close()
        }
    }

    @Test
    fun a_v1_database_reaches_v3_with_its_collection_and_an_empty_queue() = runBlocking {
        // The other half of the upgrade surface: a phone that skipped v2 entirely
        // runs 1 -> 2 -> 3 in one open, and must land in the same place.
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        legacy.execSQL(
            "CREATE TABLE IF NOT EXISTS `favorites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`artist` TEXT NOT NULL, `track` TEXT NOT NULL, `stream` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL)"
        )
        legacy.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_artist_track` ON `favorites` (`artist`, `track`)"
        )
        legacy.execSQL(
            "INSERT INTO `favorites` (`artist`, `track`, `stream`, `addedAt`) VALUES (?, ?, ?, ?)",
            arrayOf<Any>("Depeche Mode", "Enjoy the Silence", "myata", 1_000L),
        )
        legacy.version = 1
        legacy.close()

        val db = open()
        try {
            val collection = db.reactionDao().likedTracks().first()
            assertEquals(1, collection.size)
            assertEquals(depecheKey, collection.single().trackKey)
            assertEquals(1_000L, collection.single().addedAt)
            assertEquals(0, db.reactionOutboxDao().count())
        } finally {
            db.close()
        }
    }
}
