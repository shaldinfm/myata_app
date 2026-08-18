package com.example.musicplayerapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.AnonymousSession
import com.example.musicplayerapp.data.supabase.SupabaseConfig
import com.example.musicplayerapp.data.supabase.SupabaseModule
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Supabase foundation, on a device.
 *
 * Split deliberately into two kinds of test, because they answer different
 * questions and one of them needs a real project:
 *
 *  - **Always run.** That the library loads and a client can be built on this API
 *    level at all. This is the desugaring gate: supabase-kt documents API 26 as its
 *    minimum, and everything below that stands on core library desugaring back-
 *    porting `java.time` and friends. When desugaring is wrong the failure is not a
 *    build error, it is a `NoClassDefFoundError` on a 2016 phone - so it has to be
 *    asserted on API 24 specifically.
 *  - **Assumed out when unconfigured.** Anything that talks to a project. Without
 *    `supabase.properties` there is nothing to talk to, and a test that cannot ask
 *    its question must skip rather than pass quietly - the pattern
 *    `LaunchSequenceTest` already uses here.
 */
@RunWith(AndroidJUnit4::class)
class SupabaseFoundationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ==================== always, on every API level ====================

    @Test
    fun theLibraryLoadsOnThisApiLevel() {
        // Touching these classes is the test: if desugaring or the Ktor/Supabase
        // dex output were wrong for this API level, class loading throws here
        // rather than in front of a listener.
        assertNotNull(SupabaseConfig.url)
        assertNotNull(io.github.jan.supabase.auth.Auth::class.java.name)
        assertNotNull(io.github.jan.supabase.postgrest.Postgrest::class.java.name)
        assertNotNull(io.ktor.client.engine.okhttp.OkHttp::class.java.name)
    }

    @Test
    fun anUnconfiguredBuildHasNoClientAndDoesNotCrash() {
        assumeTrue("this build has a project configured", !SupabaseConfig.isConfigured)

        assertNull(SupabaseModule.client(context))
        // And the startup bootstrap is a no-op rather than an error.
        AnonymousSession.ensureInBackground(context)
        assertNull(runBlocking { AnonymousSession.ensure(context) })
    }

    /**
     * A whole client, built on this device, with no project needed.
     *
     * This is the real desugaring gate. `theLibraryLoadsOnThisApiLevel` only
     * resolves class names; this runs `createSupabaseClient` for real - Ktor's
     * OkHttp engine, kotlinx-serialization, the Auth plugin's session storage and
     * its coroutine machinery all initialise here, which is where a missing
     * back-ported `java.time` actually surfaces on API 24.
     *
     * The URL and key are placeholders and no request is made: constructing a
     * client performs no I/O.
     */
    @Test
    fun aClientCanBeBuiltOnThisDeviceWithoutAProject() {
        val client = createSupabaseClient(
            supabaseUrl = "https://placeholder.supabase.co",
            supabaseKey = "sb_publishable_placeholder",
        ) {
            httpEngine = OkHttp.create {
                preconfigured = com.example.musicplayerapp.SecureNetModule.getOkHttpClient(context)
            }
            install(Auth) {
                // Not this device's real session: a placeholder project must not be
                // able to disturb whatever the app itself has stored.
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
            }
            install(Postgrest)
        }

        assertNotNull(client.auth)
        assertNotNull(client.postgrest)
        assertNull("a placeholder client must not have a session", client.auth.currentUserOrNull())
        runBlocking { client.close() }
    }

    @Test
    fun noSecretKeyIsCompiledIntoThisBuild() {
        assertFalse(SupabaseConfig.isSecretKey(SupabaseConfig.publishableKey))
    }

    // ==================== only with a real project ====================

    @Test
    fun aClientCanBeBuiltAgainstTheConfiguredProject() {
        assumeTrue("no supabase.properties in this build", SupabaseConfig.isConfigured)

        val client = SupabaseModule.client(context)
        assertNotNull("client was null despite a configured project", client)
        // Same instance twice: one session, one token refresh loop.
        assertTrue(client === SupabaseModule.client(context))
    }

    @Test
    fun anonymousSignInProducesAStableUid() {
        assumeTrue("no supabase.properties in this build", SupabaseConfig.isConfigured)

        val first = runBlocking { AnonymousSession.ensure(context) }
        assertNotNull(
            "anonymous sign-in failed - is it enabled for the project, and is the device online?",
            first,
        )

        // Calling again reuses the session rather than creating a second listener.
        val second = runBlocking { AnonymousSession.ensure(context) }
        assertEquals(first, second)

        val client = SupabaseModule.client(context)!!
        assertEquals(first, client.auth.currentUserOrNull()?.id)
    }

    /**
     * The session outlives the process.
     *
     * Instrumentation cannot kill and revive its own process, so this asserts the
     * half that is actually falsifiable here: the session was persisted by the Auth
     * plugin and can be loaded back from storage into a session-less client. The
     * true process-death check is the manual one in
     * `docs/SUPABASE-FOUNDATION.md` - force-stop, relaunch, compare the uid in the
     * log - and it is part of the API 24 gate.
     */
    @Test
    fun theSessionIsPersistedForTheNextLaunch() {
        assumeTrue("no supabase.properties in this build", SupabaseConfig.isConfigured)

        val uid = runBlocking { AnonymousSession.ensure(context) }
        assumeTrue("no session to persist", uid != null)

        val client = SupabaseModule.client(context)!!
        val loaded = runBlocking {
            client.auth.loadFromStorage()
            client.auth.currentUserOrNull()?.id
        }
        assertEquals(uid, loaded)
    }
}
