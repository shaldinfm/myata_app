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

    /** Records a successful delivery. Called only from the drain's own verdict. */
    fun recordSuccess(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_AT, at) }
    }

    /** When something last reached the cloud, or null if nothing ever has. */
    fun lastSuccessAt(context: Context): Long? =
        prefs(context).getLong(KEY_AT, 0L).takeIf { it > 0L }

    /** Test-only: return this install to never-synced. */
    fun clearForTest(context: Context) {
        prefs(context).edit { remove(KEY_AT) }
    }

    /** Test-only: put a known moment on disk without running a drain. */
    fun recordForTest(context: Context, at: Long) = recordSuccess(context, at)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
