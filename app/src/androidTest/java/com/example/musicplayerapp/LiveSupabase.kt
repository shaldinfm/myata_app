package com.example.musicplayerapp

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * The opt-in that stands between `connectedDebugAndroidTest` and the live project.
 *
 * A configured `supabase.properties` used to be enough: any suite that found one
 * talked to production, so the ordinary way to run the tests was also the way to
 * write rows into it. Configuration says *which* project exists, not that this run
 * intends to touch it, and those are different questions - so intent now has to be
 * stated separately, on the command line, every time.
 *
 * ```
 * # normal, and incapable of reaching Supabase
 * ./gradlew connectedDebugAndroidTest
 *
 * # deliberate live validation
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.liveSupabase=true
 * ```
 *
 * Two things enforce it, and the second is the one that matters:
 *
 *  1. every test that reaches the network calls [assumeOptedIn] and skips without it;
 *  2. `MyataTestRunner` replaces the whole network boundary before
 *     `MyataApplication.onCreate` runs, so even code no test controls - the startup
 *     drain, a reaction tapped by a UI test - cannot get out.
 *
 * The second exists because the first is not enough on its own. Instrumentation runs
 * in the app's process, and the app schedules a drain at startup; a guard that lives
 * in a `@Before` is already too late for that.
 */
object LiveSupabase {

    /** `-Pandroid.testInstrumentationRunnerArguments.liveSupabase=true` */
    const val ARG = "liveSupabase"

    private const val HOW =
        "live Supabase tests are opt-in. Re-run with " +
            "-Pandroid.testInstrumentationRunnerArguments.liveSupabase=true"

    /** Reads the flag out of a runner argument bundle, before any test exists. */
    fun optedIn(arguments: Bundle?): Boolean =
        arguments?.getString(ARG)?.trim().equals("true", ignoreCase = true)

    /** Reads the flag from the running instrumentation. */
    val isOptedIn: Boolean
        get() = optedIn(InstrumentationRegistry.getArguments())

    /** Skips the calling test unless this run explicitly asked for the live project. */
    fun assumeOptedIn() = assumeTrue(HOW, isOptedIn)
}
