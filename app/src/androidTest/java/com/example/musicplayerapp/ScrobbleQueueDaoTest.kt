package com.example.musicplayerapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.lastfm.queue.LastfmQueueDatabase
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueueEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scrobble queue on a real SQLite (G6b P5): the `(stream_id, started_at)`
 * primary key is the persistent occurrence dedupe, and it must hold across a close
 * and reopen of the file - the process-death case P4's in-memory record cannot cover.
 */
@RunWith(AndroidJUnit4::class)
class ScrobbleQueueDaoTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "scrobble_queue_dao_test"
    private lateinit var db: LastfmQueueDatabase

    private val t0 = 1_789_000_000L

    private fun entry(
        streamId: String = "myata",
        startedAt: Long = t0,
        artist: String = "Nick Cave & The Bad Seeds",
        title: String = "Red Right Hand",
        duration: Long = 372,
        username: String = "listener-x",
        queuedAt: Long = 1_789_000_200_123L,
    ) = ScrobbleQueueEntry(
        streamId = streamId,
        startedAt = startedAt,
        trackKey = TrackKey.of(artist, title)!!,
        artist = artist,
        title = title,
        durationSec = duration,
        lastfmUsername = username,
        queuedAt = queuedAt,
    )

    @Before
    fun open() {
        context.deleteDatabase(fileName)
        db = LastfmQueueDatabase.build(context, fileName)
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(fileName)
    }

    private val dao get() = db.scrobbleQueueDao()

    @Test
    fun firstInsertIsQueuedExactlyAsGiven() = runBlocking {
        val e = entry(artist = "Björk — Sigur Rós ", title = "  Jóga (Live)", duration = 305)
        assertTrue(dao.insert(e) > 0)
        val stored = dao.find("myata", t0)!!
        assertEquals("artist, title, duration, startedAt and everything else, byte for byte", e, stored)
        assertEquals(0, stored.attempts)
        assertEquals(0L, stored.nextAttemptAt)
    }

    @Test
    fun anExactDuplicateIsIgnored() = runBlocking {
        assertTrue(dao.insert(entry()) > 0)
        assertEquals(-1L, dao.insert(entry()))
        assertEquals(-1L, dao.insert(entry(title = "Corrected Title", queuedAt = 5L)))
        assertEquals(1, dao.count())
        assertEquals("the first row stands", "Red Right Hand", dao.find("myata", t0)!!.title)
    }

    @Test
    fun theSameStartedAtOnAnotherStreamIsAnotherOccurrence() = runBlocking {
        assertTrue(dao.insert(entry(streamId = "myata")) > 0)
        assertTrue(dao.insert(entry(streamId = "gold")) > 0)
        assertTrue(dao.insert(entry(streamId = "myata_hits")) > 0)
        assertEquals(3, dao.count())
    }

    @Test
    fun aNewerStartedAtOnTheSameStreamInserts() = runBlocking {
        assertTrue(dao.insert(entry(startedAt = t0)) > 0)
        assertTrue(dao.insert(entry(startedAt = t0 + 372)) > 0)
        assertEquals(2, dao.count())
    }

    @Test
    fun theSameTrackAiredAgainLaterInserts() = runBlocking {
        val first = entry(startedAt = t0)
        val again = entry(startedAt = t0 + 3_600)
        assertEquals(first.trackKey, again.trackKey)
        assertTrue(dao.insert(first) > 0)
        assertTrue(dao.insert(again) > 0)
        assertEquals(2, dao.count())
    }

    @Test
    fun aDuplicateIsStillRejectedAfterTheFileIsClosedAndReopened() = runBlocking {
        assertTrue(dao.insert(entry()) > 0)
        db.close()                                   // the process dies
        db = LastfmQueueDatabase.build(context, fileName)  // and comes back
        assertEquals(1, dao.count())
        assertEquals(-1L, dao.insert(entry()))
        assertEquals(1, dao.count())
    }

    @Test
    fun pendingForReturnsOnlyThatAccountOldestFirst() = runBlocking {
        dao.insert(entry(streamId = "gold", startedAt = t0 + 200, username = "listener-x"))
        dao.insert(entry(streamId = "myata", startedAt = t0 + 200, username = "listener-x"))
        dao.insert(entry(streamId = "myata", startedAt = t0, username = "listener-x"))
        dao.insert(entry(streamId = "myata", startedAt = t0 + 100, username = "listener-y"))

        val x = dao.pendingFor("listener-x", 50)
        assertEquals(
            listOf("myata" to t0, "gold" to t0 + 200, "myata" to t0 + 200),
            x.map { it.streamId to it.startedAt },
        )
        assertTrue(x.all { it.lastfmUsername == "listener-x" })
        assertEquals(listOf(t0 + 100), dao.pendingFor("listener-y", 50).map { it.startedAt })
        assertEquals("the limit holds", 2, dao.pendingFor("listener-x", 2).size)
        assertTrue("exact match only", dao.pendingFor("LISTENER-X", 50).isEmpty())
    }

    @Test
    fun deleteForUsernameRemovesOnlyThatAccount() = runBlocking {
        dao.insert(entry(startedAt = t0, username = "listener-x"))
        dao.insert(entry(startedAt = t0 + 100, username = "listener-x"))
        dao.insert(entry(startedAt = t0 + 200, username = "listener-y"))
        assertEquals(2, dao.deleteForUsername("listener-x"))
        assertTrue(dao.pendingFor("listener-x", 50).isEmpty())
        assertEquals(1, dao.pendingFor("listener-y", 50).size)
        assertNull(dao.find("myata", t0))
    }
}
