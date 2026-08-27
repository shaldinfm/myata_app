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
import com.example.musicplayerapp.data.SyncProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v3 -> v4 migration against a real v3 database file.
 *
 * One thing matters more than anything else here: **every row that already existed
 * comes out LEGACY.** Those rows were written by a build that delivers through the
 * pre-cutover two-call path, which creates no application marker, and the atomic
 * function on the server deliberately refuses an event it has seen but never marked -
 * because whether that event's state write ever landed is undecidable from the
 * outside. A migrated row that reached the new path would be rejected outright, and
 * a migrated row that was *accepted* there would be worse: it would mean the server
 * had guessed.
 *
 * The mechanism is a SQL default rather than a Kotlin one, and the difference is the
 * point. A Kotlin default would apply to rows this build inserts - which must choose
 * their protocol per track - and would not apply to rows already on disk, which is
 * exactly backwards. The SQL default back-fills the existing rows in one statement
 * and never touches a new one, because Room names every entity column in its
 * generated INSERT.
 */
@RunWith(AndroidJUnit4::class)
class SyncProtocolMigrationTest {

    private val dbName = "protocol_migration_test.db"

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val trackA = "a".repeat(64)
    private val trackB = "b".repeat(64)

    @Before
    fun deleteAnyLeftover() {
        context.deleteDatabase(dbName)
    }

    /**
     * A v3 database exactly as the shipped build leaves one: both tables at the
     * schema Room exported for version 3, with no protocol column anywhere.
     */
    private fun createV3Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `track_reaction` " +
                "(`track_key` TEXT NOT NULL, `artist` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`stream` TEXT NOT NULL, `reaction` TEXT NOT NULL, `liked_at` INTEGER, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`track_key`))"
        )
        db.execSQL(ReactionMigration.CREATE_REACTION_OUTBOX)
        db.execSQL(ReactionMigration.CREATE_REACTION_OUTBOX_INDEX)

        db.execSQL(
            "INSERT INTO track_reaction VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(trackA, "Artist A", "Title A", "myata", "LIKED", 1_000L, 1_000L),
        )
        db.execSQL(
            "INSERT INTO track_reaction VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(trackB, "Artist B", "Title B", "gold", "DISLIKED", null, 2_000L),
        )

        // Two acts the old build was still owing when the listener updated.
        db.execSQL(
            "INSERT INTO reaction_outbox VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "aaaaaaaa-0000-4000-8000-000000000001", trackA, "Artist A", "Title A",
                "myata", ReactionEvent.LIKE.wire, 1_000L, 0, 0,
            ),
        )
        db.execSQL(
            "INSERT INTO reaction_outbox VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "aaaaaaaa-0000-4000-8000-000000000002", trackB, "Artist B", "Title B",
                "gold", ReactionEvent.DISLIKE.wire, 2_000L, 3, 99_000L,
            ),
        )
        db.version = 3
        db.close()
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                ReactionMigration.MIGRATION_1_2,
                ReactionMigration.MIGRATION_2_3,
                ReactionMigration.MIGRATION_3_4,
            )
            .build()

    /** **A.** Every pre-existing outbox row comes out of the migration as LEGACY. */
    @Test
    fun existing_outbox_rows_migrate_to_legacy() = runBlocking {
        createV3Database()
        val db = open()
        try {
            val rows = db.reactionOutboxDao().pending()
            assertEquals(2, rows.size)
            assertTrue(
                "a migrated row must never acquire atomic semantics it was not written with",
                rows.all { it.syncProtocol == SyncProtocol.LEGACY },
            )
        } finally {
            db.close()
        }
    }

    /**
     * The other half of A: a row minted **after** the migration on a track that owes
     * nothing is atomic. The SQL default back-fills the old rows and governs none of
     * the new ones.
     */
    @Test
    fun a_row_minted_after_the_migration_is_atomic() = runBlocking {
        createV3Database()
        val db = open()
        try {
            // trackB owes a legacy delivery; a third, untouched track does not.
            val fresh = "c".repeat(64)
            db.reactionDao().like(fresh, "Artist C", "Title C", "myata", likedAt = 5_000L)

            assertEquals(
                SyncProtocol.ATOMIC_RPC,
                db.reactionOutboxDao().pendingForTrack(fresh).single().syncProtocol,
            )
            assertEquals(
                "and the migrated track still inherits its epoch",
                SyncProtocol.LEGACY,
                db.reactionOutboxDao().pendingForTrack(trackB).single().syncProtocol,
            )
        } finally {
            db.close()
        }
    }

    /** The Collection is not what a protocol migration is for, and it is untouched. */
    @Test
    fun the_collection_survives_unchanged() = runBlocking {
        createV3Database()
        val db = open()
        try {
            val a = db.reactionDao().find(trackA)
            assertNotNull(a)
            assertEquals(Reaction.LIKED, a!!.reaction)
            assertEquals("Artist A", a.artist)
            assertEquals("Title A", a.title)
            assertEquals("myata", a.stream)
            assertEquals(1_000L, a.likedAt)
            assertEquals(1_000L, a.updatedAt)

            val b = db.reactionDao().find(trackB)!!
            assertEquals(Reaction.DISLIKED, b.reaction)
            assertNull(b.likedAt)
            assertEquals(2_000L, b.updatedAt)
        } finally {
            db.close()
        }
    }

    /** `remote_rev` arrives null: this device has never been told a revision. */
    @Test
    fun remote_rev_starts_unknown_rather_than_invented() = runBlocking {
        createV3Database()
        val db = open()
        try {
            assertNull(db.reactionDao().find(trackA)!!.remoteRev)
            assertNull(db.reactionDao().find(trackB)!!.remoteRev)
        } finally {
            db.close()
        }
    }

    /** Backoff state is delivery bookkeeping and survives verbatim. */
    @Test
    fun pending_delivery_state_survives_verbatim() = runBlocking {
        createV3Database()
        val db = open()
        try {
            val parked = db.reactionOutboxDao()
                .find("aaaaaaaa-0000-4000-8000-000000000002")!!
            assertEquals(3, parked.attempts)
            assertEquals(99_000L, parked.nextAttemptAt)
            assertEquals(ReactionEvent.DISLIKE, parked.eventType)
            assertEquals(SyncProtocol.LEGACY, parked.syncProtocol)
        } finally {
            db.close()
        }
    }

    /** An empty v3 database migrates cleanly and Room validates the result. */
    @Test
    fun an_empty_v3_database_migrates_cleanly() = runBlocking {
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `track_reaction` " +
                "(`track_key` TEXT NOT NULL, `artist` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`stream` TEXT NOT NULL, `reaction` TEXT NOT NULL, `liked_at` INTEGER, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`track_key`))"
        )
        raw.execSQL(ReactionMigration.CREATE_REACTION_OUTBOX)
        raw.execSQL(ReactionMigration.CREATE_REACTION_OUTBOX_INDEX)
        raw.version = 3
        raw.close()

        val db = open()
        try {
            assertEquals(0, db.reactionOutboxDao().count())
            assertEquals(0, db.reactionDao().allReactions().size)
        } finally {
            db.close()
        }
    }
}
