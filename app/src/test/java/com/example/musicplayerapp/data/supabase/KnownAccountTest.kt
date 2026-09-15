package com.example.musicplayerapp.data.supabase

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The last verified account, and the only states it may be painted for (G6a). */
class KnownAccountTest {

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"
    private val account = AccountInfo(x, "Денис", "name@example.com", "myata-07")

    @After
    fun reset() = KnownAccount.forget()

    @Test
    fun `painted only for the registered install with the same uid`() {
        KnownAccount.remember(account)
        assertEquals(account, KnownAccount.of(IdentityState.Registered(x)))
        assertNull("another account's install", KnownAccount.of(IdentityState.Registered(y)))
        assertNull("a guest", KnownAccount.of(IdentityState.None))
        assertNull("signed out", KnownAccount.of(IdentityState.SignedOut(x)))
    }

    @Test
    fun `a saved avatar replaces the known one for that account only`() {
        KnownAccount.remember(account)
        KnownAccount.avatarSaved(y, "myata-15")
        assertEquals("myata-07", KnownAccount.of(IdentityState.Registered(x))?.avatarId)
        KnownAccount.avatarSaved(x, "myata-15")
        assertEquals(account.copy(avatarId = "myata-15"), KnownAccount.of(IdentityState.Registered(x)))
    }

    @Test
    fun `a newer verified account replaces the old one, and forget empties it`() {
        KnownAccount.remember(account)
        KnownAccount.remember(account.copy(displayName = "Den"))
        assertEquals("Den", KnownAccount.of(IdentityState.Registered(x))?.displayName)
        KnownAccount.forget()
        assertTrue(KnownAccount.isEmpty)
        assertNull(KnownAccount.of(IdentityState.Registered(x)))
    }

    @Test
    fun `a definitive no-account is scoped to its uid and cleared by a verified account`() {
        KnownAccount.remember(account)
        KnownAccount.markAbsent(x)
        assertNull("absent replaces the known account", KnownAccount.of(IdentityState.Registered(x)))
        assertTrue(KnownAccount.isAbsent(IdentityState.Registered(x)))
        assertTrue("not another uid's", !KnownAccount.isAbsent(IdentityState.Registered(y)))
        assertTrue("not a guest's", !KnownAccount.isAbsent(IdentityState.None))
        KnownAccount.remember(account)
        assertTrue(!KnownAccount.isAbsent(IdentityState.Registered(x)))
        KnownAccount.markAbsent(x)
        KnownAccount.forget()
        assertTrue(KnownAccount.isEmpty)
    }

    @Test
    fun `the startup gate's older answer never overwrites a newer one`() {
        assertTrue(KnownAccount.settleIfUnknown(x, account))
        assertEquals("myata-07", KnownAccount.of(IdentityState.Registered(x))?.avatarId)

        // A refresh landed first with a newer avatar; the gate's queued answer is older.
        KnownAccount.forget()
        KnownAccount.remember(account.copy(avatarId = "myata-11"))
        assertTrue(!KnownAccount.settleIfUnknown(x, account))
        assertEquals("myata-11", KnownAccount.of(IdentityState.Registered(x))?.avatarId)
        assertTrue("nor a no-account", !KnownAccount.settleIfUnknown(x, null))
        assertEquals("myata-11", KnownAccount.of(IdentityState.Registered(x))?.avatarId)

        // Nothing known: a definitive no-account is applied.
        KnownAccount.forget()
        assertTrue(KnownAccount.settleIfUnknown(x, null))
        assertTrue(KnownAccount.isAbsent(IdentityState.Registered(x)))
    }

    @Test
    fun `nothing is known on a fresh process`() {
        assertTrue(KnownAccount.isEmpty)
    }
}
