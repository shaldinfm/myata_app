package com.example.musicplayerapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.lastfm.queue.LastfmQueueDatabase
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueueDao.Enqueued
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueueEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `lastfm_queue` 1 -> 2 against a real v1 file (G6b P6b).
 *
 * The v1 table and index are created with the exact SQL Room exported in `1.json`
 * and filled the way a phone would have them, then the production builder opens it
 * - running [LastfmQueueDatabase.MIGRATION_1_2] and Room's own validation, which
 * throws if the result is not the v2 schema.
 *
 * What is protected: every pending row survives byte for byte, and the new
 * `scrobble_finalized` starts **empty**. A v1 row is pending, not finalized; back-
 * filling a mark from it would refuse the airing as already scrobbled while it has
 * never been sent.
 */
@RunWith(AndroidJUnit4::class)
class LastfmQueueMigrationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "lastfm_queue_migration_test"

    /** Exactly what Room exported for v1 `scrobble_queue` and its index. */
    private val createQueueV1 =
        "CREATE TABLE IF NOT EXISTS `scrobble_queue` (`stream_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
            "`track_key` TEXT NOT NULL, `artist` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`duration_sec` INTEGER NOT NULL, `lastfm_username` TEXT NOT NULL, `queued_at` INTEGER NOT NULL, " +
            "`attempts` INTEGER NOT NULL DEFAULT 0, `next_attempt_at` INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(`stream_id`, `started_at`))"
    private val createQueueIndexV1 =
        "CREATE INDEX IF NOT EXISTS `index_scrobble_queue_next_attempt_at` ON `scrobble_queue` (`next_attempt_at`)"

    private val rows = listOf(
        ScrobbleQueueEntry("myata", 1_789_000_000L, "k-a", "Björk", "Jóga", 305, "listener-u", 1_789_000_200_000L),
        ScrobbleQueueEntry("gold", 1_789_000_300L, "k-b", "Nick Cave", "Red Right Hand", 372, "listener-u", 1_789_000_400_000L, 2, 1_789_000_900_000L),
        ScrobbleQueueEntry("myata", 1_789_000_600L, "k-c", "Artist", "Title", 200, "listener-v", 1_789_000_700_000L, 1, Long.MAX_VALUE),
    )

    @Before
    fun deleteAnyLeftover() {
        context.deleteDatabase(fileName)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(fileName)
    }

    private fun createV1() {
        val file = context.getDatabasePath(fileName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(createQueueV1)
        db.execSQL(createQueueIndexV1)
        for (r in rows) {
            db.execSQL(
                "INSERT INTO `scrobble_queue` VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    r.streamId, r.startedAt, r.trackKey, r.artist, r.title, r.durationSec,
                    r.lastfmUsername, r.queuedAt, r.attempts, r.nextAttemptAt,
                ),
            )
        }
        db.version = 1
        db.close()
    }

    @Test
    fun pendingRowsSurviveAndTheFinalizedTableStartsEmpty() = runBlocking {
        createV1()
        val db = LastfmQueueDatabase.build(context, fileName)
        try {
            val dao = db.scrobbleQueueDao()
            // Every row, every column, exactly as it was.
            for (r in rows) assertEquals(r, dao.find(r.streamId, r.startedAt))
            assertEquals(rows.size, dao.count())

            val sql = db.openHelper.writableDatabase
            sql.query("SELECT COUNT(*) FROM scrobble_finalized").use { c ->
                c.moveToFirst()
                assertEquals("no back-fill from pending rows", 0, c.getInt(0))
            }
            // The key is (lastfm_username, stream_id), in that order.
            val pk = sortedMapOf<Int, String>()
            sql.query("PRAGMA table_info(`scrobble_finalized`)").use { c ->
                while (c.moveToNext()) {
                    val position = c.getInt(c.getColumnIndexOrThrow("pk"))
                    if (position > 0) pk[position] = c.getString(c.getColumnIndexOrThrow("name"))
                }
            }
            assertEquals(listOf("lastfm_username", "stream_id"), pk.values.toList())

            // A migrated pending row is pending: its key still refuses a duplicate,
            // and nothing about it reads as finalized.
            for (r in rows) assertNull(dao.finalizedThrough(r.lastfmUsername, r.streamId))
            assertEquals(Enqueued.DUPLICATE, dao.enqueue(rows[0]))
        } finally {
            db.close()
        }
    }

    @Test
    fun anEmptyV1MigratesToo() = runBlocking {
        createV1()
        context.getDatabasePath(fileName).let { file ->
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM scrobble_queue") }
        }
        val db = LastfmQueueDatabase.build(context, fileName)
        try {
            assertEquals(0, db.scrobbleQueueDao().count())
            assertEquals(Enqueued.QUEUED, db.scrobbleQueueDao().enqueue(rows[0]))
        } finally {
            db.close()
        }
    }
}
