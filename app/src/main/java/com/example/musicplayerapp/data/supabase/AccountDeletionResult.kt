package com.example.musicplayerapp.data.supabase

/**
 * What the two account-deletion calls can say, as values rather than exceptions.
 *
 * The same shape as [AuthResult] and [SyncOutcome], for the same reason: the part
 * worth testing hard is what the caller does with the answer, and neither a fake nor
 * a test should have to manufacture a Ktor `HttpResponse` to express "the network
 * was down".
 *
 * ## What is deliberately absent
 *
 * **There is no failure taxonomy here yet.** G-A8b ships the boundary, not the
 * orchestrator, and deciding which failures are *definitive refusals* - the ones that
 * clear the deletion marker and leave the install registered - and which are
 * *inconclusive* - the ones that leave it sync-dead until it can ask again - is a
 * policy decision that belongs with the code that acts on it. Splitting [Failed] into
 * those two families before anything consumes them would freeze a guess.
 *
 * So every non-success is [Failed] carrying the classified [AuthFailure] this package
 * already produces. The orchestrator adds the split when it exists.
 */
sealed interface DeleteAccountOutcome {

    /**
     * The account and its rows are gone, and a receipt for this request now exists.
     *
     * The counts are the server's own report of what it removed, kept because they
     * are the only description of the deleted data anybody will ever get - after this
     * returns there is nothing left to count.
     */
    data class Deleted(
        val reactions: Long,
        val events: Long,
        val applications: Long,
    ) : DeleteAccountOutcome

    /**
     * The account was already gone when this call arrived, and a receipt for **this**
     * request now exists.
     *
     * Not an error, and not a lesser success. It is the ordinary outcome for the
     * second of two devices deleting the same account, and for a retry whose original
     * response was lost - see `docs/ACCOUNT-DELETION.md`. A caller must treat it
     * exactly as it treats [Deleted].
     */
    data object AlreadyDeleted : DeleteAccountOutcome

    /** The call did not produce a definitive answer. See the file header. */
    data class Failed(val failure: AuthFailure) : DeleteAccountOutcome
}

/**
 * Whether one `(request_id, deleted_uid)` pair has a completion receipt.
 *
 * The whole point of this call is that it needs **no session**: a device whose
 * deletion committed and whose response was lost has no credentials left to ask with
 * by the time it next runs. It goes out on the publishable key as `anon`.
 */
sealed interface DeletionStatusOutcome {

    /** A receipt exists for exactly this pair. The deletion completed. */
    data object Completed : DeletionStatusOutcome

    /**
     * No receipt for this pair.
     *
     * **Not evidence that the deletion did not happen** - only that this pair cannot
     * prove that it did. The server answers identically for a pair that never
     * existed, a pair from a deletion that never committed, and a malformed request,
     * which is deliberate: the shape of a wrong guess tells a caller nothing.
     */
    data object Unknown : DeletionStatusOutcome

    /** The question could not be asked. Says nothing about the answer. */
    data class Failed(val failure: AuthFailure) : DeletionStatusOutcome
}
