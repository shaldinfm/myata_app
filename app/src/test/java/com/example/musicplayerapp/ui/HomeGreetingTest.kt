package com.example.musicplayerapp.ui

import com.example.musicplayerapp.data.supabase.AccountInfo
import com.example.musicplayerapp.data.supabase.IdentityState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whom HOME greets by name, and - just as much - whom it does not.
 *
 * The live G-A7 run is the reason this exists: the account metadata held
 * `display_name = "Денис"`, the authenticated profile rendered it, and HOME said
 * `Привет!` to the same person on the same device. Everything interesting about the
 * fix is a decision about a string and a state, so all of it is provable here in
 * microseconds rather than on an emulator.
 *
 * The copy itself is not asserted here on purpose. [HomeGreeting] returns a name or
 * null and the caller resolves `home_greeting` / `home_greeting_named` from
 * resources, so this file holds no Russian and a future locale needs no change to it.
 */
class HomeGreetingTest {

    private val account = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"

    private fun info(uid: String = account, name: String?) = AccountInfo(uid, name, "n@example.com")

    /** Runs the decision, recording whether the auth boundary was consulted at all. */
    private class Session(private val info: AccountInfo?) {
        var asked = false
            private set

        fun supplier(): suspend () -> AccountInfo? = {
            asked = true
            info
        }
    }

    private fun greet(state: IdentityState, session: Session): String? =
        runBlocking { HomeGreeting.name(state) { session.supplier()() } }

    // ==================== the case the fix exists for ====================

    @Test
    fun `a registered account with a display name is greeted by it`() {
        val session = Session(info(name = "Денис"))
        assertEquals("Денис", greet(IdentityState.Registered(account), session))
    }

    @Test
    fun `the name is trimmed, because ProfileAccount trims it`() {
        // Not a second trimming rule - the assertion is that this delegates to the
        // one the account card already uses, so the two screens cannot disagree
        // about somebody's name.
        val session = Session(info(name = "  Денис  "))
        assertEquals("Денис", greet(IdentityState.Registered(account), session))
    }

    // ==================== everything that falls back ====================

    @Test
    fun `an account with no usable name falls back`() {
        for (name in listOf(null, "", "   ", "\t\n")) {
            val session = Session(info(name = name))
            assertNull(
                "a name of ${name?.let { "\"$it\"" }} must not be greeted",
                greet(IdentityState.Registered(account), session),
            )
        }
    }

    @Test
    fun `a registered install whose session has not restored falls back`() {
        // REGISTERED on disk is a belief, not a session. A token revoked on another
        // device leaves exactly this state, and greeting it by name would put a name
        // on a header nobody is behind.
        val session = Session(null)
        assertNull(greet(IdentityState.Registered(account), session))
    }

    @Test
    fun `a session belonging to somebody else is not greeted`() {
        // The same disagreement ProfileRoute routes to the guest profile on. This
        // does not reconcile it - a greeting is not a reason to write to the identity
        // store - it simply declines to name anybody.
        val session = Session(info(uid = other, name = "Денис"))
        assertNull(greet(IdentityState.Registered(account), session))
    }

    @Test
    fun `no state short of an account is greeted, and none of them asks the session`() {
        val states = listOf(
            IdentityState.None,
            IdentityState.Anonymous(account),
            IdentityState.SignedOut(account),
            IdentityState.EmailPending(account, "n@example.com"),
            IdentityState.EmailVerified(account),
        )

        for (state in states) {
            val session = Session(info(name = "Денис"))
            assertNull("$state must not be greeted by name", greet(state, session))
            // The stronger half: the auth boundary is not even consulted. A guest
            // install reaching it would be HOME asking who the listener is on every
            // resume, which is the sort of call that grows a mint behind it later.
            assertFalse("$state must not consult the auth boundary", session.asked)
        }
    }

    // ==================== the live symptom, as a sequence ====================

    @Test
    fun `signing out does not leave the previous listener's name behind`() {
        val signedIn = Session(info(name = "Денис"))
        assertEquals("Денис", greet(IdentityState.Registered(account), signedIn))

        // What a logout leaves: the state is SignedOut and the session is gone. Both
        // independently produce the plain greeting, so neither a stale preference nor
        // a stale token can carry the name over.
        assertNull(greet(IdentityState.SignedOut(account), Session(null)))
        assertNull(greet(IdentityState.Registered(account), Session(null)))
    }

    @Test
    fun `switching accounts greets the new listener, not the old one`() {
        assertEquals(
            "Денис",
            greet(IdentityState.Registered(account), Session(info(name = "Денис"))),
        )
        assertEquals(
            "Анна",
            greet(IdentityState.Registered(other), Session(info(uid = other, name = "Анна"))),
        )
    }
}
