package com.example.musicplayerapp.data.lastfm

/**
 * The Last.fm API error codes this feature can actually meet, and what each one
 * means for the caller.
 *
 * Codes are from https://www.last.fm/api/errorcodes plus the three token states
 * documented on the `auth.getSession` method page. Only the ones reachable from
 * the four methods this app uses are named; anything else arrives as
 * [LastfmAction.Park] through [actionForCode], which is the safe answer for an
 * error nobody has reasoned about.
 *
 * ## Why the action is here and not at the call site
 *
 * Every one of these codes has exactly one correct response and several plausible
 * wrong ones. Code 9 must clear the session and stop draining; code 29 must back
 * off rather than retry; code 6 must delete the row because it will never succeed;
 * code 14 is not an error at all. Deciding that at each call site is how a queue
 * ends up either hot-looping on a permanent failure or silently discarding a
 * listener's scrobbles. It is decided once, here, and the decision is a pure
 * function so it can be pinned by tests.
 */
enum class LastfmError(val code: Int) {

    /** 2 - the service does not exist. A bug in our URL. */
    INVALID_SERVICE(2),

    /** 3 - no such method. A bug in our method name. */
    INVALID_METHOD(3),

    /**
     * 4 - authentication failed.
     *
     * On `auth.getSession` this is the token being invalid rather than the listener
     * being wrong about anything, so it restarts authorisation rather than
     * reporting a credential problem.
     */
    AUTHENTICATION_FAILED(4),

    /** 5 - the requested response format is not supported. A bug in `format`. */
    INVALID_FORMAT(5),

    /** 6 - a required parameter is missing or malformed. A bug, and permanent. */
    INVALID_PARAMETERS(6),

    /** 7 - the resource specified does not exist. Permanent. */
    INVALID_RESOURCE(7),

    /** 8 - the operation failed for a reason Last.fm does not elaborate on. Transient. */
    OPERATION_FAILED(8),

    /**
     * 9 - the session key is no longer valid.
     *
     * The listener revoked this application at last.fm/settings/applications, or
     * Last.fm invalidated it. Nothing can be delivered until they authorise again,
     * and their queued scrobbles are still perfectly good once they do.
     */
    INVALID_SESSION_KEY(9),

    /** 10 - the api key is not valid. Nothing the listener can do. */
    INVALID_API_KEY(10),

    /** 11 - the service is temporarily offline. Transient. */
    SERVICE_OFFLINE(11),

    /**
     * 13 - the signature did not match.
     *
     * Always our bug: a parameter signed that should not have been, `format`
     * included by mistake, a wrong sort order, or a mangled secret. It is not
     * transient and it is not the row's fault, so the row is parked rather than
     * dropped and the bug is reported.
     */
    INVALID_METHOD_SIGNATURE(13),

    /**
     * 14 - the token has not been authorised.
     *
     * **Not a failure.** It is the expected answer when the listener came back from
     * the browser without granting access, or before they finished. The token is
     * still usable until it expires, so the right response is to keep waiting.
     */
    TOKEN_NOT_AUTHORIZED(14),

    /** 15 - the token expired. Tokens last 60 minutes; start over. */
    TOKEN_EXPIRED(15),

    /** 16 - temporary service failure. Transient, and the one Last.fm asks us to retry. */
    TEMPORARY_ERROR(16),

    /** 26 - this api key has been suspended. Nothing the listener can do. */
    SUSPENDED_API_KEY(26),

    /** 27 - this request type is deprecated. Permanent, and our bug. */
    DEPRECATED_REQUEST(27),

    /**
     * 29 - too many requests from this IP.
     *
     * Retrying promptly is precisely wrong, and retrying in a tight loop is how an
     * api key gets suspended. [LastfmAction.Backoff] carries a floor rather than
     * leaving the delay to the caller.
     */
    RATE_LIMIT_EXCEEDED(29),
    ;

    companion object {

        /** The named error for [code], or null if it is one nobody has reasoned about. */
        fun from(code: Int): LastfmError? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What a caller should do about an outcome.
 *
 * Deliberately about *behaviour* rather than severity: "park" and "drop" are both
 * failures, and confusing them either loses a listener's scrobbles or retries
 * something forever.
 */
sealed class LastfmAction {

    /**
     * Transient. Try the whole run again shortly; leave every row untouched.
     *
     * The queue's own attempt counter is what stops this being unbounded.
     */
    data object Retry : LastfmAction()

    /**
     * Transient, but not soon. Try again no earlier than [minDelayMs].
     *
     * Distinct from [Retry] because the reason for waiting is that we asked too
     * often, and a normal retry cadence would make it worse.
     */
    data class Backoff(val minDelayMs: Long) : LastfmAction()

    /**
     * The listener has to authorise again. Clear the stored session key, stop
     * draining, and surface the re-auth state; keep the rows.
     */
    data object Reauthenticate : LastfmAction()

    /**
     * The pending authorisation token is still valid and simply has not been
     * granted yet. Keep waiting; this is not an error to show anybody.
     */
    data object AwaitAuthorization : LastfmAction()

    /** The pending token is unusable. Discard it and request a new one. */
    data object RestartAuthorization : LastfmAction()

    /**
     * This build's credentials are refused. Turn the feature off for this process,
     * park whatever is queued, and report it - no listener action can help.
     */
    data object DisableFeature : LastfmAction()

    /**
     * This row cannot go out now and may never. Count the attempt, push its next
     * attempt out, and let the run succeed.
     *
     * Failure belongs in the queue, where it can be inspected and where it does not
     * propagate - not in WorkManager's graph, where a failed request would cancel
     * the dependents of an `APPEND_OR_REPLACE` chain. The reaction outbox learned
     * this the hard way; see `ReactionSyncWorker`.
     */
    data object Park : LastfmAction()

    /**
     * This row will never be accepted. Delete it.
     *
     * Reserved for outcomes that are permanent *and* about the row itself - a
     * malformed parameter, or a scrobble Last.fm has explicitly judged and ignored.
     * Never used for anything that might work later.
     */
    data object Drop : LastfmAction()
}

/**
 * The action for a Last.fm error code.
 *
 * Total by construction: an unrecognised code parks. That is the conservative
 * answer in both directions - nothing is deleted on the strength of an error
 * nobody has read, and nothing retries in a loop on one that turns out to be
 * permanent.
 */
fun actionForCode(code: Int): LastfmAction = when (LastfmError.from(code)) {
    LastfmError.OPERATION_FAILED,
    LastfmError.SERVICE_OFFLINE,
    LastfmError.TEMPORARY_ERROR -> LastfmAction.Retry

    LastfmError.RATE_LIMIT_EXCEEDED -> LastfmAction.Backoff(RATE_LIMIT_FLOOR_MS)

    LastfmError.INVALID_SESSION_KEY -> LastfmAction.Reauthenticate

    LastfmError.TOKEN_NOT_AUTHORIZED -> LastfmAction.AwaitAuthorization

    LastfmError.AUTHENTICATION_FAILED,
    LastfmError.TOKEN_EXPIRED -> LastfmAction.RestartAuthorization

    LastfmError.INVALID_API_KEY,
    LastfmError.SUSPENDED_API_KEY -> LastfmAction.DisableFeature

    // Our bugs. Permanent until a new build, and not the row's fault, so the row
    // survives to be delivered by the build that fixes them.
    LastfmError.INVALID_SERVICE,
    LastfmError.INVALID_METHOD,
    LastfmError.INVALID_FORMAT,
    LastfmError.INVALID_METHOD_SIGNATURE,
    LastfmError.DEPRECATED_REQUEST -> LastfmAction.Park

    // Permanent and about this row: no build and no retry makes a malformed
    // parameter acceptable.
    LastfmError.INVALID_PARAMETERS,
    LastfmError.INVALID_RESOURCE -> LastfmAction.Drop

    null -> LastfmAction.Park
}

/**
 * The floor for a rate-limit backoff.
 *
 * Five minutes, and a floor rather than the delay itself: the queue's own
 * exponential schedule may well be longer, and whichever is longer wins. The point
 * is only that 29 can never be followed by a prompt retry.
 */
internal const val RATE_LIMIT_FLOOR_MS: Long = 5 * 60 * 1000L

/**
 * Why Last.fm declined to record a scrobble it nonetheless accepted the request
 * for.
 *
 * These arrive per-scrobble inside a `2xx` response, in `ignoredMessage.code`, and
 * they are **not** errors: the server received the scrobble, judged it, and is
 * telling us the verdict. Retrying would send it again to be judged the same way,
 * so every one of them means delete the row.
 *
 * Codes from https://www.last.fm/api/show/track.scrobble.
 */
enum class IgnoredScrobble(val code: Int) {

    /** 0 - not ignored. The scrobble was recorded. */
    ACCEPTED(0),

    /** 1 - the artist is on Last.fm's ignore list. */
    ARTIST_IGNORED(1),

    /** 2 - the track is on Last.fm's ignore list. */
    TRACK_IGNORED(2),

    /**
     * 3 - the timestamp is too far in the past.
     *
     * The reason the queue drops rows past a maximum age rather than carrying them
     * forever: past that point this is the only answer they can get.
     */
    TIMESTAMP_TOO_OLD(3),

    /** 4 - the timestamp is too far in the future. A clock problem, not a track one. */
    TIMESTAMP_TOO_NEW(4),

    /** 5 - the listener hit Last.fm's daily scrobble limit. */
    DAILY_LIMIT_EXCEEDED(5),

    /**
     * A non-zero code Last.fm has not documented, or has added since.
     *
     * Deliberately a member rather than a null: the server has plainly declined the
     * scrobble, and the one outcome that must be impossible is reading "declined
     * for a reason we have no name for" as "recorded". It carries no real code, so
     * [from] never returns it by lookup.
     */
    UNKNOWN(-1),
    ;

    /** Whether Last.fm recorded this scrobble. */
    val accepted: Boolean get() = this == ACCEPTED

    companion object {

        /**
         * The verdict for [code]. Total: an unrecognised non-zero code is [UNKNOWN],
         * which is ignored like any other, and therefore dropped rather than retried.
         */
        fun from(code: Int): IgnoredScrobble =
            entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}
