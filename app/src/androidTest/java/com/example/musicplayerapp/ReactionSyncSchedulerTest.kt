package com.example.musicplayerapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.ReactionSyncScheduler
import com.example.musicplayerapp.data.supabase.ReactionSyncWorker
import com.example.musicplayerapp.data.supabase.SupabaseConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scheduling half: that a committed reaction cannot lose its wake-up.
 *
 * The two races this is about are written out in [ReactionSyncScheduler]'s KDoc.
 * What is asserted here is the observable consequence of the policy choice, because
 * that is the part a future edit could silently undo: someone "tidies"
 * `APPEND_OR_REPLACE` into `KEEP`, everything still builds, every other test still
 * passes, and reactions tapped during a sync quietly stop being sent.
 *
 * The work is kept from running by leaving its network constraint unmet, which is
 * the test harness's default. That is also what makes "a request arriving while an
 * earlier one is unfinished" reproducible without racing a real worker.
 */
@RunWith(AndroidJUnit4::class)
class ReactionSyncSchedulerTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    @Before
    fun initialiseTestWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build(),
        )
        workManager.cancelUniqueWork(ReactionSyncScheduler.UNIQUE_WORK)
        clearOutbox()
    }

    @After
    fun tidy() {
        workManager.cancelUniqueWork(ReactionSyncScheduler.UNIQUE_WORK)
        clearOutbox()
    }

    private fun clearOutbox() = runBlocking {
        val outbox = AppDatabase.getDatabase(context).reactionOutboxDao()
        outbox.pending().forEach { outbox.delete(it.eventId) }
    }

    private fun infos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(ReactionSyncScheduler.UNIQUE_WORK).get()

    // ==================== race B: a reaction during a running drain ====================

    @Test
    fun a_second_request_is_appended_rather_than_dropped() {
        assumeTrue("no supabase project configured in this build", SupabaseConfig.isConfigured)

        ReactionSyncScheduler.onReactionCommitted(context)
        assertEquals(1, infos().size)

        // The reaction tapped while the first drain is unfinished. Under KEEP this
        // request would be silently discarded and the row would wait for the next
        // app start; under APPEND_OR_REPLACE it is queued behind the first.
        ReactionSyncScheduler.onReactionCommitted(context)
        ReactionSyncScheduler.onReactionCommitted(context)

        val all = infos()
        assertEquals("each request must survive, not be dropped by KEEP", 3, all.size)
        assertTrue(all.all { ReactionSyncScheduler.UNIQUE_WORK in it.tags })
    }

    @Test
    fun the_chain_is_never_left_only_blocked() {
        assumeTrue(SupabaseConfig.isConfigured)

        ReactionSyncScheduler.onReactionCommitted(context)
        ReactionSyncScheduler.onReactionCommitted(context)

        val states = infos().map { it.state }
        // Exactly one head that can run as soon as the network arrives; the rest
        // wait behind it rather than competing with it.
        assertEquals(1, states.count { it == WorkInfo.State.ENQUEUED })
        assertTrue(states.drop(1).all { it == WorkInfo.State.BLOCKED || it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun the_policy_in_use_is_the_one_that_closes_the_race() {
        // Guards the decision itself. KEEP and REPLACE both lose - see the table in
        // ReactionSyncScheduler - and this is the line that would be edited.
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, ReactionSyncScheduler.POLICY)
    }

    // ==================== race A: startup recovery ====================

    @Test
    fun an_empty_outbox_schedules_nothing_at_startup() {
        assumeTrue(SupabaseConfig.isConfigured)

        ReactionSyncScheduler.onAppStart(context)
        settle()

        // A listener who has never reacted causes no work request at all - which is
        // the outermost of the three gates that keep them out of auth.users.
        assertEquals(0, infos().size)
    }

    @Test
    fun rows_that_survived_a_previous_process_are_scheduled_at_startup() {
        assumeTrue(SupabaseConfig.isConfigured)

        // Exactly the state a kill between "transaction committed" and "work
        // enqueued" leaves behind: a pending row and no scheduled drain.
        runBlocking {
            val db = AppDatabase.getDatabase(context)
            val key = TrackKey.of("ZZ Startup Recovery", "ZZ Startup Recovery")!!
            db.reactionDao().like(key, "ZZ Startup Recovery", "ZZ Startup Recovery", "myata", 1L, 1L)
        }
        assertEquals(0, infos().size)

        ReactionSyncScheduler.onAppStart(context)
        settle()

        assertEquals(1, infos().size)
    }

    // ==================== the worker's own gate ====================

    @Test
    fun the_worker_succeeds_and_does_nothing_on_an_empty_outbox() = runBlocking {
        val worker = TestListenableWorkerBuilder<ReactionSyncWorker>(context).build()

        // No network is needed and none is used: the drain returns Idle after one
        // COUNT, before it ever reaches the identity boundary.
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
    }

    /** [ReactionSyncScheduler.onAppStart] answers on an IO coroutine; give it a moment. */
    private fun settle(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && infos().isEmpty()) {
            Thread.sleep(50)
        }
        Thread.sleep(250)
    }
}
