package com.example.musicplayerapp.service

/**
 * The system-event side of the service's intent surface.
 *
 * ## What this is
 *
 * One action, and it exists for the tests. It is a **debug instrumentation seam**,
 * not a feature: no screen sends it, no notification sends it, and nothing in the
 * app sends it. `PlaybackNoisyServiceTest` is the only caller. It is the same shape
 * as [PlaybackIntentContract], for the same reason.
 *
 * ## Why the seam has to exist
 *
 * Audio route loss is delivered to the app as `android.media.AUDIO_BECOMING_NOISY`,
 * and that broadcast is **protected**: only the system may send it. Both a test
 * process and `adb shell am broadcast` are refused -
 *
 *     java.lang.SecurityException: Permission Denial: not allowed to send broadcast
 *       android.media.AUDIO_BECOMING_NOISY from pid=..., uid=2000
 *
 * (verified on an API 24 and an API 36 emulator, as a test app and as the shell),
 * and an emulator has no Bluetooth A2DP route to disconnect in the first place. So
 * without this seam the one path issue #13 exists for - `playing -> output gone ->
 * audio stops, intent ends, no resurrection` - is unreachable from a suite, and the
 * three cases that matter most (the durable intent becomes false, a restart after it
 * stays silent, and a later Play still works) would be manual-only forever.
 *
 * [ACTION_BECOMING_NOISY] runs what the system's broadcast runs: the player is
 * silenced the way `AudioBecomingNoisyManager` silences it, and the reason that
 * arrival carries is what the service's listener acts on. Nothing else about the
 * path is faked.
 *
 * ## Why it is safe in a shipped build
 *
 *  - **No new component, and no new surface.** It is one more action on the app's
 *    private command channel, [PlaybackCommand], whose records live in this app's
 *    own storage and nowhere else: no other app can put a command on it, and nothing
 *    is added to the manifest - no service, no receiver, no activity, no
 *    `<intent-filter>`, no permission. `SystemPlaybackEventContractTest` asserts the
 *    manifest half.
 *  - **Release refuses it before touching anything.** The service consults
 *    [isSimulationAllowed] as the first statement of the branch, before it reads the
 *    player or writes anything. A release build logs
 *    `SYSTEM_EVENT_SIMULATION_REFUSED` and returns.
 *  - **Even honoured, it adds no capability.** The most it can do is stop playback
 *    and record that the listener no longer wants it - exactly what an unplugged
 *    headset does on a phone today. It cannot start a station, cannot change which
 *    one is selected, and cannot write anything a listener did not already
 *    implicitly allow by unplugging their headphones.
 */
object SystemPlaybackEventContract {

    /** Debug builds only: deliver "the audio output went away" now. */
    const val ACTION_BECOMING_NOISY = "system_playback_becoming_noisy"

    /**
     * Whether this build may honour [ACTION_BECOMING_NOISY].
     *
     * A function rather than a bare `BuildConfig.DEBUG` read at the call site, so the
     * release answer is testable on the JVM without building or installing a release
     * variant - see `SystemPlaybackEventContractTest`. The parameter is the only
     * input: there is no override, no extra, no system property and no caller
     * identity that can turn it on.
     */
    fun isSimulationAllowed(isDebugBuild: Boolean): Boolean = isDebugBuild
}
