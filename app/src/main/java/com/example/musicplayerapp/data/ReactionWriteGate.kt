package com.example.musicplayerapp.data

import kotlinx.coroutines.sync.Mutex

/**
 * Serialises reaction commits against the identity handoff's ownership cutover, and
 * against nothing else.
 *
 * ## The one thing it makes true
 *
 * The handoff has to establish a moment before which a reaction belongs to the old
 * identity and after which it belongs to the new one. That moment is "the outbox was
 * observed empty, and the handoff was marked PREPARED". Without a gate those are two
 * separate operations and a tap can land between them - the row would then have been
 * created *before* the boundary but drain *after* it, under the wrong identity. The
 * boundary would be a matter of timing rather than a fact.
 *
 * With the gate there is no between. A reaction either commits before the handoff
 * takes the lock - where the count sees it, the attempt is abandoned and the outbox
 * is drained again - or after PREPARED is on disk, where it is unambiguously the new
 * identity's. There is no third position.
 *
 * ## Held outside the Room transaction, never inside
 *
 * [ReactionDao]'s four mutations take this lock *around* their `@Transaction` body
 * rather than within it. Taking it inside would leave a SQLite write transaction
 * open while suspended on a lock, which serialises every other writer behind a lock
 * they cannot see, and puts the handoff's own `COUNT(*)` behind an open write.
 * Outside, the ordering is the same and nothing is held that anyone else needs.
 *
 * ## A tap never waits on a network call
 *
 * The handoff holds this across exactly two operations - one indexed `COUNT(*)` on a
 * table that is almost always empty, and one `SharedPreferences.commit()`. No
 * network call is inside that span, and the shape of [withReactionWrite] is what
 * keeps it impossible to add one without noticing. Everything slow the handoff does
 * - draining, retiring, authenticating, adopting - happens under [SyncLease]
 * instead, which taps never contend for.
 */
object ReactionWriteGate {

    private val mutex = Mutex()

    /**
     * Runs one reaction mutation. Taken by [ReactionDao]'s four transitions.
     *
     * Uncontended in every ordinary moment of the app's life: the only other holder
     * is the handoff's cutover, which is microseconds long and happens once per
     * registration.
     */
    suspend fun <T> withReactionWrite(block: suspend () -> T): T = mutex.withLockSuspending(block)

    /**
     * Runs the handoff's ownership cutover: the final emptiness check and the
     * PREPARED commit, and nothing else.
     *
     * Named apart from [withReactionWrite] although it is the same lock, because the
     * two callers are asymmetric in a way worth seeing at the call site: one is the
     * hot path being protected, the other is the rare event protecting itself
     * against it.
     */
    suspend fun <T> withOwnershipCutover(block: suspend () -> T): T = mutex.withLockSuspending(block)

    private suspend fun <T> Mutex.withLockSuspending(block: suspend () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }
}
