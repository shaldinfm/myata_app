package com.example.musicplayerapp.data.supabase

/**
 * What the two account-deletion calls can say, as values rather than exceptions.
 *
 * The same shape as [AuthResult] and [SyncOutcome], for the same reason: the part
 * worth testing hard is what the caller does with the answer, and neither a fake nor
 * a test should have to manufacture a Ktor `HttpResponse` to express "the network
 * was down".
 *
 * ## The one split that exists, and the evidence behind it
 *
 * A non-success is either [Refused] - proof that the deletion transaction did **not**
 * commit - or [Failed], which proves nothing either way. Getting that line wrong in
 * the permissive direction is unrecoverable: clearing a deletion marker for a
 * deletion that actually committed leaves an install believing it still owns an
 * account that no longer exists.
 *
 * So [Refused] is granted only on a specific, observable piece of evidence, and
 * everything else - every transport failure, every gateway page, every unparseable
 * body, every SQLSTATE this build does not recognise - is [Failed]. See
 * [SupabaseEmailAuthApi.deleteAccount] for the exact rule.
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

    /**
     * The server ran the function and it refused. **Nothing was deleted.**
     *
     * Granted only for a SQLSTATE that `delete_my_account` raises itself. A plpgsql
     * `RAISE` aborts the enclosing transaction, and PostgREST runs one request in one
     * transaction - so receiving one of those codes is proof that no row, and no
     * receipt, was committed. That is what makes it safe to clear a deletion marker
     * on this outcome and leave the install registered.
     *
     * @property sqlState the five-character code, and deliberately nothing else. The
     *   PostgREST `message`, `details` and `hint` are **not** carried: they are
     *   server-authored text that a caller may log, and none of them is needed to
     *   decide anything. The code alone is the evidence.
     */
    data class Refused(val sqlState: String) : DeleteAccountOutcome

    /**
     * The call did not produce a definitive answer, in either direction.
     *
     * **Not "the deletion failed".** It may have committed and the response been
     * lost, which is precisely the case the receipt exists for. A caller must leave
     * its deletion marker exactly where it is.
     */
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
