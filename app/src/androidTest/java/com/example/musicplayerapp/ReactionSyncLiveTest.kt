package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.AnonymousSession
import com.example.musicplayerapp.data.supabase.DrainResult
import com.example.musicplayerapp.data.supabase.ReactionSyncEngine
import com.example.musicplayerapp.data.supabase.SupabaseConfig
import com.example.musicplayerapp.data.supabase.SupabaseModule
import com.example.musicplayerapp.data.supabase.SupabaseReactionSyncApi
import io.github.jan.supabase.postgrest.postgrest
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole path against the real project: Room -> outbox -> PostgREST -> back.
 *
 * **Opt-in.** This suite writes to the real project, so it runs only when the run
 * asked for it:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest  *   -Pandroid.testInstrumentationRunnerArguments.liveSupabase=true  *   "-Pandroid.testInstrumentationRunnerArguments.class=com.example.musicplayerapp.ReactionSyncLiveTest"
 * ```
 *
 * Without that flag every test here skips, and `MyataTestRunner` has additionally
 * replaced the network boundary process-wide, so there is nothing left to reach the
 * project even by accident. A configured `supabase.properties` is still required on
 * top of the flag - it is skipped rather than passed when absent, which is the
 * pattern the rest of this suite uses: a test that cannot ask its question must not
 * answer it.
 *
 * ## Test data
 *
 * Everything written here is marked [MARKER] in `artist` and `title`, so it can be
 * found and removed with one predicate. That matters more than usual because of an
 * asymmetry in the schema, which is deliberate and which this suite has to live
 * with: `reactions` rows the client can delete, and it does, in [tidy] below.
 * **`reaction_events` rows it cannot** - there is no DELETE policy for any client
 * role, because history a client can edit is not history. So this suite permanently
 * adds rows to `reaction_events`, and removing them needs owner-side SQL. That SQL
 * is in `docs/SUPABASE-SYNC.md` and in the PR body.
 *
 * The track keys are real TrackKey v1 hashes of obviously fake artist/title pairs,
 * so they satisfy the schema's CHECK and can never collide with a real track.
 */
@RunWith(AndroidJUnit4::class)
class ReactionSyncLiveTest {

    private companion object {
        /** Every row this suite writes carries this in artist and title. */
        const val MARKER = "ZZ_SYNC_TEST"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var listener: String
    private val touched = mutableSetOf<String>()

    private fun keyFor(case: String): String {
        val key = TrackKey.of("$MARKER $case", "$MARKER $case")!!
        touched += key
        return key
    }

    @Before
    fun requireAnOptInAProjectAndAnIdentity() {
        // First, and before anything that could touch the network. A configured
        // project is not consent to write to it: this suite runs only when the run
        // said so on the command line. See LiveSupabase.
        LiveSupabase.assumeOptedIn()
        assumeTrue("no supabase.properties in this build", SupabaseConfig.isConfigured)

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        // The real identity boundary, and the real session this install holds.
        listener = runBlocking { AnonymousSession.ensureAuthenticatedListener(context) }
            ?: run {
                assumeTrue("could not obtain a listener identity (offline?)", false)
                error("unreachable")
            }
    }

    @After
    fun tidy() {
        if (!::db.isInitialized) return
        runBlocking {
            // Current-state rows are the client's own and can be removed. History
            // rows cannot - see the class KDoc.
            touched.forEach { key ->
                runCatching {
                    SupabaseModule.client(context)!!.postgrest.from("reactions").delete {
                        filter { eq("listener_id", listener); eq("track_key", key) }
                    }
                }
            }
        }
        db.close()
    }

    private fun engine(api: SupabaseReactionSyncApi = SupabaseReactionSyncApi(context)) =
        ReactionSyncEngine(db.reactionDao(), db.reactionOutboxDao(), api, { listener })

    private suspend fun remoteState(key: String): JsonObject? =
        SupabaseModule.client(context)!!.postgrest.from("reactions").select {
            filter { eq("listener_id", listener); eq("track_key", key) }
        }.decodeList<JsonObject>().firstOrNull()

    private suspend fun remoteHistory(key: String): List<String> =
        SupabaseModule.client(context)!!.postgrest.from("reaction_events").select {
            filter { eq("listener_id", listener); eq("track_key", key) }
            order("occurred_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
        }.decodeList<JsonObject>().map { it["event_type"]!!.jsonPrimitive.content }

    private fun reactionOf(row: JsonObject?): String? =
        row?.get("reaction")?.jsonPrimitive?.content

    /**
     * A row's `updated_at` as epoch millis.
     *
     * PostgREST renders `timestamptz` with an explicit offset - `+00:00`, not `Z` -
     * so this parses as an [OffsetDateTime] rather than an [java.time.Instant].
     * `Instant.parse` wants the `Z` form and would throw on what the server actually
     * sends. (The *outbound* direction is the opposite problem, and is why
     * `ReactionSyncWire.timestamp` renders `Z`: a `+` in a query-string filter
     * decodes as a space.)
     */
    private fun remoteUpdatedAt(row: JsonObject?): Long? =
        row?.get("updated_at")?.jsonPrimitive?.content
            ?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }

    // ==================== 1-4: the four simple transitions ====================

    @Test
    fun a_like_reaches_history_once_and_sets_current_state_liked() = runBlocking {
        val key = keyFor("LIKE")
        val now = System.currentTimeMillis()
        db.reactionDao().like(key, "$MARKER LIKE", "$MARKER LIKE", "myata", now, now)

        assertTrue(engine().drain() is DrainResult.Drained)

        assertEquals(listOf("LIKE"), remoteHistory(key))
        assertEquals("LIKED", reactionOf(remoteState(key)))
        assertEquals(0, db.reactionOutboxDao().count())
    }

    @Test
    fun an_unlike_adds_its_event_and_leaves_a_neutral_state_row() = runBlocking {
        val key = keyFor("UNLIKE")
        val now = System.currentTimeMillis()
        db.reactionDao().like(key, "$MARKER UNLIKE", "$MARKER UNLIKE", "myata", now, now)
        engine().drain()
        assertEquals("LIKED", reactionOf(remoteState(key)))

        db.reactionDao().unlike(key, now + 1_000)
        engine().drain()

        assertEquals(listOf("LIKE", "UNLIKE"), remoteHistory(key))
        // The row stays, saying NEUTRAL. Migration 0002 admits the value, and the
        // point of storing it is the updated_at that comes with it: an absent row
        // cannot lose a last-writer-wins comparison, because it has nothing to
        // compare. The aggregate hides all-NEUTRAL tracks instead.
        val state = remoteState(key)
        assertNotNull("the reactions row must survive an UNLIKE", state)
        assertEquals("NEUTRAL", reactionOf(state))
    }

    @Test
    fun a_dislike_sets_current_state_disliked() = runBlocking {
        val key = keyFor("DISLIKE")
        db.reactionDao().dislike(key, "$MARKER DISLIKE", "$MARKER DISLIKE", "myata", System.currentTimeMillis())

        engine().drain()

        assertEquals(listOf("DISLIKE"), remoteHistory(key))
        assertEquals("DISLIKED", reactionOf(remoteState(key)))
    }

    @Test
    fun an_undislike_leaves_a_neutral_state_row() = runBlocking {
        val key = keyFor("UNDISLIKE")
        val now = System.currentTimeMillis()
        db.reactionDao().dislike(key, "$MARKER UNDISLIKE", "$MARKER UNDISLIKE", "myata", now)
        engine().drain()
        db.reactionDao().undislike(key, now + 1_000)
        engine().drain()

        assertEquals(listOf("DISLIKE", "UNDISLIKE"), remoteHistory(key))
        assertEquals("NEUTRAL", reactionOf(remoteState(key)))
    }

    // ==================== NEUTRAL is a state, not a gap ====================

    @Test
    fun a_neutral_row_carries_the_time_the_listener_changed_their_mind() = runBlocking {
        val key = keyFor("NEUTRAL_TS")
        val liked = System.currentTimeMillis()
        val withdrawn = liked + 5_000

        db.reactionDao().like(key, "$MARKER NEUTRAL_TS", "$MARKER NEUTRAL_TS", "myata", liked, liked)
        engine().drain()
        db.reactionDao().unlike(key, withdrawn)
        engine().drain()

        // This is the whole reason NEUTRAL is stored rather than implied. The remote
        // row's updated_at is the moment of the *withdrawal*, so a second device
        // holding a stale LIKED can be told it is out of date.
        val row = remoteState(key)
        assertEquals("NEUTRAL", reactionOf(row))
        assertEquals(withdrawn, remoteUpdatedAt(row)!!)
    }

    @Test
    fun a_stale_unlike_cannot_overwrite_a_newer_like() = runBlocking {
        val key = keyFor("LWW_NEUTRAL")
        val now = System.currentTimeMillis()

        // Remote already holds a *newer* LIKED than the withdrawal we are about to
        // deliver. Under the old model this was untestable: a delete had no
        // updated_at to lose with, so a late UNLIKE removed the row regardless.
        db.reactionDao().like(key, "$MARKER LWW_NEUTRAL", "$MARKER LWW_NEUTRAL", "myata", now + 60_000, now + 60_000)
        engine().drain()
        assertEquals("LIKED", reactionOf(remoteState(key)))

        // Now a withdrawal stamped an hour in the past arrives.
        db.reactionDao().unlike(key, now - 3_600_000)
        engine().drain()

        // Local is NEUTRAL and that is delivered as history, but the guarded update
        // matched nothing and the ignore-duplicates insert found a row, so the newer
        // remote state stands.
        assertTrue(remoteHistory(key).contains("UNLIKE"))
        assertEquals("LIKED", reactionOf(remoteState(key)))
    }

    // ==================== 5-6: changing your mind ====================

    @Test
    fun liked_to_disliked_keeps_both_events_and_ends_disliked() = runBlocking {
        val key = keyFor("L2D")
        val now = System.currentTimeMillis()
        db.reactionDao().like(key, "$MARKER L2D", "$MARKER L2D", "myata", now, now)
        db.reactionDao().dislike(key, "$MARKER L2D", "$MARKER L2D", "myata", now + 1_000)

        engine().drain()

        // Two acts, two events, and no invented UNLIKE between them.
        assertEquals(listOf("LIKE", "DISLIKE"), remoteHistory(key))
        assertEquals("DISLIKED", reactionOf(remoteState(key)))
    }

    @Test
    fun disliked_to_liked_keeps_both_events_and_ends_liked() = runBlocking {
        val key = keyFor("D2L")
        val now = System.currentTimeMillis()
        db.reactionDao().dislike(key, "$MARKER D2L", "$MARKER D2L", "myata", now)
        db.reactionDao().like(key, "$MARKER D2L", "$MARKER D2L", "myata", now + 1_000, now + 1_000)

        engine().drain()

        assertEquals(listOf("DISLIKE", "LIKE"), remoteHistory(key))
        assertEquals("LIKED", reactionOf(remoteState(key)))
    }

    // ==================== 7: idempotency against the real server ====================

    @Test
    fun redelivering_an_event_does_not_duplicate_history() = runBlocking {
        val key = keyFor("RETRY")
        val now = System.currentTimeMillis()
        db.reactionDao().like(key, "$MARKER RETRY", "$MARKER RETRY", "myata", now, now)

        val row = db.reactionOutboxDao().pending().single()
        val api = SupabaseReactionSyncApi(context)

        // Three deliveries of the same event_id, straight at the server. This is the
        // real ON CONFLICT DO NOTHING, not a fake standing in for it.
        repeat(3) { assertTrue(api.deliverEvent(row, listener) is com.example.musicplayerapp.data.supabase.SyncOutcome.Success) }

        assertEquals(listOf("LIKE"), remoteHistory(key))

        // And the ordinary drain afterwards still completes and clears the row.
        engine().drain()
        assertEquals(listOf("LIKE"), remoteHistory(key))
        assertEquals("LIKED", reactionOf(remoteState(key)))
        assertEquals(0, db.reactionOutboxDao().count())
    }

    @Test
    fun a_full_drain_run_twice_changes_nothing_the_second_time() = runBlocking {
        val key = keyFor("TWICE")
        val now = System.currentTimeMillis()
        db.reactionDao().like(key, "$MARKER TWICE", "$MARKER TWICE", "myata", now, now)

        engine().drain()
        // The outbox is empty now, so the second run is Idle and must not touch
        // anything - including not creating a second identity.
        assertEquals(DrainResult.Idle, engine().drain())

        assertEquals(listOf("LIKE"), remoteHistory(key))
        assertEquals("LIKED", reactionOf(remoteState(key)))
    }

    // ==================== the stale-event guarantee, live ====================

    @Test
    fun a_stale_queued_event_does_not_restore_stale_remote_state() = runBlocking {
        val key = keyFor("STALE")
        val now = System.currentTimeMillis()

        // A LIKE is queued and never sent.
        db.reactionDao().like(key, "$MARKER STALE", "$MARKER STALE", "myata", now, now)
        val stale = db.reactionOutboxDao().pending().single()

        // The listener changes their mind. Only the old LIKE is left pending.
        db.reactionDao().dislike(key, "$MARKER STALE", "$MARKER STALE", "myata", now + 60_000)
        db.reactionOutboxDao().pending()
            .filter { it.eventId != stale.eventId }
            .forEach { db.reactionOutboxDao().delete(it.eventId) }

        engine().drain()

        // History records the LIKE, because it happened.
        assertEquals(listOf("LIKE"), remoteHistory(key))
        // Current state is DISLIKED, because that is what Room says now. A folded
        // event stream would have written LIKED here.
        assertEquals("DISLIKED", reactionOf(remoteState(key)))
    }

    // ==================== a reaction during a running drain ====================

    @Test
    fun a_reaction_committed_while_a_drain_is_running_is_not_stranded() = runBlocking {
        val first = keyFor("MIDRUN_A")
        val second = keyFor("MIDRUN_B")
        val now = System.currentTimeMillis()

        db.reactionDao().like(first, "$MARKER MIDRUN_A", "$MARKER MIDRUN_A", "myata", now, now)

        // Start draining, then commit another reaction while it is in flight. This
        // is race B from the listener's side: a tap during a sync.
        val running = launch(Dispatchers.IO) { engine().drain() }
        delay(40)
        db.reactionDao().like(second, "$MARKER MIDRUN_B", "$MARKER MIDRUN_B", "myata", now + 1, now + 1)
        running.join()

        // Whether the second row made it into the first run's batch is a race and
        // does not matter. What matters is that it is still there to be drained -
        // never dropped - and that a following run finishes it. The scheduler's
        // APPEND_OR_REPLACE policy is what guarantees that following run exists;
        // ReactionSyncSchedulerTest covers that half.
        engine().drain()

        assertEquals(0, db.reactionOutboxDao().count())
        assertEquals("LIKED", reactionOf(remoteState(first)))
        assertEquals("LIKED", reactionOf(remoteState(second)))
        assertEquals(listOf("LIKE"), remoteHistory(second))
    }

    // ==================== 12: identity stability ====================

    @Test
    fun the_anonymous_uid_is_stable_across_repeated_sync_boundaries() = runBlocking {
        val first = AnonymousSession.ensureAuthenticatedListener(context)
        val second = AnonymousSession.ensureAuthenticatedListener(context)
        val third = AnonymousSession.ensureAuthenticatedListener(context)

        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(second, third)
        assertEquals(first, listener)
        // And the marker on disk agrees, which is what stops a failed refresh being
        // read as a new listener.
        assertEquals(first, AnonymousSession.knownUid(context))
    }
}
