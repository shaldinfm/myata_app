package com.example.musicplayerapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.lastfm.queue.LastfmQueueDatabase
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueueDao.Enqueued
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The finalized mark on real SQLite (G6b P6b): [ScrobbleQueueDao.enqueue] and
 * [ScrobbleQueueDao.finalizeOccurrence] as the Room transactions they are, on a
 * real database file - so API 24's SQLite, which has no upsert, runs the same two
 * statements production does.
 */
@RunWith(AndroidJUnit4::class)
class ScrobbleFinalizationDaoTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "scrobble_finalization_dao_test"
    private lateinit var db: LastfmQueueDatabase
    private val dao get() = db.scrobbleQueueDao()

    private val x = 1_789_000_000L
    private val u = "listener-u"
    private val v = "listener-v"

    private fun entry(startedAt: Long = x, stream: String = "myata", username: String = u) = ScrobbleQueueEntry(
        streamId = stream,
        startedAt = startedAt,
        trackKey = TrackKey.of("Artist", "Title $startedAt")!!,
        artist = "Artist",
        title = "Title $startedAt",
        durationSec = 600,
        lastfmUsername = username,
        queuedAt = 1_789_000_000_000L,
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

    /** C: the row committed first - finalization raises the mark and removes it, together. */
    @Test
    fun enqueuedThenFinalizedLeavesTheMarkAndNoRow() = runBlocking {
        assertEquals(Enqueued.QUEUED, dao.enqueue(entry()))
        assertNull("queuing never finalizes", dao.finalizedThrough(u, "myata"))
        assertEquals(1, dao.finalizeOccurrence(u, "myata", x))
        assertNull(dao.find("myata", x))
        assertEquals(x, dao.finalizedThrough(u, "myata"))
    }

    /** B: finalization committed first - the later enqueue sees it and writes nothing. */
    @Test
    fun finalizedThenEnqueuedIsRefused() = runBlocking {
        dao.finalizeOccurrence(u, "myata", x)
        assertEquals(Enqueued.ALREADY_FINALIZED, dao.enqueue(entry()))
        assertEquals(0, dao.count())
    }

    @Test
    fun aPendingDuplicateIsStillTheQueueKeysJob() = runBlocking {
        assertEquals(Enqueued.QUEUED, dao.enqueue(entry()))
        assertEquals(Enqueued.DUPLICATE, dao.enqueue(entry()))
        assertEquals(1, dao.count())
    }

    /** D: never backwards, on SQLite without upsert. */
    @Test
    fun theMarkOnlyMovesForward() = runBlocking {
        dao.finalizeOccurrence(u, "myata", x + 600)
        dao.finalizeOccurrence(u, "myata", x)
        assertEquals(x + 600, dao.finalizedThrough(u, "myata"))
        dao.finalizeOccurrence(u, "myata", x + 1200)
        assertEquals(x + 1200, dao.finalizedThrough(u, "myata"))
        assertEquals(Enqueued.ALREADY_FINALIZED, dao.enqueue(entry(x + 900)))
    }

    /** E, F, G. */
    @Test
    fun streamsAndAccountsAreIndependentAndNewerAiringsQueue() = runBlocking {
        dao.finalizeOccurrence(u, "myata", x)
        assertEquals("E: another stream, same second", Enqueued.QUEUED, dao.enqueue(entry(stream = "gold")))
        assertEquals("F: another account, same airing", Enqueued.QUEUED, dao.enqueue(entry(username = v)))
        assertEquals("G: a newer airing", Enqueued.QUEUED, dao.enqueue(entry(x + 600)))
        assertNull(dao.finalizedThrough(u, "gold"))
        assertNull(dao.finalizedThrough(v, "myata"))
    }

    @Test
    fun finalizingRemovesOnlyThatAccountsRow() = runBlocking {
        dao.enqueue(entry(username = v))
        assertEquals(0, dao.finalizeOccurrence(u, "myata", x))
        assertEquals(v, dao.find("myata", x)!!.lastfmUsername)
    }

    @Test
    fun anExplicitDisconnectPurgesPendingRowsAndKeepsMarks() = runBlocking {
        dao.enqueue(entry(x))
        dao.finalizeOccurrence(u, "myata", x)
        dao.enqueue(entry(x + 600))                                 // pending
        assertEquals(1, dao.deleteForUsername(u))
        assertEquals(x, dao.finalizedThrough(u, "myata"))
        assertEquals("purged pending: may be earned again", Enqueued.QUEUED, dao.enqueue(entry(x + 600)))
        assertEquals("finalized: never again", Enqueued.ALREADY_FINALIZED, dao.enqueue(entry(x)))
    }

    /**
     * A + D under real concurrency: every airing is enqueued and finalized at the
     * same time, in both orders, from many threads. However each pair interleaves,
     * no row may survive its finalization, and the mark ends at the newest airing.
     */
    @Test
    fun racingEnqueueAndFinalizeNeverLeaveARowBehind() = runBlocking {
        val airings = (0 until 200).map { x + it * 600L }
        val outcomes = airings.shuffled().flatMap { t ->
            listOf(
                async(Dispatchers.IO) { dao.enqueue(entry(t)) },
                async(Dispatchers.IO) { dao.finalizeOccurrence(u, "myata", t); null },
            )
        }.awaitAll()
        assertEquals("no row survived its finalization", 0, dao.count())
        assertEquals(airings.last(), dao.finalizedThrough(u, "myata"))
        val enqueued = outcomes.filterIsInstance<Enqueued>()
        assertEquals(200, enqueued.size)
        assertTrue(enqueued.all { it == Enqueued.QUEUED || it == Enqueued.ALREADY_FINALIZED })
        // Everything is finalized now: nothing older than the mark can come back.
        for (t in airings) assertEquals(Enqueued.ALREADY_FINALIZED, dao.enqueue(entry(t)))
    }
}
