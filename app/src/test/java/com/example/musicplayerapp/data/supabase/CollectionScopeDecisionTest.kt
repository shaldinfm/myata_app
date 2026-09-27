package com.example.musicplayerapp.data.supabase

import com.example.musicplayerapp.data.supabase.CollectionScope.Provenance
import com.example.musicplayerapp.data.supabase.CollectionScope.Scope
import com.example.musicplayerapp.data.supabase.CollectionScope.Settlement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a database without a settled scope is settled. Pre-v5 rows go to an account
 * only when the evidence proves it; otherwise they are parked as unclaimed legacy
 * data - never handed to whoever signs in first.
 */
class CollectionScopeDecisionTest {

    private val y = "22222222-2222-4222-8222-222222222222"
    private val z = "33333333-3333-4333-8333-333333333333"

    private fun settle(
        state: IdentityState,
        migrated: Boolean = true,
        provenance: Provenance = Provenance.IN_PLACE_UPGRADE,
        hasRows: Boolean = true,
        handoff: Boolean = false,
        read: Set<String> = emptySet(),
    ) = CollectionScope.settle(migrated, provenance, hasRows, state, handoff, read)

    @Test
    fun an_in_place_upgrade_proven_as_one_account_keeps_the_rows_as_that_accounts() {
        // The upgraded 3.6.x listener who registered directly: the original P1.
        assertEquals(Settlement(Scope.Account(y), false), settle(IdentityState.Registered(y)))
        assertEquals(Settlement(Scope.Account(y), false), settle(IdentityState.Registered(y), read = setOf(y)))
        assertEquals(Settlement(Scope.Account(y), false), settle(IdentityState.SignedOut(y), read = setOf(y)))
    }

    @Test
    fun an_in_place_upgrade_without_an_account_is_the_devices_own_collection() {
        assertEquals(Settlement(Scope.Device, false), settle(IdentityState.None))
        assertEquals(Settlement(Scope.Device, false), settle(IdentityState.Anonymous(y)))
        assertEquals(Settlement(Scope.Device, false), settle(IdentityState.Registered(y), handoff = true))
    }

    @Test
    fun a_restored_pre_v5_database_is_never_attributed() {
        for (state in listOf(IdentityState.None, IdentityState.Anonymous(y), IdentityState.Registered(y))) {
            val settled = settle(state, provenance = Provenance.FRESH_INSTALL)
            assertEquals("$state", true, settled.parkAsLegacy)
        }
    }

    @Test
    fun an_install_that_read_another_account_cannot_attribute_its_old_rows() {
        assertEquals(
            Settlement(Scope.Account(z), parkAsLegacy = true),
            settle(IdentityState.Registered(z), read = setOf(y, z)),
        )
    }

    @Test
    fun nothing_to_attribute_means_nothing_is_parked() {
        assertEquals(
            Settlement(Scope.Account(y), false),
            settle(IdentityState.Registered(y), provenance = Provenance.FRESH_INSTALL, hasRows = false),
        )
        // A database this build created has no pre-v5 rows to be wrong about.
        assertEquals(Settlement(Scope.Device, false), settle(IdentityState.None, migrated = false))
    }

    @Test
    fun scope_keys_round_trip() {
        for (scope in listOf(Scope.Device, Scope.Account(y), Scope.Legacy)) {
            assertEquals(scope, Scope.parse(scope.key))
        }
    }
}
