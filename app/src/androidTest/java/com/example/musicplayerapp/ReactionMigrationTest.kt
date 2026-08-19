package com.example.musicplayerapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionMigration
import com.example.musicplayerapp.data.TrackKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v1 -> v2 migration against a real v1 database file.
 *
 * The v1 `favorites` table is created here with the exact SQL Room generated for
 * it, and filled the way a listener's phone would have it, so what is exercised is
 * the real migration on real old data - including Room's own validation of the
 * result, which throws on open if the migrated schema is not what v2 expects.
 *
 * The thing being protected is somebody's collection. A migration bug here is not a
 * crash, it is a Collection that quietly comes back shorter.
 */
@RunWith(AndroidJUnit4::class)
class ReactionMigrationTest {

    private val dbName = "migration_test.db"

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Exactly what Room generated for the v1 `favorites` entity. */
    private val createFavoritesV1 =
        "CREATE TABLE IF NOT EXISTS `favorites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`artist` TEXT NOT NULL, `track` TEXT NOT NULL, `stream` TEXT NOT NULL, " +
            "`addedAt` INTEGER NOT NULL)"

    private val createFavoritesIndexV1 =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_artist_track` ON `favorites` (`artist`, `track`)"

    @Before
    fun deleteAnyLeftover() {
        context.deleteDatabase(dbName)
    }

    private fun createV1Database(rows: List<Array<Any>>) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(createFavoritesV1)
        db.execSQL(createFavoritesIndexV1)
        for (row in rows) {
            db.execSQL(
                "INSERT INTO `favorites` (`artist`, `track`, `stream`, `addedAt`) VALUES (?, ?, ?, ?)",
                row,
            )
        }
        db.version = 1
        db.close()
    }

    /**
     * Every migration, not just the one under test: the database class has moved on
     * since v2, and a phone that has been sitting on v1 has to reach whatever the
     * current version is in one open. Registering only 1 -> 2 would make this test
     * fail the moment a v3 exists - which is exactly what it did - while saying
     * nothing about the v1 data these tests are actually about.
     */
    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                ReactionMigration.MIGRATION_1_2,
                ReactionMigration.MIGRATION_2_3,
            )
            .build()

    @Test
    fun an_ordinary_collection_survives() = runBlocking {
        createV1Database(
            listOf(
                arrayOf("Depeche Mode", "Enjoy the Silence", "myata", 1_000L),
                arrayOf("Nick Cave", "Red Right Hand", "gold", 2_000L),
            )
        )

        val db = openMigrated()
        try {
            val collection = db.reactionDao().likedTracks().first()

            // Newest first, exactly as the v1 list was ordered.
            assertEquals(listOf("Nick Cave", "Depeche Mode"), collection.map { it.artist })
            assertEquals(listOf(2_000L, 1_000L), collection.map { it.addedAt })
            assertEquals(listOf("gold", "myata"), collection.map { it.stream })
            assertEquals(
                TrackKey.of("Depeche Mode", "Enjoy the Silence"),
                collection.last().trackKey,
            )

            // Everything that was saved is LIKED.
            assertEquals(
                Reaction.LIKED,
                db.reactionDao().find(collection.first().trackKey)!!.reaction,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun rows_that_are_one_track_under_the_key_become_one_row() = runBlocking {
        // Four separate v1 rows - the unique index was on the raw pair - that are one
        // track under TrackKey v1.
        createV1Database(
            listOf(
                arrayOf("Nick Cave", "Red Right Hand - Live", "myata", 5_000L),
                arrayOf("  nick cave ", "Red Right Hand - Live", "myata", 3_000L),
                arrayOf("Nick Cave", "Red Right Hand " + Char(0x2013) + " Live", "gold", 9_000L),
                arrayOf("Depeche Mode", "Enjoy the Silence", "myata", 7_000L),
            )
        )

        val db = openMigrated()
        try {
            val collection = db.reactionDao().likedTracks().first()
            assertEquals(2, collection.size)

            val cave = collection.single { it.trackKey == TrackKey.of("Nick Cave", "Red Right Hand - Live") }
            // Since when it has been in the Collection: the earliest of the four.
            assertEquals(3_000L, cave.addedAt)
            // Shown with the newest spelling.
            assertEquals("Red Right Hand " + Char(0x2013) + " Live", cave.track)
            assertEquals("gold", cave.stream)
        } finally {
            db.close()
        }
    }

    @Test
    fun a_row_that_cannot_be_keyed_is_still_kept() = runBlocking {
        createV1Database(
            listOf(
                arrayOf("", "Some Title", "myata", 1_000L),
                arrayOf("Depeche Mode", "Enjoy the Silence", "myata", 2_000L),
            )
        )

        val db = openMigrated()
        try {
            val collection = db.reactionDao().likedTracks().first()
            assertEquals(2, collection.size)
            assertTrue(
                collection.any { it.trackKey.startsWith(ReactionMigration.LEGACY_KEY_PREFIX) }
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun the_old_table_is_gone_and_the_new_one_is_what_room_expects() = runBlocking {
        createV1Database(listOf(arrayOf("Depeche Mode", "Enjoy the Silence", "myata", 1_000L)))

        // Opening at all is the schema check: Room validates the migrated database
        // against the v2 schema and throws if it does not match.
        val db = openMigrated()
        try {
            db.reactionDao().likedTracks().first()

            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('favorites', 'track_reaction')"
            ).use { cursor ->
                val tables = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertTrue("track_reaction" in tables)
                assertFalse("favorites" in tables)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun an_empty_collection_migrates_cleanly() = runBlocking {
        createV1Database(emptyList())

        val db = openMigrated()
        try {
            assertEquals(emptyList<Any>(), db.reactionDao().likedTracks().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun reactions_survive_being_closed_and_reopened() = runBlocking {
        createV1Database(listOf(arrayOf("Depeche Mode", "Enjoy the Silence", "myata", 1_000L)))

        val key = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!

        // First run: migrate, then withdraw the Like.
        val first = openMigrated()
        try {
            assertTrue(first.reactionDao().unlike(key))
        } finally {
            first.close()
        }

        // Second run, as after process death: the row is still there, still NEUTRAL,
        // and still out of the Collection.
        val second = openMigrated()
        try {
            val row = second.reactionDao().find(key)
            assertNotNull(row)
            assertEquals(Reaction.NEUTRAL, row!!.reaction)
            assertEquals(emptyList<Any>(), second.reactionDao().likedTracks().first())

            // And liking it again brings it back and persists.
            assertTrue(
                second.reactionDao().like(key, "Depeche Mode", "Enjoy the Silence", "myata", 5_000L)
            )
        } finally {
            second.close()
        }

        val third = openMigrated()
        try {
            assertEquals(1, third.reactionDao().likedTracks().first().size)
            assertEquals(5_000L, third.reactionDao().likedTracks().first().single().addedAt)
        } finally {
            third.close()
        }
    }
}
