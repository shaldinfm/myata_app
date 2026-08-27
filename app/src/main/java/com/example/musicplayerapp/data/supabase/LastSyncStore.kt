package com.example.musicplayerapp.data.supabase

import android.content.Context
import androidx.core.content.edit

/**
 * When this device last got a reaction into the cloud.
 *
 * One `Long` in its own preferences file, and deliberately nothing more. The
 * authenticated profile shows `Последняя синхронизация`, the frame fills it with
 * `2 мин назад`, and shipping that string as static text would be inventing a fact
 * about somebody's account - so the fact is recorded instead.
 *
 * ## What counts as a sync
 *
 * **A drain that actually delivered at least one row.** That is
 * [DrainResult.Drained] with `delivered > 0`, and [DrainResult.MoreWorkDue], which
 * only occurs after a full batch went out.
 *
 * Nothing else is written, and the exclusions matter more than the inclusions:
 *
 *  - [DrainResult.Idle] is the commonest outcome by far - the outbox was empty, so
 *    the worker asked one `COUNT(*)` and stopped. Recording it would move the
 *    timestamp every time the scheduler happened to fire, and the row would then say
 *    "synced a minute ago" to somebody who has not reacted to anything in a month.
 *    It would be measuring the scheduler, not the account;
 *  - [DrainResult.Waiting], [DrainResult.RetryLater] and [DrainResult.Paused] are
 *    non-events by definition;
 *  - [DrainResult.HandoffInProgress] read no row at all.
 *
 * So the row answers "when did something of mine last reach the cloud", which is the
 * only reading of that label a listener can act on.
 *
 * ## It is never written by looking at it
 *
 * Nothing on the profile screen touches this. Opening the profile is not a sync, and
 * a screen that updated the timestamp it displays would always show the moment it
 * was opened.
 *
 * `apply()` rather than `commit()`, and that is the opposite of [IdentityStore]'s
 * rule on purpose: losing this to a process death costs one stale line of text until
 * the next successful drain, while losing an identity marker splits a listener in
 * two. Not every write deserves the same paranoia.
 */
object LastSyncStore {

    private const val PREFS = "myata_last_sync"
    private const val KEY_AT = "last_success_at"

    /**
     * Deliberately a second key rather than a redefinition of the first.
     *
     * Upload and restore answer different questions - "did something of mine reach
     * the cloud" and "did I read the account back" - and one install can have done
     * either without the other. Collapsing them would make a fresh device that has
     * restored a Collection but never pushed a reaction indistinguishable from one
     * that has done neither.
     *
     * The profile will eventually show the more recent of the two under
     * `Последняя синхронизация`; that rendering is not part of this change.
     */
    private const val KEY_PULL_AT = "last_pull_at"

    /** Prefixes one durable flag per account. See [markInitialRestoreComplete]. */
    private const val KEY_RESTORED_PREFIX = "initial_restore_complete_"

    /** Records a successful delivery. Called only from the drain's own verdict. */
    fun recordSuccess(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_AT, at) }
    }

    /** When something last reached the cloud, or null if nothing ever has. */
    fun lastSuccessAt(context: Context): Long? =
        prefs(context).getLong(KEY_AT, 0L).takeIf { it > 0L }

    /**
     * Records a completed pull. **Only** after a full scan.
     *
     * A partial scan is not a synchronisation: the pages it applied are valid and
     * kept, but the account has not been read to the end, so saying "synchronised"
     * would be claiming something nobody checked. The next run starts at revision
     * zero again and the local watermark makes the replay idempotent.
     */
    fun recordPullSuccess(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_PULL_AT, at) }
    }

    /** When this device last read the whole account back, or null if it never has. */
    fun lastPullAt(context: Context): Long? =
        prefs(context).getLong(KEY_PULL_AT, 0L).takeIf { it > 0L }

    /**
     * The more recent of the two, or null if neither has ever happened.
     *
     * What `Последняя синхронизация` shows. The two facts stay stored apart - they
     * answer different questions and one install can have done either without the
     * other - but the row asks a third question, "when did this device last exchange
     * anything with the cloud", and that is their maximum.
     *
     * Combining them only here is what keeps the storage honest. Writing one into the
     * other would make a device that restored an account but never pushed
     * indistinguishable from one that has done neither, which is the false
     * "never synchronised" this phase exists to stop showing.
     */
    fun lastSyncAt(context: Context): Long? {
        val upload = lastSuccessAt(context)
        val pull = lastPullAt(context)
        return when {
            upload == null -> pull
            pull == null -> upload
            else -> maxOf(upload, pull)
        }
    }

    /**
     * Records that [uid] has been read back in full at least once on this install.
     *
     * ## What it means, and the four things it is not
     *
     * True only after a complete [ReactionPull] scan for that account - the same
     * condition, in the same place, as [recordPullSuccess]. A push cannot set it, nor
     * can a trigger firing, nor `Busy`, `AuthUnavailable`, `NotEligible`, a transient
     * failure, or a scan that applied some pages and then stopped.
     *
     * It is **not a cursor**: nothing in the pull reads it, so a later app start
     * full-scans exactly as it would have. It is **not the throttle** - that is a
     * sixty-second debounce in memory, and this is durable. It is **not a claim that
     * the account is current**, only that it has been read through once. And it does
     * **not** suppress anything.
     *
     * ## Per account, and deliberately
     *
     * Keyed by uid, so completing X says nothing about Y. An install that switches
     * accounts has restored nothing for the new one until the new one is read, and a
     * sign-out cannot mark anybody complete because nothing on that path writes here.
     */
    fun markInitialRestoreComplete(context: Context, uid: String) {
        prefs(context).edit { putBoolean(restoreKey(uid), true) }
    }

    /** Whether [uid] has ever been read back in full on this install. */
    fun isInitialRestoreComplete(context: Context, uid: String): Boolean =
        prefs(context).getBoolean(restoreKey(uid), false)

    /**
     * One key per account rather than a set of uids.
     *
     * A set would be a read-modify-write, and two accounts completing at once could
     * lose one of them. A key per uid cannot.
     */
    private fun restoreKey(uid: String) = "$KEY_RESTORED_PREFIX$uid"

    /**
     * Test-only: return this install to never-synced, in both directions, for every
     * account.
     *
     * Clears the whole file rather than named keys, because the per-account restore
     * flags are not enumerable from here and a test that left one behind would leak
     * into the next.
     */
    fun clearForTest(context: Context) {
        prefs(context).edit { clear() }
    }

    /** Test-only: put a known moment on disk without running a drain. */
    fun recordForTest(context: Context, at: Long) = recordSuccess(context, at)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
