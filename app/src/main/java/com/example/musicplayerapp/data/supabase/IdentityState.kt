package com.example.musicplayerapp.data.supabase

/**
 * What this install's remote identity *is*, as a value rather than an inference.
 *
 * ## What this replaces
 *
 * There used to be one string in preferences, `listener_uid`, and one rule read off
 * it: **a uid is present, therefore never mint again.** That was exactly right for
 * the only two situations that existed - an install that had never synced, and one
 * that had - and it is the reason a flaky network never split a listener in two.
 *
 * It has no answer for the situations that arrive with accounts. "There is a uid" and
 * "the listener is signed in" stop being the same sentence the moment somebody signs
 * out: a marker still sits on disk, so no identity is minted, which is correct - but
 * nothing distinguishes *deliberately signed out* from *temporarily unable to reach
 * the server*, and those two want opposite behaviour from the sync worker. One should
 * retry until the network comes back. The other should stop and stay stopped.
 *
 * So the state is written down instead of deduced. Every state below is persisted and
 * survives process death; the ones marked reserved are representable now and acted on
 * later, because a state you cannot store is a state you cannot migrate to safely.
 *
 * ## The invariant that outlives all of it
 *
 * **No state transition may ever mint a second `auth.uid()` for one person.** Only
 * [None] can become [Anonymous] by signing in, and only once. Every other state that
 * cannot reach the server reports that it cannot, and waits. Losing one sync is
 * recoverable; splitting a listener's data across two identities is not, and no later
 * merge fully undoes it - see `docs/SUPABASE-FOUNDATION.md` on the G-A7 handoff.
 */
sealed interface IdentityState {

    /**
     * The uid this install owns, or last owned. Null only for [None].
     *
     * Deliberately present on [SignedOut] too: the identity did not stop existing
     * because the listener signed out of it, and knowing which one it was is what a
     * later sign-in needs.
     */
    val uid: String?

    /** Never owned an identity. The only state from which one may be minted. */
    data object None : IdentityState {
        override val uid: String? = null
    }

    /**
     * An anonymous `auth.users` row this install created and owns.
     *
     * Survives restart. A failed refresh does **not** leave this state - it is still
     * this listener, still this uid, just temporarily unreachable.
     */
    data class Anonymous(override val uid: String) : IdentityState

    /**
     * **Reserved for G-A4.** An email has been claimed for [uid] and not yet
     * confirmed.
     *
     * Representable and persisted now, and deliberately not produced by anything yet.
     * It exists here because the confirmation arrives out of band - a link in a mail
     * client, minutes or days later, quite possibly after the process has died - so
     * the pending claim has to be durable from the moment it is made rather than held
     * in memory by whatever screen made it.
     */
    data class EmailPending(override val uid: String, val email: String) : IdentityState

    /**
     * **Reserved for G-A4.** The email is confirmed; no password exists yet.
     *
     * A real intermediate rather than a formality: between confirming an address and
     * choosing a password the account is neither anonymous nor complete, and a process
     * death in that window must not land the listener back at [Anonymous].
     */
    data class EmailVerified(override val uid: String) : IdentityState

    /** A full account. Same `auth.uid()` the anonymous rows were always written under. */
    data class Registered(override val uid: String) : IdentityState

    /**
     * Signed out on purpose. Cloud sync is **paused**, not broken.
     *
     * Three things are true at once here, and the design hangs on all three:
     *
     *  - the local Room Collection is untouched, because it was never the cloud's copy;
     *  - reactions carry on accumulating locally and in the outbox, waiting for a
     *    future sign-in;
     *  - **no anonymous identity may be minted.** Signing out is not a route back to
     *    [None]. Doing that would hand the listener a fresh uid and quietly orphan
     *    everything [lastUid] owns.
     *
     * ## The frozen logout contract (owner decision, G-A2)
     *
     * Settled now so G-A4 inherits it rather than re-deciding it. **None of it is
     * implemented here** - there is no logout UI and no call to `auth.signOut()`,
     * because nothing can sign out yet. What is fixed is the shape:
     *
     *  1. **LOCAL scope only.** Signing out on this device signs out *this* device.
     *     Other devices stay signed in, because one person deciding to sign out of
     *     their phone is not a statement about their tablet.
     *  2. **The stored Supabase session and tokens are cleared from this device.**
     *  3. **No session is retained for a "fast re-login".** The convenience is real
     *     and it is refused deliberately: a signed-out install holding a live
     *     authenticated session is one bug away from silently resuming as a listener
     *     who asked to be signed out, and "signed out" would stop being a claim the
     *     app can honestly make.
     *  4. Local Room and the Collection are untouched.
     *  5. The persisted state becomes `SignedOut(lastUid)`.
     *  6. **This state is authoritative over any Supabase session that turns up
     *     anyway.** A restored session does not un-sign-out an install; the stored
     *     state wins. That is why [ListenerSession.restore] checks it before it
     *     touches the client at all.
     *  7. **A stale session left by a crash mid-logout must be ignored and cleared**
     *     by G-A4's startup handling. Clearing the token and writing the state cannot
     *     be made atomic, so the recovery rule is written down instead: state
     *     `SignedOut` plus a live session means the logout was interrupted, and the
     *     session is the part that is wrong.
     *  8. No new anonymous uid is ever minted automatically.
     *
     * There is deliberately **no `SIGNING_OUT` state**. It would only earn its place
     * if the ordering above needed a durable marker between "token cleared" and "state
     * written", and rule 7 removes that need by making the end state self-correcting.
     * Ordering and crash recovery are G-A4's to implement.
     */
    data class SignedOut(val lastUid: String) : IdentityState {
        override val uid: String get() = lastUid
    }

    /**
     * Whether cloud sync may run at all in this state.
     *
     * False only for [SignedOut]. [None] is true because a sync boundary reaching it
     * is precisely the moment an identity is allowed to be minted.
     */
    val syncEnabled: Boolean
        get() = this !is SignedOut

    /**
     * Whether this state is an **account** rather than a device-scoped identity.
     *
     * The one definition of the set the anti-demotion guard protects, so that the
     * guard and the callers that avoid tripping it cannot come to disagree about what
     * an account is. [IdentityStore.adoptAnonymous] refuses every state in here;
     * [ListenerSession] uses the same predicate to know there is nothing to adopt, so
     * adding a state to the set repairs both sides at once.
     *
     * [SignedOut] is deliberately **not** an account state here even though it is
     * usually reached from one: what it records is that this install is not currently
     * anybody, and the paths that consult this have already short-circuited on it.
     */
    val isAccount: Boolean
        get() = this is EmailPending || this is EmailVerified || this is Registered
}

/**
 * The answer to "who owns the rows I am about to write", and the reason it is three
 * cases rather than a nullable string.
 *
 * The drain used to get `String?`, and null had to mean two incompatible things:
 * *not right now, ask again in a minute* and *stop, this listener signed out*. The
 * first wants WorkManager to retry with backoff. The second wants it to succeed and
 * schedule nothing, because retrying a paused account is a wake-up that can never do
 * anything - forever, on a schedule.
 */
sealed interface ListenerIdentity {

    /** Signed in. [uid] owns whatever gets written next. */
    data class Available(val uid: String) : ListenerIdentity

    /**
     * Nobody's fault and probably temporary: offline, token expired, project paused,
     * no project configured. **The identity still exists**; it just cannot be produced
     * this minute. Rows keep their attempt counts and their place in the queue.
     */
    data class Unavailable(val reason: String) : ListenerIdentity

    /**
     * Deliberately signed out. Nothing is wrong and nothing should be retried.
     *
     * Distinct from [Unavailable] because the correct response is opposite: touch no
     * row, count no attempt, park nothing, and schedule no further work until an
     * explicit sign-in changes the state.
     */
    data class Paused(val lastUid: String) : ListenerIdentity
}
