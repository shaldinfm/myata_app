package com.example.musicplayerapp.data.supabase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When an account surface asks the server again: always for Profile/Settings, once a minute for HOME. */
class AccountRefreshTest {

    private val uid = "22222222-2222-4222-8222-222222222222"
    private val minute = AccountRefresh.HOME_MIN_INTERVAL_MS

    @Test
    fun `Profile and Settings pass no interval and always refresh`() {
        assertTrue(AccountRefresh.due(lastAt = 1_000, lastUid = uid, uid = uid, now = 1_001, minIntervalMs = 0))
    }

    @Test
    fun `HOME refreshes the first time and then not again within a minute`() {
        assertTrue(AccountRefresh.due(lastAt = 0, lastUid = null, uid = uid, now = 5_000, minIntervalMs = minute))
        assertFalse(AccountRefresh.due(lastAt = 5_000, lastUid = uid, uid = uid, now = 5_000 + minute - 1, minIntervalMs = minute))
        assertTrue(AccountRefresh.due(lastAt = 5_000, lastUid = uid, uid = uid, now = 5_000 + minute, minIntervalMs = minute))
    }

    @Test
    fun `another account is never held back by the previous one's throttle`() {
        assertTrue(AccountRefresh.due(lastAt = 5_000, lastUid = "someone-else", uid = uid, now = 5_001, minIntervalMs = minute))
    }

    @Test
    fun `a clock that went backwards does not suppress refreshing`() {
        assertTrue(AccountRefresh.due(lastAt = 10_000, lastUid = uid, uid = uid, now = 1_000, minIntervalMs = minute))
    }
}
