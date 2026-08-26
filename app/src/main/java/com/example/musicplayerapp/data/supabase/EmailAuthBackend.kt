package com.example.musicplayerapp.data.supabase

import android.content.Context

/**
 * The auth boundary behind one seam, so instrumentation can replace it wholesale.
 *
 * The sibling of [ReactionSyncBackend], and it exists for a sharper version of the
 * same reason. That one closes a leak: the app's own startup drain reaching the live
 * project before any test's `@Before` could stop it. This one closes a hole that
 * would otherwise open the moment auth exists at all - a suite that drives
 * registration would create **real `auth.users` rows in production**, and unlike a
 * stray reaction row those cannot be cleaned up with a publishable key.
 *
 * So the default under instrumentation is not "the real thing with care taken". It
 * is a backend that refuses, installed by `MyataTestRunner` before
 * `Application.onCreate`, and lifted only by the explicit `liveSupabase=true` opt-in
 * that `LiveSupabase` documents.
 *
 * **With no override installed - which is every state a shipped build can be in -
 * [api] returns exactly what a caller would have constructed inline.** Nothing in
 * `src/main` calls [overrideForInstrumentation].
 */
object EmailAuthBackend {

    @Volatile
    private var override: ((Context) -> EmailAuthApi)? = null

    /** The auth boundary: the real one unless instrumentation replaced it. */
    fun api(context: Context): EmailAuthApi =
        override?.invoke(context) ?: SupabaseEmailAuthApi(context)

    /**
     * Instrumentation only, called from the test runner before the Application
     * starts. Passing null restores the real backend.
     */
    fun overrideForInstrumentation(api: ((Context) -> EmailAuthApi)?) {
        override = api
    }

    /** Whether a replacement is installed. Lets a test assert its own isolation. */
    val isOverridden: Boolean
        get() = override != null
}
