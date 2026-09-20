package com.example.musicplayerapp.service

import com.example.musicplayerapp.service.RecoveryPolicy.Plan
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect episode, pinned where it can be pinned exactly.
 *
 * The failure this replaced was not a wrong delay: it was a *state* that had no way
 * out. Six fast attempts and then the episode was over for the life of the process,
 * with `wantsPlayback` still true, so a stream that was unavailable for a minute
 * could leave a 24/7 radio silent until somebody touched the phone. Everything below
 * is about that state: what the fast phase is, what the slow phase that follows it
 * does, and what ends either of them.
 *
 * Deterministic on purpose. Reaching the slow phase in a running service takes six
 * consecutive real failures, and a test that staged them would be staging the radio
 * server, not the app - so the machine is pinned here, and
 * `PlaybackNoisyServiceTest` pins the wiring it is driven by.
 */
class RecoveryPolicyTest {

    // ==================== the fast phase is unchanged ====================

    /**
     * The shipped fast phase, exactly: six attempts, 1 + 2 + 4 + 8 + 16 + 30
     * seconds, which is about a minute of trying.
     */
    @Test
    fun `the fast phase is six attempts with the shipped backoff`() {
        var episode = RecoveryPolicy.onUserWantsPlayback()
        val delays = mutableListOf<Long>()

        repeat(RecoveryPolicy.MAX_FAST_ATTEMPTS) { index ->
            val decision = fail(episode)
            val plan = decision.plan as Plan.FastRetry
            assertEquals("attempt number", index + 1, plan.attempt)
            delays += plan.delayMs
            episode = decision.episode
        }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L),
            delays,
        )
        assertEquals(RecoveryPolicy.MAX_FAST_ATTEMPTS, episode.fastAttempts)
        assertFalse("six attempts is still the fast phase", episode.slow)
    }

    @Test
    fun `each failure spends exactly one fast attempt`() {
        var episode = RecoveryPolicy.onUserWantsPlayback()

        repeat(RecoveryPolicy.MAX_FAST_ATTEMPTS) {
            val before = episode.fastAttempts
            val decision = fail(episode)
            assertEquals(
                "one failure must arm one attempt, never two",
                before + 1,
                decision.episode.fastAttempts,
            )
            assertEquals(before + 1, (decision.plan as Plan.FastRetry).attempt)
            episode = decision.episode
        }

        // And the one after that spends nothing at all.
        val dormant = fail(episode)
        assertEquals(RecoveryPolicy.MAX_FAST_ATTEMPTS, dormant.episode.fastAttempts)
        assertTrue(dormant.plan is Plan.SlowRetry)
    }

    // ==================== the slow phase ====================

    /**
     * The hole this slice closes. The seventh failure - the one that used to end the
     * episode for good - now moves the episode to its slow phase, and the listener's
     * intent is untouched: nothing here writes `wantsPlayback` false, and the durable
     * record is only ever changed by the service's own user-intent methods.
     */
    @Test
    fun `a spent fast budget enters the slow phase instead of giving up`() {
        val decision = fail(spentFastBudget())

        assertEquals(
            Plan.SlowRetry(RecoveryPolicy.SLOW_RETRY_INTERVAL_MS),
            decision.plan,
        )
        assertTrue(decision.episode.slow)
        assertEquals("nothing was credited on the way", null, decision.creditedRunMs)
    }

    @Test
    fun `the slow interval is minutes, not seconds`() {
        val plan = fail(spentFastBudget()).plan as Plan.SlowRetry
        assertTrue(
            "a seconds-level loop is the failure mode; ${plan.delayMs}ms is not a slow retry",
            plan.delayMs >= 3 * 60_000L,
        )
    }

    /**
     * The whole point of the slow phase, in numbers: eight hours of a server that
     * never answers costs ninety-six requests, and never returns to the fast phase
     * on the way. Twelve an hour is not background polling; a loop would be.
     */
    @Test
    fun `a server that is down for eight hours costs a handful of requests`() {
        var episode = spentFastBudget()
        var slowAttempts = 0
        var elapsedMs = 0L

        while (elapsedMs < 8 * 60 * 60_000L) {
            val decision = fail(episode)
            val plan = decision.plan as Plan.SlowRetry
            elapsedMs += plan.delayMs
            slowAttempts++
            episode = decision.episode
        }

        assertEquals(
            "5 minutes apart is 12 attempts an hour, 96 in eight",
            8 * 60 / 5,
            slowAttempts,
        )
    }

    @Test
    fun `the slow phase never falls back into the fast phase on its own`() {
        var episode = spentFastBudget()
        repeat(20) {
            val decision = fail(episode)
            assertTrue("$decision", decision.plan is Plan.SlowRetry)
            assertTrue(decision.episode.slow)
            episode = decision.episode
        }
    }

    // ==================== connectivity ====================

    /**
     * An episode that is out of connectivity spends nothing and arms nothing: a timer
     * cannot know when the network comes back, so the service parks it and the
     * connectivity callback resumes it. Missing that would burn the whole fast budget
     * on attempts that could not possibly have succeeded.
     */
    @Test
    fun `a failure with no connectivity parks instead of spending the budget`() {
        val parked = fail(RecoveryPolicy.onUserWantsPlayback(), networkAvailable = false)

        assertEquals(Plan.WaitForNetwork, parked.plan)
        assertTrue(parked.episode.awaitingNetwork)
        assertEquals("nothing was spent", 0, parked.episode.fastAttempts)
        assertFalse(parked.episode.slow)

        // And when connectivity returns, the episode resumes where it was: the first
        // attempt, not the second.
        val resumed = fail(parked.episode)
        assertEquals(Plan.FastRetry(1, 1_000L), resumed.plan)
        assertFalse(resumed.episode.awaitingNetwork)
    }

    /**
     * The slow phase parks the same way, and - this is the part worth pinning - comes
     * back into the slow phase rather than into a fresh fast budget. A network that
     * was down for a while is not a reason to hammer the server on the way back.
     */
    @Test
    fun `a parked slow phase resumes slow`() {
        val parked = fail(dormant(), networkAvailable = false)
        assertEquals(Plan.WaitForNetwork, parked.plan)
        assertTrue(parked.episode.awaitingNetwork)
        assertTrue("parking must not lose the phase", parked.episode.slow)

        val resumed = fail(parked.episode)
        assertEquals(Plan.SlowRetry(RecoveryPolicy.SLOW_RETRY_INTERVAL_MS), resumed.plan)
    }

    /**
     * Only a parked episode answers a connectivity event.
     *
     * The service's callback acts on [Episode.awaitingNetwork] and on nothing else, so
     * this is the pin for "plugging the headphones back in, or the network coming back,
     * does not start the radio by itself": an episode the listener ended is not parked,
     * and one that is merely dormant has its own armed attempt rather than a network
     * wake-up. A parked episode does answer it - that is the whole reason a loss of
     * connectivity arms nothing.
     */
    @Test
    fun `only a parked episode answers a connectivity event`() {
        assertFalse(
            "an episode the listener ended must not be woken by connectivity",
            RecoveryPolicy.onIntentEnded().awaitingNetwork,
        )
        assertFalse(
            "an explicit play starts unparked",
            RecoveryPolicy.onUserWantsPlayback().awaitingNetwork,
        )
        assertTrue(
            "a failure with no connectivity is what parks an episode",
            fail(RecoveryPolicy.Episode(), networkAvailable = false).episode.awaitingNetwork,
        )
    }

    // ==================== what ends an episode ====================

    /**
     * Pause, Stop, a sleep-timer expiry, headphones out and audio focus lost all
     * arrive at the service as this one call, which is why it is the only cancellation
     * there has to be. What it has to leave behind is nothing at all: no attempts
     * spent, no phase, and nothing the connectivity callback could wake.
     */
    @Test
    fun `ending the intent leaves nothing armed`() {
        val ended = RecoveryPolicy.onIntentEnded()

        assertTrue(ended.untouched)
        assertFalse(ended.slow)
        assertFalse(ended.awaitingNetwork)
        assertEquals(0, ended.fastAttempts)

        // A later failure is a new problem, not the seventh attempt of the old one.
        assertEquals(Plan.FastRetry(1, 1_000L), fail(ended).plan)
    }

    @Test
    fun `an explicit play starts from nothing`() {
        assertTrue(RecoveryPolicy.onUserWantsPlayback().untouched)
    }

    // ==================== a stable run is a new problem ====================

    @Test
    fun `a stable run gives the next failure a fresh budget`() {
        val decision = fail(
            ran(RecoveryPolicy.Episode(fastAttempts = 3), runMs = RecoveryPolicy.STABILITY_RESET_MS)
        )

        assertEquals(RecoveryPolicy.STABILITY_RESET_MS, decision.creditedRunMs)
        assertEquals(Plan.FastRetry(1, 1_000L), decision.plan)
        assertFalse("a healthy run ends the slow phase too", decision.episode.slow)
        assertEquals(1, decision.episode.fastAttempts)
    }

    @Test
    fun `a run one millisecond short of stable is worth nothing`() {
        val decision = fail(
            ran(
                RecoveryPolicy.Episode(fastAttempts = RecoveryPolicy.MAX_FAST_ATTEMPTS),
                runMs = RecoveryPolicy.STABILITY_RESET_MS - 1,
            )
        )

        assertEquals(null, decision.creditedRunMs)
        assertTrue(
            "a stream that plays for a moment and dies must not get a new budget",
            decision.plan is Plan.SlowRetry,
        )
    }

    @Test
    fun `a long run with nothing spent is not a reset`() {
        // There is nothing to reset, so nothing is credited and the log line that hangs
        // off it cannot claim a reset that did not happen.
        val decision = fail(ran(RecoveryPolicy.Episode(), runMs = 10 * 60_000L))
        assertEquals(null, decision.creditedRunMs)
        assertEquals(Plan.FastRetry(1, 1_000L), decision.plan)
    }

    /**
     * The bug this one pins was found on an emulator, not in review.
     *
     * With the stream blocked but connectivity still reported, and 105 seconds of
     * healthy playback behind it, the app logged `RECOVERY_RESET reason=stable_playback
     * stableForMs=105459` on *every* failure: the same healthy run was credited again and
     * again, so the count never left 1 and the fast loop ran at roughly a second an
     * attempt for as long as the stream stayed broken. That is the seconds-level infinite
     * loop this whole slice exists to prevent, and it also made `RECOVERY_GAVE_UP`
     * unreachable whenever the radio had been playing for a while.
     *
     * So the credit is spent by the failure that takes it: one healthy minute buys one
     * fresh budget, and the outage that follows spends it like any other.
     */
    @Test
    fun `a healthy run is worth one fresh budget, not an endless supply of them`() {
        var episode = ran(RecoveryPolicy.Episode(fastAttempts = 1), runMs = 105_459L)

        val credited = fail(episode)
        assertEquals(105_459L, credited.creditedRunMs)
        assertEquals(Plan.FastRetry(1, 1_000L), credited.plan)
        assertEquals("the run is spent, not kept", 0L, credited.episode.uncreditedRunMs)
        episode = credited.episode

        // From here it is a normal episode: five more fast attempts and then the slow
        // phase, with no further credit - there has been no further healthy run.
        var fastAttempts = 0
        var plan: Plan
        do {
            val decision = fail(episode)
            assertEquals("the same run must not be credited twice", null, decision.creditedRunMs)
            plan = decision.plan
            if (plan is Plan.FastRetry) fastAttempts++
            episode = decision.episode
        } while (plan !is Plan.SlowRetry)

        assertEquals(RecoveryPolicy.MAX_FAST_ATTEMPTS - 1, fastAttempts)
    }

    @Test
    fun `a run that is never followed by a failure is never credited`() {
        // A pause or a stop ends the episode outright, so the run recorded on the way out
        // cannot be handed to a later, unrelated failure.
        val paused = RecoveryPolicy.onIntentEnded()
        val decision = fail(ran(paused, runMs = 10 * 60_000L))
        assertEquals(null, decision.creditedRunMs)
        assertEquals(Plan.FastRetry(1, 1_000L), decision.plan)
    }

    // ==================== process death ====================

    /**
     * Nothing about an episode is durable, and that is a decision rather than an
     * omission: a process death is handled by the durable playback intent, which
     * restores "this station, still wanted" and then starts the fast phase again. A
     * counter on disk would outlive the outage it described and could park a
     * relaunched service straight into the slow phase.
     */
    @Test
    fun `the policy holds no state of its own`() {
        val mutable = RecoveryPolicy::class.java.declaredFields
            .filterNot { Modifier.isFinal(it.modifiers) }
        assertEquals(
            "an episode may only live in the service's own field",
            emptyList<Field>(),
            mutable,
        )
    }

    // ==================== helpers ====================

    private fun fail(
        episode: RecoveryPolicy.Episode,
        networkAvailable: Boolean = true,
    ) = RecoveryPolicy.onFailure(
        episode = episode,
        networkAvailable = networkAvailable,
    )

    /** [episode] after a run of playback that ended after [runMs]. */
    private fun ran(episode: RecoveryPolicy.Episode, runMs: Long) =
        RecoveryPolicy.onPlaybackRunEnded(episode, runMs)

    /** The episode after all six fast attempts have failed. */
    private fun spentFastBudget(): RecoveryPolicy.Episode {
        var episode = RecoveryPolicy.onUserWantsPlayback()
        repeat(RecoveryPolicy.MAX_FAST_ATTEMPTS) { episode = fail(episode).episode }
        return episode
    }

    /** The episode after the failure that spent the budget - i.e. in the slow phase. */
    private fun dormant(): RecoveryPolicy.Episode = fail(spentFastBudget()).episode
}
