package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmBackend
import com.example.musicplayerapp.data.lastfm.LastfmRequest
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.data.lastfm.queue.LastfmQueueDatabase
import com.example.musicplayerapp.data.lastfm.queue.LastfmScrobbleScheduler
import com.example.musicplayerapp.data.lastfm.queue.LastfmScrobbleWorker
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueueEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The worker and scheduler on a device, over a real (in-memory) Room queue and a
 * scripted transport (G6b P6a). Nothing here reaches Last.fm: the transport is
 * this test's, the request factory is the runner's fabricated one, and every
 * override is put back to the offline gate afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LastfmScrobbleWorkerTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager get() = WorkManager.getInstance(context)
    private lateinit var db: LastfmQueueDatabase
    private val api = ScriptedApi()
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
        PrefsLastfmSessionStore(context).write(LastfmStoredSession(sessionKey = "sk-fabricated", username = "p6a-listener"))
    }

    @After
    fun tearDown() {
        workManager.cancelUniqueWork(LastfmScrobbleScheduler.DRAIN_WORK)
        workManager.cancelUniqueWork(LastfmScrobbleScheduler.RETRY_WORK)
        LastfmQueueDatabase.overrideForInstrumentation(null)
        LiveLastfm.restoreOffline()
        PrefsLastfmSessionStore(context).write(LastfmStoredSession.EMPTY)
        db.close()
    }

    private fun seed(vararg startedAt: Long) = runBlocking {
        startedAt.forEach {
            db.scrobbleQueueDao().insert(
                ScrobbleQueueEntry(
                    "myata", it, TrackKey.of("Artist", "Title $it")!!, "Artist", "Title $it", 200,
                    "p6a-listener", 1L,
                )
            )
        }
    }

    private fun runWorker(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<LastfmScrobbleWorker>(context).build().doWork() }

    @Test
    fun theWorkerSendsDueRowsOldestFirstAndDeletesTheAccepted() = runBlocking {
        seed(t0 + 300, t0)
        api.acceptAll = true

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val p = api.requests.single().params
        assertEquals("$t0", p["timestamp[0]"])
        assertEquals("${t0 + 300}", p["timestamp[1]"])
        assertEquals("0", p["chosenByUser[0]"])
        assertEquals(0, db.scrobbleQueueDao().count())
    }

    @Test
    fun noAnswerKeepsTheRowsAndSetsTheRetryTimer() = runBlocking {
        seed(t0)
        api.acceptAll = false                                   // unreachable

        runWorker()

        val row = db.scrobbleQueueDao().find("myata", t0)!!
        assertEquals(1, row.attempts)
        assertTrue(row.nextAttemptAt > System.currentTimeMillis())
        val timer = workManager.getWorkInfosForUniqueWork(LastfmScrobbleScheduler.RETRY_WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, timer.state)
        assertEquals(NetworkType.CONNECTED, timer.constraints.requiredNetworkType)
    }

    @Test
    fun requestDrainIsOneTimeNetworkBoundWorkThatDrains() = runBlocking {
        seed(t0)
        api.acceptAll = true

        LastfmScrobbleScheduler.requestDrain(context)
        val info = workManager.getWorkInfosForUniqueWork(LastfmScrobbleScheduler.DRAIN_WORK).get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals("waits for a network", WorkInfo.State.ENQUEUED, info.state)

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)

        // A CoroutineWorker runs on its own dispatcher even with the synchronous
        // executor, so the constraint being met starts the work - it does not finish it.
        val deadline = System.currentTimeMillis() + 10_000
        var state = workManager.getWorkInfoById(info.id).get()!!.state
        while (!state.isFinished && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            state = workManager.getWorkInfoById(info.id).get()!!.state
        }
        assertEquals(WorkInfo.State.SUCCEEDED, state)
        assertEquals(0, db.scrobbleQueueDao().count())
        assertEquals(1, api.requests.size)
    }

    @Test
    fun anotherAccountsRowsAreNeverSent() = runBlocking {
        seed(t0)
        PrefsLastfmSessionStore(context).write(LastfmStoredSession(sessionKey = "sk-other", username = "someone-else"))
        api.acceptAll = true

        runWorker()

        assertTrue(api.requests.isEmpty())
        assertEquals(1, db.scrobbleQueueDao().count())
    }

    /** Accepts every scrobble it is sent, or answers nothing at all. */
    private class ScriptedApi : LastfmApi {
        val requests = mutableListOf<LastfmRequest>()
        @Volatile var acceptAll = true

        override suspend fun send(request: LastfmRequest): LastfmTransportResult {
            synchronized(requests) { requests += request }
            if (!acceptAll) return LastfmTransportResult.Unreachable("IOException")
            val stamps = (0 until 50).mapNotNull { request.params["timestamp[$it]"] }
            val items = stamps.joinToString(",") {
                """{"artist":{"#text":"a"},"track":{"#text":"t"},"timestamp":"$it","ignoredMessage":{"code":"0","#text":""}}"""
            }
            return LastfmTransportResult.Body(
                """{"scrobbles":{"scrobble":[$items],"@attr":{"accepted":${stamps.size},"ignored":0}}}"""
            )
        }
    }
}
