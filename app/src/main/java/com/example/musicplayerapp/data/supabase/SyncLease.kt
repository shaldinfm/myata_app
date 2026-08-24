package com.example.musicplayerapp.data.supabase

import kotlinx.coroutines.sync.Mutex

/**
 * Exclusive access to the reaction sync path, for the length of one drain or one
 * identity handoff.
 *
 * ## Why this had to exist
 *
 * There was no single-flight boundary here before, and that was defensible: the
 * scheduler runs two WorkManager chains - the immediate one and the parked-row
 * timer - and [ReactionSyncScheduler] says outright that overlap between them is
 * harmless, *because both remote writes are idempotent*.
 *
 * That assumption expires at the handoff. Retiring an identity's current state is a
 * DELETE, and a drain that is still in flight will happily upsert a row straight
 * back afterwards - which would defeat the retirement structurally rather than
 * occasionally. So the sync path now has a lease, and the handoff takes it.
 *
 * ## Asymmetric on purpose
 *
 *  - a **drain** takes it with [tryAcquire] and gives up immediately if it cannot.
 *    A drain that queued instead would park a WorkManager thread for the length of
 *    somebody's registration, which is a network round trip;
 *  - the **handoff** takes it with [withExclusive], which *waits*. That is the
 *    quiescing step: a drain that started before the handoff still holds the lease,
 *    and the handoff cannot pass this line until that drain has finished and
 *    released it.
 *
 * ## It is not the durable half
 *
 * This is an in-memory lock and it dies with the process. It closes the
 * *concurrency* hole a durable flag cannot - a running drain has already passed any
 * flag check - while the persisted handoff stage closes the *process death* hole a
 * mutex cannot. Neither is sufficient alone, and the two are not interchangeable.
 */
object SyncLease {

    private val mutex = Mutex()

    /** True while anything holds the lease. Diagnostics only; never a decision. */
    val isHeld: Boolean get() = mutex.isLocked

    /**
     * Runs [block] with the lease held, **waiting** for any current holder.
     *
     * The handoff's entry point. It is held across network calls deliberately: the
     * whole point is that no drain may run between retiring X and adopting into Y.
     */
    suspend fun <T> withExclusive(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs [block] only if the lease is free, and returns null if it is not.
     *
     * Never waits. A drain that cannot have the lease is a drain that must not run
     * at all right now, and saying so immediately is cheaper than queueing.
     */
    suspend fun <T : Any> tryAcquire(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
