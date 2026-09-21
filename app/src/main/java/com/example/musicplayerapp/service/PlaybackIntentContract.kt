package com.example.musicplayerapp.service

/**
 * The durable-playback-intent side of the service's intent surface.
 *
 * ## What this is
 *
 * One action, and it exists for the tests. It is a **debug instrumentation
 * seam**, not a feature: no screen sends it, no notification sends it, and
 * nothing in the app sends it. `PlaybackIntentServiceTest` is the only caller.
 *
 * ## Why the seam has to exist at all
 *
 * A process death cannot be staged from a test that lives in the process being
 * killed - the instrumentation dies with it. `START_STICKY` then brings the
 * service back with a **null intent**, and no test can send one of those either.
 * Without [ACTION_RESTORE] the restart path is simply unreachable from a suite,
 * and the cases that matter most - a pause, a stop or a sleep-timer expiry that a
 * restart must **not** undo - would be manual-only forever.
 *
 * [ACTION_RESTORE] runs exactly the method the null intent runs, on a service
 * that really did just come up. Nothing else about the path is faked.
 *
 * ## Why it is safe in a shipped build
 *
 *  - **No new component, and no new surface.** It is one more action on the app's
 *    private command channel, [PlaybackCommand], whose records live in this app's
 *    own storage and nowhere else: no other app can put a command on it, and nothing
 *    is added to the manifest - no service, no receiver, no activity, no
 *    `<intent-filter>`, no permission. `PlaybackIntentContractTest` asserts the
 *    manifest half.
 *  - **Release refuses it before touching anything.** The service consults
 *    [isRestoreAllowed] as the first statement of the branch, before it reads the
 *    store, before it clears `playbackIntentRestored`, before any player call. A
 *    release build logs `PLAYBACK_INTENT_RESTORE_REFUSED` and returns.
 *  - **Even honoured, it adds no capability.** The most it can do is what the
 *    listener's own stored intent already says - the same station, and only if
 *    they had left it wanted. The service's `play`, `switch` and `stop` commands
 *    can do strictly more, and by the same rule every one of them is reachable
 *    only from inside this app.
 */
object PlaybackIntentContract {

    /** Debug builds only: run the `START_STICKY` restore now. */
    const val ACTION_RESTORE = "playback_intent_restore"

    /**
     * Whether this build may honour [ACTION_RESTORE].
     *
     * A function rather than a bare `BuildConfig.DEBUG` read at the call site, so
     * the release answer is testable on the JVM without building or installing a
     * release variant - see `PlaybackIntentContractTest`. The parameter is the
     * only input: there is no override, no extra, no system property and no
     * caller identity that can turn it on.
     */
    fun isRestoreAllowed(isDebugBuild: Boolean): Boolean = isDebugBuild
}
