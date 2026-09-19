package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmAuth
import com.example.musicplayerapp.data.lastfm.LastfmBackend
import com.example.musicplayerapp.data.lastfm.LastfmConfig
import com.example.musicplayerapp.data.lastfm.LastfmRequest
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.data.lastfm.queue.LastfmQueueDatabase
import com.example.musicplayerapp.data.lastfm.queue.LastfmScrobbleScheduler
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueue
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueueEntry
import com.example.musicplayerapp.scrobble.OccurrenceId
import com.example.musicplayerapp.scrobble.ScrobbleCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6b's production wiring on a device (G6b P6b): the real triggers, scheduler,
 * worker and sender, over the test WorkManager, an in-memory Room queue and a
 * scripted - or, where nothing may be sent, a failing - transport. The build's own
 * write gate stays closed; tests that expect a write open it for their fake only.
 */
@RunWith(AndroidJUnit4::class)
class LastfmWiringTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager get() = WorkManager.getInstance(context)
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!
    private lateinit var db: LastfmQueueDatabase
    private val api = ScriptedApi()
    private val sessions get() = PrefsLastfmSessionStore(context)
    private val t0 = 1_789_000_000L

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        db = Room.inMemoryDatabaseBuilder(context, LastfmQueueDatabase::class.java).build()
        LastfmQueueDatabase.overrideForInstrumentation(db)
        LastfmBackend.overrideForInstrumentation { api }
        LastfmBackend.overrideRequestsForInstrumentation { LiveLastfm.fakeRequests() }
        sessions.write(LastfmStoredSession(sessionKey = "sk-fabricated", username = "p6b-listener"))
    }

    @After
    fun tearDown() {
        workManager.cancelUniqueWork(LastfmScrobbleScheduler.DRAIN_WORK)
        workManager.cancelUniqueWork(LastfmScrobbleScheduler.RETRY_WORK)
        LastfmQueueDatabase.overrideForInstrumentation(null)
        LastfmConfig.configuredOverrideForTest = null
        LiveLastfm.restoreOffline()                            // also closes the write gate override
        sessions.write(LastfmStoredSession.EMPTY)
        db.close()
    }

    // ---- helpers -------------------------------------------------------------------

    private fun drains(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(LastfmScrobbleScheduler.DRAIN_WORK).get()
    private fun pending() = drains().filter { !it.state.isFinished }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    /** Lets every drain run that is waiting for a network run, until none is left. */
    private fun runAllDrains() {
        await("all drains finished", 20_000) {
            drains().filter { it.state == WorkInfo.State.ENQUEUED }.forEach { driver.setAllConstraintsMet(it.id) }
            drains().all { it.state.isFinished }
        }
    }

    private fun row(startedAt: Long) = ScrobbleQueueEntry(
        "myata", startedAt, TrackKey.of("Artist", "Title $startedAt")!!, "Artist", "Title $startedAt", 200,
        "p6b-listener", 1L,
    )

    private fun candidate(startedAt: Long) = ScrobbleCandidate(
        OccurrenceId("myata", startedAt), TrackKey.of("Artist", "Title $startedAt")!!, "Artist", "Title $startedAt", startedAt, 200,
    )

    // ---- coalescing ------------------------------------------------------------------

    @Test
    fun aRowCommittedWhileADrainWaitsIsLeftToThatDrain() = runBlocking {
        assertTrue(LastfmScrobbleScheduler.requestDrainNow(context))          // offline: waits
        db.scrobbleQueueDao().insert(row(t0))                                // committed after
        assertEquals("the waiting run will read it", false, LastfmScrobbleScheduler.requestDrainNow(context))
        assertEquals(1, pending().size)
        LastfmBackend.overrideWritesForTest(true)
        runAllDrains()
        assertEquals(0, db.scrobbleQueueDao().count())
        assertEquals(1, api.sent.size)
    }

    @Test
    fun aRowCommittedWhileADrainRunsGetsAFollowUpAndIsSentOnce() = runBlocking {
        LastfmBackend.overrideWritesForTest(true)
        db.scrobbleQueueDao().insert(row(t0))
        val gate = CompletableDeferred<Unit>()
        api.holdUntil = gate
        LastfmScrobbleScheduler.requestDrainNow(context)
        driver.setAllConstraintsMet(pending().single().id)
        api.started.await()                                                  // the run has read and is sending
        db.scrobbleQueueDao().insert(row(t0 + 300))
        assertTrue("a running run gets a follow-up", LastfmScrobbleScheduler.requestDrainNow(context))
        assertTrue(drains().any { it.state == WorkInfo.State.BLOCKED })
        api.holdUntil = null
        gate.complete(Unit)
        runAllDrains()
        assertEquals(0, db.scrobbleQueueDao().count())
        assertEquals("each row exactly once", listOf(t0, t0 + 300), api.sent.flatMap { it }.sorted())
    }

    @Test
    fun manyConcurrentRequestsLeaveOnePendingRun() = runBlocking {
        (1..10).map { async(Dispatchers.Default) { LastfmScrobbleScheduler.requestDrainNow(context) } }.awaitAll()
        assertEquals(1, pending().size)
    }

    @Test
    fun hoursOfOfflineListeningDoNotGrowTheChainPerTrack() = runBlocking {
        val queue = ScrobbleQueue(
            dao = { db.scrobbleQueueDao() }, linkedUsername = { "p6b-listener" },
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
            onQueued = { runBlocking { LastfmScrobbleScheduler.requestDrainNow(context) } },
        )
        for (i in 0 until 40) queue.write(candidate(t0 + i * 200L), "p6b-listener")   // offline all along
        assertEquals(40, db.scrobbleQueueDao().count())
        assertEquals("one pending run, not forty", 1, pending().size)
        LastfmBackend.overrideWritesForTest(true)
        runAllDrains()
        assertEquals(0, db.scrobbleQueueDao().count())
        assertEquals(listOf(40), api.sent.map { it.size })                 // one batch, one request
    }

    // ---- the production triggers, end to end ------------------------------------------

    @Test
    fun anEligibleTrackReachesTheFakeLastfmThroughTheProductionPath() = runBlocking {
        LastfmBackend.overrideWritesForTest(true)
        ScrobbleQueue.forContext(context).sink().onEligible(candidate(t0))      // what the service calls
        await("the row and its drain") { runBlocking { db.scrobbleQueueDao().count() } == 1 && pending().isNotEmpty() }
        runAllDrains()
        assertEquals(0, db.scrobbleQueueDao().count())
        val p = api.requests.single().params
        assertEquals("$t0", p["timestamp[0]"])
        assertEquals("0", p["chosenByUser[0]"])
    }

    @Test
    fun withTheGateClosedTheWholePathSendsNothingAndKeepsTheRow() = runBlocking {
        LastfmBackend.overrideForInstrumentation { FailingLastfmApi }           // any request fails the test
        ScrobbleQueue.forContext(context).sink().onEligible(candidate(t0))
        await("the row and its drain") { runBlocking { db.scrobbleQueueDao().count() } == 1 && pending().isNotEmpty() }
        runAllDrains()
        val stored = db.scrobbleQueueDao().find("myata", t0)!!
        assertEquals("untouched", 0, stored.attempts)
        assertEquals(0L, stored.nextAttemptAt)
        assertTrue("no retry loop", workManager.getWorkInfosForUniqueWork(LastfmScrobbleScheduler.RETRY_WORK).get().isEmpty())
    }

    @Test
    fun appStartAsksForADrainOnlyWhenConfiguredAndLinked() = runBlocking {
        LastfmConfig.configuredOverrideForTest = true
        sessions.write(LastfmStoredSession.EMPTY)
        LastfmScrobbleScheduler.onAppStart(context)
        Thread.sleep(500)
        assertTrue("not linked: nothing", drains().isEmpty())
        sessions.write(LastfmStoredSession(sessionKey = "sk-fabricated", username = "p6b-listener"))
        LastfmScrobbleScheduler.onAppStart(context)
        await("a drain after app start") { pending().isNotEmpty() }
    }

    @Test
    fun aSuccessfulLinkAsksForADrainAndAFailedOneDoesNot() = runBlocking {
        LastfmConfig.configuredOverrideForTest = true
        sessions.write(LastfmStoredSession(pendingToken = "tok-p6b", pendingTokenIssuedAt = System.currentTimeMillis()))
        api.authAnswer = """{"error":14,"message":"not yet"}"""
        LastfmAuth.forContext(context).resume()
        Thread.sleep(500)
        assertTrue("still pending: nothing", drains().isEmpty())
        api.authAnswer = """{"session":{"name":"p6b-listener","key":"sk-new","subscriber":0}}"""
        assertEquals(LastfmAuth.Resume.Linked, LastfmAuth.forContext(context).resume())
        await("a drain after linking") { pending().isNotEmpty() }
    }

    /** Accepts every scrobble, answers auth as scripted, can hold a request open. */
    private class ScriptedApi : LastfmApi {
        val requests = mutableListOf<LastfmRequest>()
        val sent = mutableListOf<List<Long>>()
        @Volatile var holdUntil: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        @Volatile var authAnswer = """{"error":14,"message":"not yet"}"""

        override suspend fun send(request: LastfmRequest): LastfmTransportResult {
            if (request.method == "auth.getSession") return LastfmTransportResult.Body(authAnswer)
            synchronized(requests) { requests += request }
            val stamps = (0 until 50).mapNotNull { request.params["timestamp[$it]"]?.toLong() }
            synchronized(sent) { sent += stamps }
            started.complete(Unit)
            holdUntil?.await()
            val items = stamps.joinToString(",") {
                """{"artist":{"#text":"a"},"track":{"#text":"t"},"timestamp":"$it","ignoredMessage":{"code":"0","#text":""}}"""
            }
            return LastfmTransportResult.Body(
                """{"scrobbles":{"scrobble":[$items],"@attr":{"accepted":${stamps.size},"ignored":0}}}"""
            )
        }
    }
}
