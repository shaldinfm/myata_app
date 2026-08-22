package com.example.musicplayerapp

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.ListenerSession
import com.example.musicplayerapp.data.supabase.SupabaseConfig
import com.example.musicplayerapp.data.supabase.SupabaseModule
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing about G-A2 that cannot be proved without the network: that the
 * reworked boundary still mints exactly one anonymous identity, and only when asked.
 *
 * Everything else about the state machine is storage and is covered offline by
 * [IdentityStateTest]. This is here because G-A2 *changed the identity boundary
 * itself* - `ensureAuthenticatedListener` returning `String?` became
 * [ListenerSession.identity] returning [ListenerIdentity] - and "the mint path was not
 * tested" is not an acceptable state for the PR that rewrote it.
 *
 * ## Opt-in, and deliberately narrow
 *
 * Runs only under `liveSupabase=true` (see [LiveSupabase]):
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.liveSupabase=true \
 *   "-Pandroid.testInstrumentationRunnerArguments.class=com.example.musicplayerapp.AnonymousMintLiveTest"
 * ```
 *
 * It touches **no** reaction table: no `reactions` row, no `reaction_events` row, no
 * outbox row, no Room write of any kind. The only thing it creates anywhere is a
 * single anonymous `auth.users` identity, which is the subject under test and cannot
 * be avoided - you cannot prove minting works without minting. The uid is logged under
 * [TAG] so it can be deleted afterwards by exact id.
 *
 * ## What "concurrent" proves, and what it does not
 *
 * The double-mint guard is a [kotlinx.coroutines.sync.Mutex] held across the
 * check-and-sign-in in [ListenerSession.identity]. Eight coroutines are released at it
 * at once; if the guard were removed or narrowed, more than one would find "no session,
 * state is NONE" and sign in, and the set of returned uids would have more than one
 * element - which is the assertion.
 *
 * This detects a broken guard. It cannot prove the absence of a race, because no
 * probabilistic test can. Its value is that the failure it detects is otherwise
 * invisible: two uids for one person is silent, permanent, and only shows up much
 * later as a listener whose collection is split in half.
 */
@RunWith(AndroidJUnit4::class)
class AnonymousMintLiveTest {

    private companion object {
        /** Grep this in logcat for the uid the run created. */
        const val TAG = "MintProbe"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val auth
        get() = SupabaseModule.client(context)!!.auth

    @Before
    fun requireAnOptInAndAProject() {
        LiveSupabase.assumeOptedIn()
        assumeTrue("no supabase.properties in this build", SupabaseConfig.isConfigured)
    }

    /**
     * `runBlocking` returns whatever the block's last expression does, and a JUnit test
     * method must be void - a trailing `Log.w` returns an Int and the class fails to
     * load with "should be void". This pins the result away.
     */
    private fun runBlockingTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }

    @After
    fun leaveTheDeviceClean() = runBlocking {
        // Local only. The `auth.users` row this test created stays - a client cannot
        // delete it and should not be able to - and is reported for owner-side removal.
        runCatching { auth.clearSession() }
        IdentityStore.clearForTest(context)
    }

    @Test
    fun the_boundary_mints_exactly_one_identity_and_only_when_asked() = runBlockingTest {
        // ---------- 1. a genuinely fresh install ----------
        IdentityStore.clearForTest(context)
        // Local session clearing, not signOut(): this needs no server round trip and
        // does not depend on the existing token still being valid. The device is now in
        // the state a first launch is in.
        auth.clearSession()

        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull("arrange failed: a session survived clearSession()", auth.currentUserOrNull())

        // ---------- 2. startup alone must not mint ----------
        val restoredBefore = ListenerSession.restore(context)

        assertNull("startup minted an identity", restoredBefore)
        assertNull("startup created a session", auth.currentUserOrNull())
        assertEquals(
            "startup moved the install off NONE",
            IdentityState.None,
            IdentityStore.state(context),
        )

        // ---------- 3 + 6. the first boundary call, eight times at once ----------
        val answers = (1..8)
            .map { async(Dispatchers.IO) { ListenerSession.identity(context) } }
            .awaitAll()

        val unavailable = answers.filterIsInstance<ListenerIdentity.Unavailable>()
        assertTrue("boundary failed: ${unavailable.map { it.reason }}", unavailable.isEmpty())
        assertTrue("a fresh install must never report Paused", answers.none { it is ListenerIdentity.Paused })

        val uids = answers.filterIsInstance<ListenerIdentity.Available>().map { it.uid }.toSet()

        // The assertion the whole file exists for. More than one uid here means the
        // guard let two sign-ins through and one person now owns two identities.
        assertEquals("concurrent boundary calls minted more than one identity: $uids", 1, uids.size)

        val uid = uids.single()
        Log.w(TAG, "MINTED_TEST_UID=$uid")

        // ---------- 4. the persisted state agrees ----------
        assertEquals(IdentityState.Anonymous(uid), IdentityStore.state(context))
        assertEquals(uid, ListenerSession.knownUid(context))

        // ---------- 5. asking again returns the same identity, not another ----------
        repeat(3) {
            val again = ListenerSession.identity(context)
            assertTrue("expected Available, got $again", again is ListenerIdentity.Available)
            assertEquals(uid, (again as ListenerIdentity.Available).uid)
        }
        assertEquals(IdentityState.Anonymous(uid), IdentityStore.state(context))

        // ---------- 7. restore resolves to the same identity ----------
        // The closest an in-process test gets to a restart: the session is re-read
        // through the same path `MyataApplication` uses at startup. A genuine
        // process-death restart cannot be driven from instrumentation and is covered
        // by SupabaseFoundationTest's persistence check and the manual API 24 gate.
        assertEquals(uid, ListenerSession.restore(context))
        assertEquals(IdentityState.Anonymous(uid), IdentityStore.state(context))

        Log.w(TAG, "MINT_TEST_COMPLETE uid=$uid states=ANONYMOUS")
    }

    @Test
    fun a_known_identity_is_never_replaced_when_the_session_is_gone() = runBlockingTest {
        // The other half of the mint rule, and the one that protects existing installs:
        // losing the session must never look like a fresh install. No identity is
        // created here - if the guard were broken, one would be, and the assertion on
        // the stored uid is what would catch it.
        val fabricated = "00000000-0000-4000-8000-0000feedface"
        IdentityStore.clearForTest(context)
        IdentityStore.adoptAnonymous(context, fabricated)
        auth.clearSession()

        val who = ListenerSession.identity(context)

        assertTrue(
            "an install with a known identity and no session must not mint, got $who",
            who is ListenerIdentity.Unavailable,
        )
        assertEquals(fabricated, IdentityStore.state(context).uid)
    }
}
