package com.example.musicplayerapp.data.supabase

import android.content.Context
import androidx.core.content.edit

/**
 * What each account on this device has actually synchronised, and when.
 *
 * Three facts, all keyed by listener uid, because every one of them is a statement
 * about an account rather than about a phone. An install that signs out of X and into
 * Y has not synchronised anything as Y, and a row that showed X's time would be
 * answering a question nobody asked.
 *
 * ```
 * last_upload_<uid>              something of mine reached the cloud
 * last_pull_<uid>                I read the whole account back
 * initial_restore_complete_<uid> I have read it back at least once, ever
 * ```
 *
 * ## What counts as an upload
 *
 * **A drain that actually delivered at least one row.** That is
 * [DrainResult.Drained] with `delivered > 0`, and [DrainResult.MoreWorkDue], which
 * only occurs after a full batch went out. Both `APPLIED` and `ALREADY_APPLIED`
 * settle rows and therefore count: the second means the events did reach the cloud
 * earlier and this device is only learning it now, and not counting it would empty
 * the outbox while the profile still claimed the account had never synchronised.
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
 * ## Whose upload it was
 *
 * The uid comes from the drain that delivered - [DrainResult.Drained.listenerId] -
 * and never from whatever identity happens to be current when the worker gets round
 * to its bookkeeping. Those are different questions and they can genuinely differ: a
 * drain releases [SyncLease], a sign-out and a sign-in as another account can land,
 * and the worker would then file X's delivery under Y.
 *
 * ## It is never written by looking at it
 *
 * Nothing on the profile screen touches this. Opening the profile is not a sync, and
 * a screen that updated the timestamp it displays would always show the moment it was
 * opened.
 *
 * `apply()` rather than `commit()`, and that is the opposite of [IdentityStore]'s
 * rule on purpose: losing this to a process death costs one stale line of text until
 * the next successful sync, while losing an identity marker splits a listener in two.
 * Not every write deserves the same paranoia.
 */
object LastSyncStore {

    private const val PREFS = "myata_last_sync"

    private const val KEY_UPLOAD_PREFIX = "last_upload_"
    private const val KEY_PULL_PREFIX = "last_pull_"

    /** One durable flag per account. See [markInitialRestoreComplete]. */
    private const val KEY_RESTORED_PREFIX = "initial_restore_complete_"

    /**
     * Records that something [uid] owns reached the cloud.
     *
     * Called only from the drain's own verdict, with the uid that owned that drain.
     */
    fun recordUploadSuccess(context: Context, uid: String, at: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_UPLOAD_PREFIX + uid, at) }
    }

    /** When something of [uid]'s last reached the cloud, or null if nothing ever has. */
    fun lastUploadAt(context: Context, uid: String): Long? =
        prefs(context).getLong(KEY_UPLOAD_PREFIX + uid, 0L).takeIf { it > 0L }

    /**
     * Records a completed pull for [uid]. **Only** after a full scan.
     *
     * A partial scan is not a synchronisation: the pages it applied are valid and
     * kept, but the account has not been read to the end, so saying "synchronised"
     * would be claiming something nobody checked. The next run starts at revision
     * zero again and the local watermark makes the replay idempotent.
     */
    fun recordPullSuccess(context: Context, uid: String, at: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_PULL_PREFIX + uid, at) }
    }

    /** When this device last read [uid]'s account back in full, or null if never. */
    fun lastPullAt(context: Context, uid: String): Long? =
        prefs(context).getLong(KEY_PULL_PREFIX + uid, 0L).takeIf { it > 0L }

    /**
     * The more recent of [uid]'s two timestamps, or null if neither has happened.
     *
     * What `Последняя синхронизация` shows for the account being displayed. The two
     * facts stay stored apart - they answer different questions and one account can
     * have had either without the other - but the row asks a third question, "when did
     * this device last exchange anything for this account", and that is their maximum.
     *
     * Combining them only here is what keeps the storage honest. Writing one into the
     * other would make an account that was restored but never pushed indistinguishable
     * from one that has done neither, which is the false "never synchronised" this
     * phase exists to stop showing.
     */
    fun lastSyncAt(context: Context, uid: String): Long? {
        val upload = lastUploadAt(context, uid)
        val pull = lastPullAt(context, uid)
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
        prefs(context).edit { putBoolean(KEY_RESTORED_PREFIX + uid, true) }
    }

    /** Whether [uid] has ever been read back in full on this install. */
    fun isInitialRestoreComplete(context: Context, uid: String): Boolean =
        prefs(context).getBoolean(KEY_RESTORED_PREFIX + uid, false)

    /**
     * Removes every fact this store holds about [uid], and nothing about anyone else.
     *
     * For permanent account deletion: once the account is gone, "when did it last
     * sync" is a question about something that no longer exists, and an install that
     * later registered a *new* account would otherwise carry a stranger's timestamps
     * under a stranger's uid forever.
     *
     * Named keys rather than a file wipe, which is the whole point of it and the
     * difference from [clearForTest]. The three keys are reconstructible from the uid,
     * so one account's history can be removed without touching another's - and a
     * device that has used two accounts must keep the survivor's.
     *
     * Deleting an absent key is a no-op, so this is idempotent and safe to re-run
     * after an interrupted cleanup.
     */
    fun forget(context: Context, uid: String) {
        prefs(context).edit {
            remove(KEY_UPLOAD_PREFIX + uid)
            remove(KEY_PULL_PREFIX + uid)
            remove(KEY_RESTORED_PREFIX + uid)
        }
    }

    /**
     * Test-only: return this install to never-synced, for every account.
     *
     * Clears the whole file rather than named keys: the per-account keys are not
     * enumerable from here, and a test that left one behind would leak into the next.
     */
    fun clearForTest(context: Context) {
        prefs(context).edit { clear() }
    }

    /** Test-only: put a known upload moment on disk without running a drain. */
    fun recordForTest(context: Context, uid: String, at: Long) =
        recordUploadSuccess(context, uid, at)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
