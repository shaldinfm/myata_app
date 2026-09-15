package com.example.musicplayerapp

import android.view.View
import android.view.ViewGroup
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.ui.settings.ThemeMode
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots of profile-avatar and the profile card around it, for the owner's review.
 *
 * Why a test rather than an adb script, and why opt-in, is the same as
 * [ProfileAuthenticatedCaptureTest]: the screens need a registered account with a live
 * session, and the only safe way to have one is the fake auth seam.
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.captureAvatar=true \
 *   "-Pandroid.testInstrumentationRunnerArguments.class=com.example.musicplayerapp.ProfileAvatarCaptureTest"
 * ```
 *
 * Per theme: the profile with no avatar; the 4x6 picker with nothing ringed; one choice in
 * the top, middle and bottom sections, each shot from that scroll position; the preview
 * holding the last choice; and the profile after saving it.
 */
@RunWith(AndroidJUnit4::class)
class ProfileAvatarCaptureTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi

    private val account = "22222222-2222-4222-8222-222222222222"
    private val SHARED = "/sdcard/Pictures"

    @Before
    fun open() {
        assumeTrue(
            "opt in with -Pandroid.testInstrumentationRunnerArguments.captureAvatar=true",
            InstrumentationRegistry.getArguments().getString("captureAvatar")
                ?.trim().equals("true", ignoreCase = true),
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.overrideForInstrumentation(db)
        auth = FakeEmailAuthApi().also {
            it.uid = account
            it.session = account
            it.accountName = "Денис"
            it.accountEmail = "name@example.com"
        }
        EmailAuthBackend.overrideForInstrumentation { auth }
        ReactionSyncBackend.overrideForInstrumentation({ RecordingSyncApi() }, CountingIdentity(null).asProvider())
        IdentityStore.clearForTest(context)
        IdentityStore.markRegistered(context, account)
        LastSyncStore.recordForTest(context, account, System.currentTimeMillis() - 2 * 60_000L)
    }

    @After
    fun close() {
        if (!::db.isInitialized) return
        ThemeStore.clearForTest(context)
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        db.close()
    }

    @Test
    fun capturesBothThemes() {
        for ((mode, theme) in listOf(ThemeMode.LIGHT to "light", ThemeMode.DARK to "dark")) {
            ThemeStore.write(context, mode)
            auth.accountAvatar = null

            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                openProfileAndSettle()
                await("the account card") {
                    it.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                        it.findViewById<android.widget.TextView>(R.id.profile_account_name)
                            .text.toString() == "Денис"
                }
                settle()
                screencap("$SHARED/g6a-profile-default-$theme.png")

                tap(R.id.profile_row_avatar)
                await("the picker") {
                    it.currentDestinationIdOrNull() == R.id.profile_avatar &&
                        it.findViewById<View>(R.id.avatar_current).visibility == View.VISIBLE
                }
                settle()
                screencap("$SHARED/g6a-picker-default-$theme.png")

                // One choice in each section of the 4x6 grid, each shot at the scroll
                // position that section is seen from: top, middle, bottom.
                for ((key, where) in listOf("myata-06" to "top", "myata-11" to "middle", "myata-23" to "bottom")) {
                    scrollTo(where)
                    on { activity ->
                        val grid = activity.findViewById<ViewGroup>(R.id.avatar_grid)
                        (0 until grid.childCount).map { grid.getChildAt(it) }
                            .first { it.tag == key }.performClick()
                    }
                    settle()
                    screencap("$SHARED/g6a-picker-$where-$theme.png")
                }

                // The preview above the grid, holding the bottom-row choice.
                scrollTo("top")
                settle()
                screencap("$SHARED/g6a-picker-preview-$theme.png")

                scrollTo("bottom")
                tap(R.id.avatar_save)
                await("the profile with the avatar") {
                    it.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                        it.findViewById<View>(R.id.profile_account_avatar_image).visibility == View.VISIBLE
                }
                settle()
                screencap("$SHARED/g6a-profile-saved-$theme.png")

                // The account surfaces around the picker: Settings names the account, HOME
                // shows the saved avatar in its profile control.
                on { it.onBackPressedDispatcher.onBackPressed() }
                await("Settings") { it.currentDestinationIdOrNull() == R.id.settings }
                settle()
                screencap("$SHARED/g6a-settings-name-$theme.png")
                on { it.onBackPressedDispatcher.onBackPressed() }
                await("HOME with the avatar") {
                    it.currentDestinationIdOrNull() == R.id.home && it.findViewById<View>(R.id.profile_entry).tag == "myata-23"
                }
                settle()
                screencap("$SHARED/g6a-home-avatar-$theme.png")
            } finally {
                try {
                    scenario.close()
                } catch (e: Throwable) {
                    android.util.Log.w("AVATARQA", "activity close timed out", e)
                }
            }
        }
        android.util.Log.i("AVATARQA", "screenshots written to $SHARED")
    }

    private fun scrollTo(where: String) {
        on { activity ->
            val scroll = activity.findViewById<android.widget.ScrollView>(R.id.avatar_scroll)
            val range = (scroll.getChildAt(0).height + scroll.paddingTop + scroll.paddingBottom - scroll.height)
                .coerceAtLeast(0)
            scroll.scrollTo(0, when (where) { "top" -> 0; "middle" -> range / 2; else -> range })
        }
    }

    /** Past the fade and the ink correction, so the shot is the resting frame. */
    private fun settle() {
        instrumentation.runOnMainSync { }
        Thread.sleep(700)
    }

    private fun screencap(path: String) {
        instrumentation.uiAutomation
            .executeShellCommand("screencap -p $path")
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }
            .use { it.readBytes() }
    }

    private fun tap(id: Int) {
        on { it.findViewById<View>(id).performClick() }
        instrumentation.runOnMainSync { }
    }

    private fun await(what: String, timeoutMs: Long = 15_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { on { ok = check(it) } }
            if (ok) return
            Thread.sleep(25)
        }
        error("timed out waiting for $what")
    }

    private fun on(block: (MainActivity) -> Unit) {
        instrumentation.runOnMainSync { block(resumedMainActivity()) }
    }
}
