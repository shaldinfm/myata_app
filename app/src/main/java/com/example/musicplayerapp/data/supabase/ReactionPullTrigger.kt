package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * When the account is read back.
 *
 * G-A7c built the pull and deliberately wired it to nothing: a primitive that
 * schedules itself is one that cannot be tested by asking it to run. This is the
 * scheduling, and it is four moments and no more.
 *
 * ```
 * a sign-in, a registration or a recovery that ends REGISTERED(Y)
 * a completed X -> Y handoff
 * a handoff an earlier process left unfinished, resolved by recovery
 * an ordinary app start whose restored session is the account on disk
 * ```
 *
 * There is no foreground trigger, no resume trigger, no periodic worker and no
 * realtime subscription. Convergence for anything that happens while the app is open
 * is the next start, which is a deliberate v1 trade: a listener with two devices sees
 * the other one's changes when they next open the app, and nobody pays for a poller.
 *
 * ## What this does not decide
 *
 * Not whether the pull *may* run - [ReactionPull] owns that, and the check is
 * REGISTERED plus a restored session for the same uid plus no unresolved handoff.
 * Duplicating a weaker version of it here is exactly how a trigger ends up reading
 * somebody else's account, so this asks only the question it has to ask to key the
 * throttle: **who** would be pulled.
 *
 * Not whether two pulls may overlap either. [SyncLease] already excludes the push
 * drain, the handoff and handoff recovery, and a pull that cannot have it answers
 * [PullResult.Busy]. Nothing here adds a second lock.
 */
object ReactionPullTrigger {

    private const val TAG = "ReactionPull"

    /**
     * How long one listener's completed scan suppresses the next.
     *
     * Long enough that the lifecycle points above cannot stack into several scans of
     * the same account in one launch - a sign-in is routinely followed by
     * reconciliation finishing, and both are legitimate triggers. Short enough that it
     * is a debounce and never a policy: sixty seconds cannot be mistaken for a
     * statement about whether remote state is current.
     */
    internal const val WINDOW_MS = 60_000L

    /**
     * One invocation's claim on one listener's window.
     *
     * A plain class, never a `data class`, and the difference is load-bearing: two
     * triggers can be minted in the same millisecond - certainly under a fixed test
     * clock - and a value comparison could not tell "I claimed it" from "somebody else
     * claimed it at the same instant". Identity can, and the release below depends on
     * telling those apart.
     */
    private class Claim(val at: Long)

    /**
     * The current claim per listener, in memory only.
     *
     * **Deliberately not persisted.** A durable marker here would be one edit away
     * from becoming a cursor, and a bug in it would silently stop an account ever
     * being read again - the failure this whole phase is built to avoid. Losing it on
     * a process death costs one extra scan of a few hundred rows and guarantees that
     * a restart always converges, which is the trade worth making.
     *
     * Keyed by uid, so one listener's debounce says nothing about another's: signing
     * out of X and into Y reads Y immediately.
     */
    private val claims = ConcurrentHashMap<String, Claim>()

    /**
     * Elapsed time, not wall-clock time.
     *
     * The window is a debounce measured in this process's own lifetime, so it must not
     * move when the device's clock does. `System.currentTimeMillis()` can jump
     * backwards on an NTP correction or a timezone change - and a backwards jump would
     * make an existing claim look like it was made in the future, suppressing every
     * pull for up to the size of the jump. `elapsedRealtime()` only ever advances.
     */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * Where the background attempt runs.
     *
     * An unowned application-lived scope, which is the existing pattern in this
     * package - `IdentityReconciler.startupInBackground`,
     * `ListenerSession.restoreInBackground` and `ReactionSyncScheduler` all launch the
     * same way. It must outlive the caller: an authentication returning is not a
     * reason to cancel the read that follows it.
     *
     * A seam because a test needs to be able to *end* one. These launches touch
     * process-wide state - the claim map, the identity store - so one still in flight
     * when the next test sets up will write into it, which looks exactly like a bug in
     * the throttle and is not one.
     */
    internal var scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    /** Test seams, with [clock] and [scope]. Nothing in `src/main` sets any of them. */
    internal var runner: suspend (Context) -> PullResult = { ReactionPull.run(it) }
    internal var onRequest: ((String) -> Unit)? = null

    /**
     * Asks for a pull, without waiting for one.
     *
     * Fire and forget, and that is the contract rather than laziness: this is called
     * from the tail of an authentication and from application startup, and neither may
     * be delayed - let alone failed - by a background read. A sign-in that worked is a
     * sign-in that worked whether or not the account could be read afterwards.
     */
    fun requestInBackground(context: Context, why: String) {
        val app = context.applicationContext
        onRequest?.invoke(why)
        scope.launch {
            runCatching { request(app, why) }
                .onFailure { Log.w(TAG, "pull trigger failed: ${it.message}") }
        }
    }

    /**
     * One debounced attempt. Returns null when the throttle swallowed it.
     *
     * ## What the throttle does and does not consume
     *
     * The window is claimed **before** the scan, so two triggers arriving together
     * collapse into one - that is the case it exists for, since a sign-in and the
     * reconciliation that follows it are both legitimate and nearly simultaneous.
     *
     * It is then **released again** for every outcome that did not actually read the
     * account: [PullResult.Busy] means a drain or a handoff owned the lease,
     * [PullResult.AuthUnavailable] means there was no usable session yet, and
     * [PullResult.NotEligible] means this install is not an account. None of those is
     * evidence about remote state, and letting them eat the window would turn the one
     * immediate retry opportunity into a minute of silence - a trigger that fired
     * while a drain happened to be running would be the only chance that launch got.
     *
     * A [PullResult.Transient] failure does keep the window. The account was reached
     * and the network gave way, and retrying that within the same minute is unlikely
     * to end differently; the next start retries normally.
     */
    internal suspend fun request(context: Context, why: String): PullResult? {
        // An unresolved deletion stops the trigger before it claims the window. The
        // pull would refuse anyway - ReactionPull checks the same marker - but a claim
        // made here and released there is a debounce slot spent on a read that was
        // never going to happen, and this is also the cheaper answer: no coroutine is
        // launched and no engine is built.
        if (IdentityStore.deletionInFlight(context)) {
            Log.d(TAG, "pull skipped ($why): account deletion in flight")
            return null
        }

        // Only enough identity to name the throttle key. Whether the pull may run at
        // all is ReactionPull's decision, asked below and not second-guessed here.
        val uid = (IdentityStore.state(context) as? IdentityState.Registered)?.uid ?: return null

        // Check and claim in one indivisible step. `compute` holds the bin lock for
        // this key across the read and the write, which a get-then-put does not: two
        // triggers arriving together would both read a stale entry, both write, and
        // both go on to read the account.
        val mine = Claim(clock())
        val holder = claims.compute(uid) { _, existing ->
            if (existing != null && mine.at - existing.at < WINDOW_MS) existing else mine
        }
        if (holder !== mine) {
            Log.d(TAG, "pull skipped ($why): within the ${WINDOW_MS / 1000}s window")
            return null
        }

        val result = runner(context)

        when (result) {
            is PullResult.Busy,
            is PullResult.AuthUnavailable,
            is PullResult.NotEligible,
            -> {
                // Nothing was read, so nothing is owed the window back.
                //
                // Conditional, and only ever a removal. An unconditional remove would
                // delete a claim a later invocation had already made, and writing the
                // previous timestamp back would be worse - it would overwrite a newer
                // claim with an older value. `remove(key, value)` touches the mapping
                // only while it is still this invocation's own.
                //
                // The old timestamp is not restored, deliberately. It was necessarily
                // outside the window - that is why this claim succeeded - so restoring
                // it and removing it are indistinguishable to every future check, and
                // removing cannot race.
                claims.remove(uid, mine)
            }

            else -> Unit
        }

        Log.d(TAG, "pull ($why) -> $result")
        return result
    }

    /** Test-only: forget every debounce. */
    internal fun resetForTest() {
        claims.clear()
        scope = CoroutineScope(Dispatchers.IO)
        clock = { SystemClock.elapsedRealtime() }
        runner = { ReactionPull.run(it) }
        onRequest = null
    }
}
