package com.example.musicplayerapp.data.lastfm.queue

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrobble queue's schema, pinned against what Room actually exported.
 *
 * A change to [ScrobbleQueueEntry] rewrites the export silently; this makes it a
 * failing test instead. Read as text, like `ReactionOutboxSchemaTest`, so no JSON
 * dependency is needed.
 */
class ScrobbleQueueSchemaTest {

    /** Unit tests run with the module directory as the working directory. */
    private val dir = File("schemas/com.example.musicplayerapp.data.lastfm.queue.LastfmQueueDatabase")

    private fun schema(): String {
        val file = File(dir, "1.json")
        assertTrue("no exported schema at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `version 1 is the only version`() {
        assertEquals(listOf("1.json"), dir.list()!!.sorted())
        assertTrue(schema().contains("\"version\": 1"))
    }

    @Test
    fun `the table is exactly the agreed shape, keyed by the occurrence`() {
        val expected = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (" +
            "`stream_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
            "`track_key` TEXT NOT NULL, `artist` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`duration_sec` INTEGER NOT NULL, `lastfm_username` TEXT NOT NULL, " +
            "`queued_at` INTEGER NOT NULL, `attempts` INTEGER NOT NULL DEFAULT 0, " +
            "`next_attempt_at` INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(`stream_id`, `started_at`))"
        assertTrue("createSql changed:\n${schema()}", schema().contains(expected))
        assertTrue(schema().contains("\"tableName\": \"scrobble_queue\""))
    }

    @Test
    fun `next_attempt_at is indexed for the sender`() {
        assertTrue(
            schema().contains(
                "CREATE INDEX IF NOT EXISTS `index_scrobble_queue_next_attempt_at` " +
                    "ON `\${TABLE_NAME}` (`next_attempt_at`)"
            )
        )
    }

    @Test
    fun `no column could hold a session key, token or signature`() {
        val columns = Regex("\"columnName\": \"([^\"]+)\"").findAll(schema()).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "stream_id", "started_at", "track_key", "artist", "title", "duration_sec",
                "lastfm_username", "queued_at", "attempts", "next_attempt_at",
            ),
            columns,
        )
        for (column in columns) {
            assertFalse(column, Regex("session|token|sig|secret|password|^sk$|_sk$").containsMatchIn(column))
        }
    }

    @Test
    fun `the Collections database is untouched - still version 4, no migration added`() {
        val app = File("schemas/com.example.musicplayerapp.data.AppDatabase")
        assertEquals(listOf("2.json", "3.json", "4.json"), app.list()!!.sorted())
    }
}
