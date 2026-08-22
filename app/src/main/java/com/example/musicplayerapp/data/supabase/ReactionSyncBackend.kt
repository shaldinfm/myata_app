package com.example.musicplayerapp.data.supabase

import android.content.Context

/**
 * The two network-facing collaborators [ReactionSyncWorker] builds, behind one seam.
 *
 * **This changes nothing about how the app behaves.** With no override installed -
 * which is every state a shipped build can be in - [api] and [identity] return
 * exactly what the worker constructed inline before: a [SupabaseReactionSyncApi] and
 * [AnonymousSession.ensureAuthenticatedListener]. Nothing in `src/main` ever calls
 * [overrideForInstrumentation]; there is no setting, no BuildConfig flag and no code
 * path that reaches it outside an instrumentation run.
 *
 * ## Why it exists
 *
 * Instrumentation runs **inside the app's own process**, so `MyataApplication.onCreate`
 * fires before the first test does - and it calls [ReactionSyncScheduler.onAppStart],
 * which schedules a real drain whenever the real database holds a pending outbox row.
 * Any row a previous test left behind was therefore delivered to the live project by
 * the app itself, outside every test's `@Before`, `@After` and skip condition. That is
 * how `ZZ Sync Fixture …` rows reached production while every individual test looked
 * well-behaved.
 *
 * No per-test guard can close that, because the leak happens before any test exists.
 * The gate has to be installed before `Application.onCreate`, which is what
 * `MyataTestRunner` does - and one process-wide seam at the network boundary is the
 * smallest thing that covers every route to it: the startup drain, a reaction tapped
 * by a UI test, and the scheduler suite's own worker runs alike.
 *
 * The seam is deliberately at the *network* boundary and not higher. Everything above
 * it - the config gate, the database, [ReactionSyncEngine], the drain verdicts and the
 * rescheduling they trigger - still runs for real under test, so the scheduling
 * assertions keep their teeth.
 */
object ReactionSyncBackend {

    @Volatile
    private var apiOverride: ((Context) -> ReactionSyncApi)? = null

    @Volatile
    private var identityOverride: (suspend (Context) -> String?)? = null

    /** The PostgREST boundary: the real one unless instrumentation replaced it. */
    fun api(context: Context): ReactionSyncApi =
        apiOverride?.invoke(context) ?: SupabaseReactionSyncApi(context)

    /** The identity boundary: the real one unless instrumentation replaced it. */
    suspend fun identity(context: Context): String? =
        identityOverride?.invoke(context) ?: AnonymousSession.ensureAuthenticatedListener(context)

    /**
     * Instrumentation only, called from the test runner before the Application starts.
     * Passing nulls restores the real backend.
     */
    fun overrideForInstrumentation(
        api: ((Context) -> ReactionSyncApi)?,
        identity: (suspend (Context) -> String?)?,
    ) {
        apiOverride = api
        identityOverride = identity
    }

    /** Whether a replacement is installed. Lets a test assert its own isolation. */
    val isOverridden: Boolean
        get() = apiOverride != null || identityOverride != null
}
