package com.example.musicplayerapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The isolation, asserted rather than assumed.
 *
 * Everything else in the suite relies on `MyataTestRunner` having replaced the
 * network boundary before the app started. That is exactly the kind of guarantee
 * that quietly stops holding - the runner is unregistered in a Gradle edit, a
 * refactor renames the argument, a merge drops the `testInstrumentationRunner` line -
 * and nothing else in the suite would notice, because the symptom is rows appearing
 * in a database no test looks at.
 *
 * These two run in both modes and disagree with each other on purpose: whichever way
 * the suite was invoked, one of them is making a claim that would fail if the gate
 * were not working.
 */
@RunWith(AndroidJUnit4::class)
class LiveSupabaseIsolationTest {

    @Test
    fun a_normal_run_cannot_reach_the_live_project() {
        if (LiveSupabase.isOptedIn) return

        assertTrue(
            "MyataTestRunner did not install the offline backend - this run can write " +
                "to the live Supabase project. Check testInstrumentationRunner in " +
                "app/build.gradle.",
            ReactionSyncBackend.isOverridden,
        )
    }

    @Test
    fun an_opted_in_run_uses_the_real_backend() {
        if (!LiveSupabase.isOptedIn) return

        assertFalse(
            "the offline backend is still installed, so the live validation would " +
                "not actually be validating anything",
            ReactionSyncBackend.isOverridden,
        )
    }
}
