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
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ReactionSyncScheduler
import com.example.musicplayerapp.data.supabase.ReactionSyncWorker
import com.example.musicplayerapp.data.supabase.SupabaseConfig
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 *
 * **Nothing here reaches the live project.** `MyataTestRunner` has replaced the
 * network boundary for the whole process, so the runs below exercise the real config
 * gate, the real database, the real [com.example.musicplayerapp.data.supabase.ReactionSyncEngine]
 * and the real rescheduling - everything except the two calls that would leave the
 * device. The assertions are unchanged; only the far side of the socket is.
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
        workManager.cancelUniqueWork(ReactionSyncScheduler.RETRY_WORK)
        clearOutbox()
    }

    @After
    fun tidy() {
        workManager.cancelUniqueWork(ReactionSyncScheduler.UNIQUE_WORK)
        workManager.cancelUniqueWork(ReactionSyncScheduler.RETRY_WORK)
        clearOutbox()
        // These tests share the app's real preferences, so a signed-out state left
        // behind would silently disable scheduling for every test after it.
        IdentityStore.clearForTest(context)
    }

    private fun clearOutbox() = runBlocking {
        val outbox = AppDatabase.getDatabase(context).reactionOutboxDao()
        outbox.pending().forEach { outbox.delete(it.eventId) }
    }

    private fun infos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(ReactionSyncScheduler.UNIQUE_WORK).get()

    /** The timer chain: what a parked row leaves behind. */
    private fun timers(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(ReactionSyncScheduler.RETRY_WORK).get()

    private val testDriver
        get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    /**
     * Puts the outbox in exactly the state a permanent-looking 4xx leaves it in: one
     * row, counted against, and not eligible again until [at].
     */
    private fun parkOneRow(at: Long) = runBlocking {
        val db = AppDatabase.getDatabase(context)
        // A fresh track each time. These tests share the app's real database, and
        // liking a track that is already LIKED is correctly a no-op that writes no
        // outbox row - so reusing one key makes every test after the first silently
        // set up nothing.
        val name = markedTrack()
        val key = TrackKey.of(name, name)!!
        db.reactionDao().like(key, name, name, "myata", 1L, 1L)
        val row = db.reactionOutboxDao().pending().single()
        db.reactionOutboxDao().recordFailedAttempt(row.eventId, at)
    }

    /**
     * A distinct, obviously-fake track name, marked so cleanup can find it.
     *
     * `ZZ_SCHED_FIXTURE`, with the underscore the rest of the project uses. The old
     * spelling was `ZZ Sync Fixture`, with spaces, which the documented cleanup
     * predicate `artist like 'ZZ\_%'` does not match - so rows that escaped to the
     * live project were invisible to every cleanup pass aimed at them. These rows can
     * no longer escape at all (see LiveSupabase), and the name now matches the
     * predicate anyway, because two independent reasons to not leak is the right
     * number.
     */
    private fun markedTrack(): String = "ZZ_SCHED_FIXTURE ${System.nanoTime()}"

    /** A uid for the paused-state tests. Never signed in anywhere; storage only. */
    private val PAUSED_UID = "00000000-0000-4000-8000-00000000cafe"

    private fun runWorkerOnce() = runBlocking {
        TestListenableWorkerBuilder<ReactionSyncWorker>(context).build().doWork()
    }

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
            val name = markedTrack()
            val key = TrackKey.of(name, name)!!
            db.reactionDao().like(key, name, name, "myata", 1L, 1L)
        }
        assertEquals(0, infos().size)

        ReactionSyncScheduler.onAppStart(context)
        settle()

        assertEquals(1, infos().size)
    }

    // ==================== the parked-row wake-up ====================

    @Test
    fun a_parked_row_leaves_a_timer_behind() {
        assumeTrue(SupabaseConfig.isConfigured)

        // An hour out, which is what a permanent-looking 4xx earns on its first
        // attempt. Nothing is due, so this run needs no network and no identity.
        val parkedUntil = System.currentTimeMillis() + 3_600_000L
        parkOneRow(parkedUntil)

        assertEquals(androidx.work.ListenableWorker.Result.success(), runWorkerOnce())

        // The mechanism the whole gate is about. APPEND_OR_REPLACE closes the two
        // commit races but it is not a timer: once a chain finishes it schedules
        // nothing, so without this the row would wait for the listener to react
        // again or restart the app - up to a day away, or forever.
        val timer = timers().single()
        assertEquals(WorkInfo.State.ENQUEUED, timer.state)
        assertTrue(ReactionSyncScheduler.RETRY_WORK in timer.tags)
    }

    @Test
    fun the_timer_is_set_for_the_moment_the_row_becomes_eligible() {
        assumeTrue(SupabaseConfig.isConfigured)

        val parkedUntil = System.currentTimeMillis() + 3_600_000L
        parkOneRow(parkedUntil)
        runWorkerOnce()

        // Not "some time later" - the row's own next_attempt_at, carried out of the
        // drain and turned into an initial delay.
        val scheduledFor = timers().single().nextScheduleTimeMillis
        assertTrue(
            "scheduled for $scheduledFor, row is eligible at $parkedUntil",
            kotlin.math.abs(scheduledFor - parkedUntil) < 30_000L,
        )
    }

    @Test
    fun the_timer_fires_and_runs_the_worker_with_no_restart_and_no_new_reaction() {
        assumeTrue(SupabaseConfig.isConfigured)

        // The timer a parked row leaves behind, on its own. The outbox is empty here
        // deliberately: this test is about the wake-up reaching the worker at all,
        // and an empty outbox keeps the run off the network and out of the
        // self-rescheduling that would muddy what is being observed. That the row is
        // then actually retried is ReactionSyncEngineTest's
        // `a_parked_row_is_retried_when_its_moment_arrives`.
        ReactionSyncScheduler.scheduleWakeUp(context, System.currentTimeMillis() + 3_600_000L)
        val timer = timers().single()
        assertEquals(WorkInfo.State.ENQUEUED, timer.state)

        // Nothing else happens: no app restart, no reaction, no other enqueue. Only
        // the delay elapsing, which is what the test driver simulates.
        testDriver.setAllConstraintsMet(timer.id)
        testDriver.setInitialDelayMet(timer.id)

        // It ran. Without this link a parked row has nothing watching a clock for it.
        //
        // Awaited rather than read. Making the work eligible hands it to WorkManager's
        // executor and returns; reading the state on the next line was a race the test
        // usually won, and the failure it lost with was `expected:<SUCCEEDED> but
        // was:<RUNNING>` - the worker observed mid-flight, not a scheduling bug.
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(timer.id).state)
    }

    /**
     * Blocks until [id] reaches a terminal state, or fails with what it was still
     * doing when the time ran out.
     *
     * Driven by `getWorkInfoByIdFlow`, so it returns on the transition itself rather
     * than on a guessed interval: there is no sleep here to be too short on a loaded
     * emulator or wasted time on an idle one. The timeout is a failure condition, not
     * a wait - work that has not finished in ten seconds is a real defect, and saying
     * which state it was stuck in is the useful half of the report.
     */
    private fun awaitFinished(id: UUID, timeoutMs: Long = 10_000): WorkInfo = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            workManager.getWorkInfoByIdFlow(id).filterNotNull().first { it.state.isFinished }
        } ?: throw AssertionError(
            "work $id did not finish within ${timeoutMs}ms; " +
                "last observed state was ${workManager.getWorkInfoById(id).get()?.state}"
        )
    }

    @Test
    fun an_empty_outbox_leaves_no_timer() {
        assumeTrue(SupabaseConfig.isConfigured)

        assertEquals(androidx.work.ListenableWorker.Result.success(), runWorkerOnce())

        // Nothing owed, so no pointless timer for a listener who has never reacted.
        assertEquals(0, timers().size)
    }

    @Test
    fun a_newer_timer_replaces_the_pending_one_rather_than_queueing() {
        assumeTrue(SupabaseConfig.isConfigured)

        val now = System.currentTimeMillis()
        ReactionSyncScheduler.scheduleWakeUp(context, now + 3_600_000L)
        ReactionSyncScheduler.scheduleWakeUp(context, now + 60_000L)
        ReactionSyncScheduler.scheduleWakeUp(context, now + 120_000L)

        // There is only ever one meaningful "next wake-up". Appending would build a
        // queue of stale timers, each waking the device for nothing.
        assertEquals(1, timers().size)
        assertEquals(ExistingWorkPolicy.REPLACE, ReactionSyncScheduler.RETRY_POLICY)
    }

    @Test
    fun the_timer_is_not_on_the_main_chain() {
        assumeTrue(SupabaseConfig.isConfigured)

        // A day-long delay, as a row parked by a repeated 4xx would earn.
        ReactionSyncScheduler.scheduleWakeUp(context, System.currentTimeMillis() + 86_400_000L)
        assertEquals(0, infos().size)

        // A reaction tapped now must not be queued behind that. If the timer lived on
        // the main chain, this request would be appended after a request that will
        // not run for a day.
        ReactionSyncScheduler.onReactionCommitted(context)

        assertEquals(1, infos().size)
        assertEquals(WorkInfo.State.ENQUEUED, infos().single().state)
        assertNotEquals(ReactionSyncScheduler.UNIQUE_WORK, ReactionSyncScheduler.RETRY_WORK)
    }

    // ==================== signed out: paused, not broken ====================

    @Test
    fun a_signed_out_listener_schedules_no_drain_when_reacting() {
        assumeTrue(SupabaseConfig.isConfigured)
        IdentityStore.adoptAnonymous(context, PAUSED_UID)
        IdentityStore.signOut(context)

        ReactionSyncScheduler.onReactionCommitted(context)
        ReactionSyncScheduler.onReactionCommitted(context)

        // Each of those would otherwise be a constrained, network-waiting request that
        // wakes the device to discover it may do nothing. The reaction itself is
        // already committed to Room by this point - only the wake-up is withheld.
        assertEquals(0, infos().size)
    }

    @Test
    fun a_signed_out_listener_schedules_nothing_at_startup_either() {
        assumeTrue(SupabaseConfig.isConfigured)
        runBlocking {
            val db = AppDatabase.getDatabase(context)
            val name = markedTrack()
            db.reactionDao().like(TrackKey.of(name, name)!!, name, name, "myata", 1L, 1L)
        }
        IdentityStore.adoptAnonymous(context, PAUSED_UID)
        IdentityStore.signOut(context)

        ReactionSyncScheduler.onAppStart(context)
        settle()

        // A pending row exists and is deliberately left pending: it is waiting for a
        // sign-in, not for a network.
        assertTrue(runBlocking { AppDatabase.getDatabase(context).reactionOutboxDao().count() } > 0)
        assertEquals(0, infos().size)
    }

    @Test
    fun a_signed_out_worker_succeeds_and_mutates_no_row() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val name = markedTrack()
        db.reactionDao().like(TrackKey.of(name, name)!!, name, name, "myata", 1L, 1L)
        val before = db.reactionOutboxDao().pending().single()

        IdentityStore.adoptAnonymous(context, PAUSED_UID)
        IdentityStore.signOut(context)

        // success(), not retry(). A retry would put this on a backoff schedule that
        // can never accomplish anything until the listener signs in - and the sign-in
        // is what schedules the drain.
        assertEquals(androidx.work.ListenableWorker.Result.success(), runWorkerOnce())

        val after = db.reactionOutboxDao().pending().single()
        assertEquals(before.eventId, after.eventId)
        assertEquals(before.attempts, after.attempts)
        assertEquals(before.nextAttemptAt, after.nextAttemptAt)
        assertEquals(0, timers().size)
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
