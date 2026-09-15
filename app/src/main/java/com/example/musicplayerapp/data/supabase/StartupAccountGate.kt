package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the launch splash for a registered install until its stored session is restored,
 * so the first frame of HOME is already the account's - never `Привет!` and the glyph.
 *
 * ## Why
 *
 * supabase-kt restores the stored session in the background after the client is created:
 * building the client measured 1.05-1.45 s and the storage load 0.21-0.49 s on the slowest
 * QA emulator. The splash used to end on the app's first
 * frame, before that, so every cold start of a signed-in install showed the guest header
 * for a few hundred milliseconds (G6a flicker recording). Only a registered install has
 * anything to wait for; everyone else starts exactly as before.
 *
 * ## What it waits for, and what it never waits for
 *
 * [EmailAuthApi.awaitSessionRestored] - the Auth plugin leaving `Initializing`, a read of
 * local storage - then [EmailAuthApi.currentAccount], which reads the restored session.
 * Never the account refresh, never any request of the app's own. The one way the plugin's
 * restore itself can touch the network is an access token that has already expired, which
 * it renews before it reports a session; [RESTORE_TIMEOUT_MS] is what bounds that case.
 *
 * ## It never holds forever
 *
 * [TIMEOUT_MS] is a hard ceiling on the whole wait, measured from [start], and
 * [RESTORE_TIMEOUT_MS] a narrower one on the restore alone once the client exists. A restore
 * that throws, hangs, or outlives either releases the splash and leaves the account
 * **unresolved**: HOME then shows a neutral header - neither a name nor the guest greeting -
 * until its own read settles.
 * Nobody is signed out, nothing is retried here, and nothing is written.
 *
 * ## One main-thread step
 *
 * The outcome is published on the main thread, and in one runnable: the result goes into
 * [KnownAccount], the listeners (HOME) repaint from it, and only then does [isHolding]
 * turn false. The splash checks [isHolding] before each frame on that same thread, so the
 * first frame it lets through already has the repainted header.
 */
object StartupAccountGate {

    private const val TAG = "SupabaseAuth"

    /**
     * The hard ceiling on the whole wait, client construction included - nothing can hold the
     * splash longer. Building the Supabase client and its Auth plugin measured 1.05-1.45 s on
     * the slowest QA emulator (software GPU, 7 s cold starts); this is about twice that and
     * well inside the 5 s input-dispatch ANR window.
     */
    const val TIMEOUT_MS = 3_000L

    /**
     * The ceiling on the restore itself, once the client exists - the only phase that can touch
     * the network (a stored access token that has already expired is renewed before the plugin
     * reports a session). Loading from storage measured 0.21-0.49 s; this is about twice that.
     */
    const val RESTORE_TIMEOUT_MS = 1_000L

    /** What the gate concluded. [Unresolved] covers a timeout and a failed restore alike. */
    enum class Outcome { NotRegistered, Account, NoAccount, Unresolved }

    @Volatile
    var isHolding: Boolean = false
        private set

    @Volatile
    var outcome: Outcome? = null
        private set

    /** Instrumentation shortens the ceilings so a never-completing restore is testable. */
    @Volatile
    internal var timeoutMs: Long = TIMEOUT_MS

    @Volatile
    internal var restoreTimeoutMs: Long = RESTORE_TIMEOUT_MS

    @Volatile
    private var started = false

    /** Bumped by [resetForTest], so a previous run's late result cannot touch this one. */
    @Volatile
    private var generation = 0

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called from `MainActivity.onCreate`, before the first frame. Once per process: a
     * recreated activity (an appearance change) finds it already settled and is not held.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        val state = IdentityStore.state(app)
        if (state !is IdentityState.Registered) {
            outcome = Outcome.NotRegistered
            return
        }

        isHolding = true
        val run = generation
        val startedAt = SystemClock.elapsedRealtime()

        // The hard ceiling is a main-thread timer, not only withTimeoutOrNull below: coroutine
        // timeouts are cooperative, and building the client (a synchronized, blocking call)
        // has no suspension point for one to act on. A timer on the main looper cannot be held
        // up by work on IO, so the splash is released at TIMEOUT_MS whatever that work is doing.
        main.postDelayed({ settle(run, startedAt, Outcome.Unresolved, null, "timeout") }, timeoutMs)

        scope.launch {
            val restoreCap = restoreTimeoutMs
            val result: Result<AccountInfo?>? = withTimeoutOrNull(timeoutMs) {
                try {
                    val api = EmailAuthBackend.api(app)
                    api.prepareSession()
                    // Null: the restore outlived its own, narrower ceiling.
                    withTimeoutOrNull(restoreCap) {
                        api.awaitSessionRestored()
                        Result.success(api.currentAccount())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val concluded = when {
                result == null -> Outcome.Unresolved
                result.isFailure -> Outcome.Unresolved
                result.getOrNull()?.uid == state.uid -> Outcome.Account
                else -> Outcome.NoAccount
            }
            val why = result?.exceptionOrNull()?.javaClass?.simpleName ?: if (result == null) "timeout" else null
            main.post { settle(run, startedAt, concluded, state.uid to result?.getOrNull(), why) }
        }
    }

    /**
     * Publishes the first outcome to arrive - the restore's, or the timer's - on the main thread,
     * once per run; the other is ignored. See the class notes for the order of the three steps.
     */
    private fun settle(run: Int, startedAt: Long, concluded: Outcome, restored: Pair<String, AccountInfo?>?, why: String?) {
        if (run != generation || outcome != null) return
        Log.d(
            TAG,
            "startup session gate: $concluded in ${SystemClock.elapsedRealtime() - startedAt} ms" +
                (why?.let { " ($it)" } ?: ""),
        )
        // Only into an empty KnownAccount: HOME's own read or the refresh may already have
        // landed something newer while this was queued.
        if (restored != null) when (concluded) {
            Outcome.Account -> KnownAccount.settleIfUnknown(restored.first, restored.second)
            Outcome.NoAccount -> KnownAccount.settleIfUnknown(restored.first, null)
            else -> Unit
        }
        outcome = concluded
        listeners.forEach { it() }
        isHolding = false
    }

    /** A main-thread callback run when the gate settles, before the splash is released. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Instrumentation: forget this process's start, so each launch is gated afresh. */
    fun resetForTest(timeout: Long = TIMEOUT_MS, restoreTimeout: Long = RESTORE_TIMEOUT_MS) {
        generation++
        started = false
        isHolding = false
        outcome = null
        timeoutMs = timeout
        restoreTimeoutMs = restoreTimeout
        listeners.clear()
    }
}
