package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.lastfm.LastfmAuth
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.data.lastfm.queue.LastfmQueueDatabase
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueue
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueueEntry
import com.example.musicplayerapp.scrobble.OccurrenceId
import com.example.musicplayerapp.scrobble.ScrobbleCandidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The P5 wiring on a device, through the exact objects production uses:
 * `ScrobbleQueue.forContext(...).sink()` is what `MediaPlayerService` hands P4's
 * tracker, and `LastfmAuth.forContext(...)` is what the Last.fm screen's `Отключить`
 * calls. The database is an in-memory `LastfmQueueDatabase` installed through its
 * override; the session is a fabricated one. No request reaches Last.fm - the
 * runner's offline gate is in place, and none of this sends anything anyway.
 */
@RunWith(AndroidJUnit4::class)
class ScrobbleQueueIntegrationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: LastfmQueueDatabase
    private val sessions get() = PrefsLastfmSessionStore(context)
    private val t0 = 1_789_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LastfmQueueDatabase::class.java).build()
        LastfmQueueDatabase.overrideForInstrumentation(db)
        sessions.write(LastfmStoredSession.EMPTY)
    }

    @After
    fun tearDown() {
        LastfmQueueDatabase.overrideForInstrumentation(null)
        sessions.write(LastfmStoredSession.EMPTY)
        LiveLastfm.restoreOffline()
        db.close()
    }

    private fun link(username: String) =
        sessions.write(LastfmStoredSession(sessionKey = "sk-fabricated-for-test", username = username))

    private fun candidate(stream: String = "myata", startedAt: Long = t0) = ScrobbleCandidate(
        occurrenceId = OccurrenceId(stream, startedAt),
        trackKey = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!,
        artist = "Depeche Mode",
        title = "Enjoy the Silence",
        startedAt = startedAt,
        durationSec = 373,
    )

    private fun row(username: String, startedAt: Long) = ScrobbleQueueEntry(
        streamId = "myata", startedAt = startedAt,
        trackKey = TrackKey.of("A", "T")!!, artist = "A", title = "T", durationSec = 200,
        lastfmUsername = username, queuedAt = 1L,
    )

    private suspend fun awaitCount(expected: Int) = withTimeout(10_000) {
        while (db.scrobbleQueueDao().count() != expected) delay(20)
    }

    @Test
    fun theServicesSinkQueuesTheCandidateForTheLinkedAccount() = runBlocking {
        link("p5-listener-x")

        ScrobbleQueue.forContext(context).sink().onEligible(candidate())
        awaitCount(1)

        val stored = db.scrobbleQueueDao().find("myata", t0)!!
        assertEquals("p5-listener-x", stored.lastfmUsername)
        assertEquals("Enjoy the Silence", stored.title)
        assertEquals(373L, stored.durationSec)

        // The same occurrence again - as after a service or process restart - is ignored.
        ScrobbleQueue.forContext(context).sink().onEligible(candidate())
        ScrobbleQueue.forContext(context).write(candidate(), "p5-listener-x")
        assertEquals(1, db.scrobbleQueueDao().count())
    }

    @Test
    fun explicitDisconnectPurgesOnlyThatAccount() = runBlocking {
        val dao = db.scrobbleQueueDao()
        dao.insert(row("p5-listener-x", t0))
        dao.insert(row("p5-listener-x", t0 + 100))
        dao.insert(row("p5-listener-y", t0 + 200))
        link("p5-listener-x")

        LastfmAuth.forContext(context).disconnect()

        assertTrue(dao.pendingFor("p5-listener-x", 50).isEmpty())
        assertEquals(1, dao.pendingFor("p5-listener-y", 50).size)
        assertEquals(LastfmStoredSession.EMPTY, sessions.read())
    }

    @Test
    fun reauthRequiredKeepsTheAccountsRows() = runBlocking {
        val dao = db.scrobbleQueueDao()
        dao.insert(row("p5-listener-x", t0))
        // Error 9 leaves the username and drops the key: re-auth, not a disconnect.
        sessions.write(LastfmStoredSession(username = "p5-listener-x"))

        val auth = LastfmAuth.forContext(context)
        auth.link()
        auth.resume()

        assertEquals("the same account's listens wait for it", 1, dao.pendingFor("p5-listener-x", 50).size)
    }
}
