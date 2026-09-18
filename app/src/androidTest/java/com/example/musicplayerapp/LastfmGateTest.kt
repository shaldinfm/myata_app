package com.example.musicplayerapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.lastfm.LastfmBackend
import com.example.musicplayerapp.data.lastfm.LastfmConfig
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * The live-traffic gate, the real session store, and the backup rules as the
 * device compiled them.
 */
@RunWith(AndroidJUnit4::class)
class LastfmGateTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clean() {
        LastfmConfig.configuredOverrideForTest = null
        PrefsLastfmSessionStore(context).write(LastfmStoredSession.EMPTY)
    }

    @After
    fun restore() {
        LastfmConfig.configuredOverrideForTest = null
        LiveLastfm.restoreOffline()
        PrefsLastfmSessionStore(context).write(LastfmStoredSession.EMPTY)
    }

    @Test
    fun an_ordinary_run_cannot_reach_Last_fm() {
        assumeFalse("a live run is the one run allowed to reach Last.fm", LiveLastfm.isOptedIn)

        assertTrue("the runner replaced the transport before the app started", LastfmBackend.isOverridden)
        assertSame(OfflineLastfmApi, LastfmBackend.api(context))
    }

    @Test
    fun making_the_row_appear_does_not_open_the_gate() {
        assumeFalse(LiveLastfm.isOptedIn)

        // configuredOverrideForTest is what instrumentation uses to reach the screen.
        // It decides visibility, and must have no say over the transport.
        LastfmConfig.configuredOverrideForTest = true
        assertTrue(LastfmConfig.isConfigured)
        assertSame(OfflineLastfmApi, LastfmBackend.api(context))
    }

    @Test
    fun restoring_after_a_fake_puts_the_offline_transport_back_not_the_real_one() {
        assumeFalse(LiveLastfm.isOptedIn)

        LastfmBackend.overrideForInstrumentation { OfflineLastfmApi }
        LiveLastfm.restoreOffline()

        assertTrue(LastfmBackend.isOverridden)
        assertSame(OfflineLastfmApi, LastfmBackend.api(context))
    }

    @Test
    fun the_store_round_trips_all_four_fields_and_clears_them() {
        val store = PrefsLastfmSessionStore(context)
        val full = LastfmStoredSession(
            sessionKey = "sk-fake", username = "f0ul482",
            pendingToken = "tok-fake", pendingTokenIssuedAt = 1_789_700_000_000L,
        )

        store.write(full)
        // A fresh instance reads the file, not an in-memory copy.
        assertEquals(full, PrefsLastfmSessionStore(context).read())

        store.write(LastfmStoredSession.EMPTY)
        assertEquals(LastfmStoredSession.EMPTY, PrefsLastfmSessionStore(context).read())
        val prefs = context.getSharedPreferences(PrefsLastfmSessionStore.FILE, 0)
        assertTrue("an empty session leaves no keys behind: ${prefs.all.keys}", prefs.all.isEmpty())
    }

    @Test
    fun the_compiled_backup_rules_exclude_only_the_lastfm_files() {
        // The Last.fm session (P3b) and the scrobble queue database with its WAL and
        // SHM (P5) - nothing else, so the rest of the app is backed up as before.
        val lastfmFiles = listOf(
            "sharedpref:lastfm_session.xml",
            "database:lastfm_queue",
            "database:lastfm_queue-wal",
            "database:lastfm_queue-shm",
        )
        // The resources as the build packaged them, read on the device. The API 31+
        // file lists them twice: once for cloud backup, once for device transfer.
        for ((res, times) in listOf(R.xml.lastfm_backup_rules to 1, R.xml.lastfm_data_extraction_rules to 2)) {
            val excludes = mutableListOf<String>()
            var includes = 0
            context.resources.getXml(res).use { p ->
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "exclude" -> excludes += "${p.getAttributeValue(null, "domain")}:" +
                            p.getAttributeValue(null, "path")
                        "include" -> includes++
                    }
                }
            }
            assertEquals("no <include> - it would stop the rest of the app being backed up", 0, includes)
            assertTrue("at least one exclude in $res", excludes.isNotEmpty())
            assertEquals("exactly the Last.fm files in $res", List(times) { lastfmFiles }.flatten(), excludes)
        }
    }
}
