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

    private fun schema(version: Int = 1): String {
        val file = File(dir, "$version.json")
        assertTrue("no exported schema at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `versions 1 and 2, and nothing else`() {
        assertEquals(listOf("1.json", "2.json"), dir.list()!!.sorted())
        assertTrue(schema(1).contains("\"version\": 1"))
        assertTrue(schema(2).contains("\"version\": 2"))
    }

    @Test
    fun `version 2 leaves the queue exactly as version 1 had it`() {
        fun queueSql(json: String) = Regex("\"createSql\": \"([^\"]*)\"").findAll(json)
            .map { it.groupValues[1] }
            .filter { "`track_key`" in it || "index_scrobble_queue" in it }
            .toList()
        assertEquals(2, queueSql(schema(1)).size)
        assertEquals(queueSql(schema(1)), queueSql(schema(2)))
    }

    @Test
    fun `scrobble_finalized is one mark per account and stream - and the migration creates exactly it`() {
        val expected = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (" +
            "`lastfm_username` TEXT NOT NULL, `stream_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`lastfm_username`, `stream_id`))"
        assertTrue("createSql changed:\n${schema(2)}", schema(2).contains(expected))
        assertEquals(
            expected.replace("\${TABLE_NAME}", "scrobble_finalized"),
            LastfmQueueDatabase.CREATE_SCROBBLE_FINALIZED,
        )
    }

    @Test
    fun `scrobble_finalized holds no track, no history and nothing secret`() {
        val finalized = schema(2).substringAfter("\"tableName\": \"scrobble_finalized\"")
        val columns = Regex("\"columnName\": \"([^\"]+)\"").findAll(finalized).map { it.groupValues[1] }.toList()
        assertEquals(listOf("lastfm_username", "stream_id", "started_at"), columns)
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
