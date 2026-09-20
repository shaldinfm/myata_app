package com.example.musicplayerapp.service

/**
 * The reconnect episode: how much of the fast retry budget has been spent, and
 * whether the episode has moved on to the slow phase that exists so a long outage
 * cannot end in permanent silence.
 *
 * Free of Android types so the whole shape can be pinned by JVM tests - the same
 * treatment [StreamErrorPolicy], [SessionMetadataPolicy] and
 * [PlaybackIntentPolicy] already have. Nothing here touches the player, the
 * network or a Handler: it answers one question per event, and
 * `MediaPlayerService` is the translator on top of it.
 *
 * ## Why the slow phase exists
 *
 * The fast phase is deliberately short - six attempts in about a minute - because
 * hammering the server is the failure mode on the other side. But "the fast
 * retries ran out" is not the same fact as "the listener no longer wants the
 * radio". A stream that was unavailable for one minute used to end the episode for
 * the life of the process: `wantsPlayback` stayed true, the durable record stayed
 * true, nothing was armed, and the radio sat silent until somebody pressed Play. For
 * a station meant to play around the clock that is the wrong answer, so the episode
 * now continues - quietly. The fast budget and the fast backoff are unchanged; after
 * they are spent exactly one attempt is armed [SLOW_RETRY_INTERVAL_MS] out, and one
 * more after each later failure. A server that is down for an hour costs twelve
 * requests, not a loop, and there is never more than one attempt armed.
 *
 * ## An episode that is out of connectivity is parked, not paced
 *
 * A timer cannot know when the network comes back, so a failure with no
 * connectivity at all spends nothing and arms nothing: the episode is parked in
 * [Episode.awaitingNetwork], and the service's connectivity callback is the
 * wake-up. That is true in either phase - parked in the fast phase it is the same
 * attempt it was about to make, and parked in the slow phase it is the slow
 * attempt, brought forward to the moment connectivity actually returned.
 *
 * ## Cancellation belongs to the service, and it is total
 *
 * Pause, Stop, a sleep-timer expiry, headphones coming out and audio focus being
 * lost all end the listener's intent, and [onIntentEnded] is what that leaves
 * behind: nothing spent, nothing wanted, nothing armed. `MediaPlayerService`
 * cancels the armed attempt in the same move, because all five of them pass
 * through the one method that ends the episode for the recovery machinery.
 *
 * ## What is deliberately not here
 *
 * No counters are persisted, and there is no attempt history. A process death is
 * covered by the durable playback intent, which restores "this station, still
 * wanted" and starts from a fresh budget: a service the system has just relaunched
 * should get the fast phase again, and a counter on disk would outlive the outage
 * it describes.
 */
internal object RecoveryPolicy {

    /** Consecutive fast attempts before the episode moves to its slow phase. */
    const val MAX_FAST_ATTEMPTS = 6

    /** First backoff step; it doubles per attempt up to [MAX_DELAY_MS]. */
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L

    /**
     * How long playback must run uninterrupted for the next failure to count as a
     * fresh problem. Resetting on `STATE_READY` instead would let a stream that
     * plays for two seconds and dies retry forever.
     *
     * Credited once per run - see [Episode.uncreditedRunMs].
     */
    const val STABILITY_RESET_MS = 60_000L

    /**
     * The slow phase's interval.
     *
     * Minutes, not seconds: this is the last resort for a failure where Android
     * still reports connectivity - a 503, or a server that closed a live connection
     * and is not accepting a new one yet - and the connectivity callback already
     * covers the case where the loss really is the network.
     */
    const val SLOW_RETRY_INTERVAL_MS = 5 * 60_000L

    /**
     * What the service remembers about the episode in progress. Four facts, none of
     * which is written to disk.
     */
    data class Episode(
        /** Fast attempts already spent. */
        val fastAttempts: Int = 0,
        /** The fast budget is gone: this episode is in its slow phase. */
        val slow: Boolean = false,
        /**
         * Parked because there was no connectivity. Nothing is armed while this is
         * true; the connectivity callback is the wake-up.
         */
        val awaitingNetwork: Boolean = false,
        /**
         * How long the most recent finished run of playback lasted, waiting to be
         * credited to the next failure.
         *
         * A healthy run is evidence about the *stream*, and it is worth exactly one
         * fresh budget - see [Decision.creditedRunMs] and [onPlaybackRunEnded].
         */
        val uncreditedRunMs: Long = 0L,
    ) {
        /** Nothing spent and nothing pending - what an explicit Play starts from. */
        val untouched: Boolean get() = fastAttempts == 0 && !slow && !awaitingNetwork
    }

    /** What the service must do about one failure. */
    sealed class Plan {
        /**
         * No connectivity: spend nothing. The episode is parked and the connectivity
         * callback resumes it.
         */
        object WaitForNetwork : Plan()

        /** Fast attempt [attempt] (1-based), [delayMs] from now. */
        data class FastRetry(val attempt: Int, val delayMs: Long) : Plan()

        /**
         * The fast budget is spent. Playback is paused and exactly one slow attempt
         * is armed [delayMs] out; this is where the episode stays until it succeeds,
         * the listener acts, or connectivity goes away.
         */
        data class SlowRetry(val delayMs: Long) : Plan()
    }

    /** The episode after an event, and what that event asks for. */
    data class Decision(
        val episode: Episode,
        val plan: Plan,
        /**
         * The run this failure credited, or null when it credited nothing.
         *
         * A credited run means "this is a new problem, not the seventh attempt of an
         * old one", and it is a fact for the log rather than something the service has
         * to infer back out of two episodes.
         */
        val creditedRunMs: Long? = null,
    )

    /**
     * One playback failure, given the episode it lands in.
     *
     * [networkAvailable] is the service's own reading of the connectivity it is about
     * to retry on, and it is the only input this decision takes from outside the
     * episode: everything else - how much of the budget is spent, how healthy the last
     * run was - is already in [Episode].
     */
    fun onFailure(
        episode: Episode,
        networkAvailable: Boolean,
    ): Decision {
        // Evidence about the stream is worth exactly one fresh budget, and it is spent
        // here. Leaving it in place was a real bug - found on an emulator, with a
        // blocked stream and 105 seconds of healthy playback behind it: every failure
        // credited the same run, so the count was reset to zero each time and the fast
        // loop ran at roughly a second an attempt, for ever. `RECOVERY_GAVE_UP` was not
        // unreachable in theory, it was unreachable in practice, and a seconds-level
        // infinite loop is the failure mode the slow phase exists to prevent.
        val credited = episode.uncreditedRunMs
            .takeIf { it >= STABILITY_RESET_MS && !episode.untouched }
        val base = if (credited != null) {
            episode.copy(fastAttempts = 0, slow = false, uncreditedRunMs = 0L)
        } else {
            episode.copy(uncreditedRunMs = 0L)
        }

        if (!networkAvailable) {
            return Decision(
                episode = base.copy(awaitingNetwork = true),
                plan = Plan.WaitForNetwork,
                creditedRunMs = credited,
            )
        }

        if (base.slow || base.fastAttempts >= MAX_FAST_ATTEMPTS) {
            return Decision(
                episode = base.copy(slow = true, awaitingNetwork = false),
                plan = Plan.SlowRetry(SLOW_RETRY_INTERVAL_MS),
                creditedRunMs = credited,
            )
        }

        val index = base.fastAttempts
        return Decision(
            episode = base.copy(fastAttempts = index + 1, awaitingNetwork = false),
            plan = Plan.FastRetry(
                attempt = index + 1,
                delayMs = StreamErrorPolicy.backoffDelayMs(index, BASE_DELAY_MS, MAX_DELAY_MS),
            ),
            creditedRunMs = credited,
        )
    }

    /**
     * Playback stopped after running for [runMs]. Whether that is evidence of a healthy
     * stream is decided by the next failure, which credits it once and consumes it.
     */
    fun onPlaybackRunEnded(episode: Episode, runMs: Long): Episode =
        episode.copy(uncreditedRunMs = runMs)

    /** An explicit Play, a stream switch, or a system restore: nothing spent. */
    fun onUserWantsPlayback(): Episode = Episode()

    /**
     * Pause, Stop, a sleep-timer expiry, headphones out, audio focus lost. The
     * episode is over, and nothing stays armed behind the listener's back.
     */
    fun onIntentEnded(): Episode = Episode()
}
