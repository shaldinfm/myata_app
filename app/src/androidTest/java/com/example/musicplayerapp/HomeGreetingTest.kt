package com.example.musicplayerapp

import android.content.Context
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityStore
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * The HOME header greets the listener by name, on the real screen.
 *
 * `HomeGreetingTest` in `src/test` owns the decision - which states are named, how a
 * blank name falls back, whose session counts. None of that is repeated here. What
 * this suite adds is the half a unit test cannot reach: that the decision is actually
 * bound to `R.id.home_greeting`, and that it is **re-taken on every resume**, which
 * is the property the live G-A7 follow-up is really about. A greeting computed once
 * at inflation would pass every unit test and still show the previous listener's name
 * after a logout.
 *
 * Fake auth, real everything else - the same seam `ProfileAuthenticatedTest` uses, so
 * nothing here can reach the live project or create a row in `auth.users`.
 */
@RunWith(AndroidJUnit4::class)
class HomeGreetingTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var auth: FakeEmailAuthApi

    private val denis = "22222222-2222-4222-8222-222222222222"
    private val anna = "33333333-3333-4333-8333-333333333333"

    @get:Rule
    val timeout: Timeout = Timeout.builder()
        .withTimeout(90, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build()

    @Before
    fun open() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }

        auth = FakeEmailAuthApi().also { it.uid = denis }
        EmailAuthBackend.overrideForInstrumentation { auth }
        IdentityStore.clearForTest(context)
    }

    @After
    fun close() {
        auth.release()
        IdentityStore.clearForTest(context)
        TestIsolation.restoreBackends()
    }

    // ==================== the case the fix exists for ====================

    @Test
    fun a_registered_listener_is_greeted_by_name() {
        signedInAs(denis, "Денис")

        withMainActivity { scenario ->
            scenario.awaitGreeting(named("Денис"))
        }
    }

    // ==================== the fallbacks ====================

    @Test
    fun an_account_with_no_name_gets_the_plain_greeting() {
        signedInAs(denis, "   ")

        withMainActivity { scenario ->
            scenario.awaitGreeting(plain())
        }
    }

    @Test
    fun a_guest_gets_the_plain_greeting() {
        // No identity at all, which is every install that has never signed in - and
        // the state this screen spends most of its life in.
        withMainActivity { scenario ->
            scenario.awaitGreeting(plain())
        }
    }

    @Test
    fun a_registered_install_with_no_session_gets_the_plain_greeting() {
        IdentityStore.markRegistered(context, denis)
        auth.session = null
        auth.accountName = "Денис"

        withMainActivity { scenario ->
            scenario.awaitGreeting(plain())
        }
    }

    // ==================== the property a unit test cannot reach ====================

    /**
     * The reason the greeting is drawn from `onResume` rather than once at inflation.
     *
     * Sign-in, registration, a logout and a switch to another account all end with
     * HOME resumed, and not one of them tells HOME anything. So the header is re-asked
     * on every resume, and this walks the sequence that would expose a cached one:
     * a name, then a different person's name, then nobody's.
     */
    @Test
    fun the_greeting_survives_neither_a_switch_nor_a_logout() {
        signedInAs(denis, "Денис")

        withMainActivity { scenario ->
            scenario.awaitGreeting(named("Денис"))

            // Somebody else signs in on this device.
            signedInAs(anna, "Анна")
            scenario.resume()
            scenario.awaitGreeting(named("Анна"))

            // And then signs out. The stored state and the session both go, which is
            // what a real logout leaves behind.
            IdentityStore.signOut(context)
            auth.session = null
            scenario.resume()
            scenario.awaitGreeting(plain())
        }
    }

    // ==================== helpers ====================

    private fun signedInAs(uid: String, name: String?) {
        IdentityStore.clearForTest(context)
        IdentityStore.markRegistered(context, uid)
        auth.uid = uid
        auth.session = uid
        auth.accountName = name
        auth.accountEmail = "name@example.com"
    }

    private fun plain() = context.getString(R.string.home_greeting)

    private fun named(name: String) = context.getString(R.string.home_greeting_named, name)

    /** A STARTED/RESUMED round trip, which is what returning to HOME really is. */
    private fun ActivityScenario<MainActivity>.resume() {
        moveToState(Lifecycle.State.STARTED)
        moveToState(Lifecycle.State.RESUMED)
    }

    /**
     * Waits for the header to settle on [expected].
     *
     * Polled rather than read once. The greeting is resolved off the main thread -
     * both reads are local, so it lands almost immediately, but "almost" is not
     * something a test on the API 24 image should assume. `onActivity` already waits
     * for the main looper to go idle; this waits for the value that arrives on it.
     */
    private fun ActivityScenario<MainActivity>.awaitGreeting(expected: String) {
        val deadline = System.currentTimeMillis() + 10_000
        var seen = "<never read>"

        while (System.currentTimeMillis() < deadline) {
            onActivity { activity ->
                seen = activity.findViewById<TextView>(R.id.home_greeting)?.text?.toString()
                    ?: "<no greeting view>"
            }
            if (seen == expected) return
            Thread.sleep(50)
        }

        assertEquals("HOME greeting never settled", expected, seen)
    }
}
