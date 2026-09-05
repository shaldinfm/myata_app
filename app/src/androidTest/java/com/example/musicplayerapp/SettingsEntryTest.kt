package com.example.musicplayerapp

import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 40x40 header entry control, and what is now behind it.
 *
 * ## What changed at G1, and what deliberately did not
 *
 * The control opens `settings` rather than a profile. **Its geometry, its host and
 * its identity guarantees are unchanged**, and that is most of what this file
 * asserts: the same 40dp circle, in the same place in the same band, with the same
 * four bottom-bar destinations untouched beside it.
 *
 * The profile is now one tap further in, from `Settings > Аккаунт > Профиль`, so
 * the destination assertions go through that row.
 *
 * ## Why the identity assertions are here rather than only in a unit test
 *
 * The thing worth guarding is not that one fragment avoids one function call - a
 * reviewer can see that. It is that **opening settings and then the profile does
 * not create a row in `auth.users`**, which is a property of the whole path: the
 * entry control, the navigation, both screens, and anything a future edit adds to
 * any of them. G1 lengthened that path, which makes the claim worth more rather
 * than less. So it is asserted against the persisted state after really opening
 * and really going back, which is the only version of it that keeps holding when
 * somebody adds a "just fetch the avatar" call in six months.
 *
 * Nothing here reaches Supabase, and `MyataTestRunner` guarantees that
 * independently: the whole suite runs with the network boundary replaced.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEntryTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 40dp, as Figma draws it, in this device's pixels. */
    private val expectedPx: Int
        get() = (40 * context.resources.displayMetrics.density).toInt()

    @Before
    fun freshInstall() {
        // API 33+ asks for POST_NOTIFICATIONS on first launch and the dialog sits
        // over the activity. Granting it up front keeps these tests about the
        // control rather than about whatever is on top of it.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
        IdentityStore.clearForTest(context)
    }

    @After
    fun tidy() = IdentityStore.clearForTest(context)

    // ==================== the control ====================

    @Test
    fun home_carries_a_forty_by_forty_control_on_the_trailing_edge() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // R.id.settings_entry, not settings_entry_icon: the id on each
                // <include> overrides the included root's at inflation time.
                val entry = activity.findViewById<ImageView>(R.id.settings_entry)
                assertNotNull("HOME must carry the header control", entry)
                assertEquals(View.VISIBLE, entry.visibility)

                // Size, within a pixel of rounding.
                assertTrue(
                    "expected ~$expectedPx px, was ${entry.width}x${entry.height}",
                    kotlin.math.abs(entry.width - expectedPx) <= 2 &&
                        kotlin.math.abs(entry.height - expectedPx) <= 2,
                )

                // Trailing edge: Figma puts the circle at x=334 in a 390 band, so
                // its right edge sits 16dp from the screen's. Asserted as a margin
                // rather than an absolute x, because the screen is not always 390.
                val header = activity.findViewById<View>(R.id.home_header)
                val margin = 16 * context.resources.displayMetrics.density
                val rightGap = header.width - (entry.left + entry.width)
                assertTrue(
                    "control must sit ${margin.toInt()}px from the trailing edge, was $rightGap",
                    kotlin.math.abs(rightGap - margin) <= 2,
                )
            }
        }
    }

    @Test
    fun the_control_is_vertically_centred_in_the_sixty_four_dp_band() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val entry = activity.findViewById<View>(R.id.settings_entry)
                val header = activity.findViewById<View>(R.id.home_header)
                // Figma: y=12 in a 64 band, and 64-12-40 = 12. Equal gaps.
                val top = entry.top
                val bottom = header.height - (entry.top + entry.height)
                assertTrue("top $top vs bottom $bottom", kotlin.math.abs(top - bottom) <= 2)
            }
        }
    }

    /**
     * There is one control, and G1 did not add a second.
     *
     * The frozen HOME and ABOUT US headers each carry a *second* 40x40 node at the
     * same x=334,y=12 which is `visible: false`. Un-hiding it to give settings an
     * entry of its own would put two overlapping circles in the band, which is the
     * wrong turn this asserts against.
     */
    @Test
    fun the_header_carries_exactly_one_trailing_control() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val header = activity.findViewById<android.view.ViewGroup>(R.id.home_header)
                val circles = (0 until header.childCount)
                    .map { header.getChildAt(it) }
                    .filter { it.visibility == View.VISIBLE && it.width == it.height }
                    .filter { kotlin.math.abs(it.width - expectedPx) <= 2 }
                assertEquals("exactly one 40dp control in the band", 1, circles.size)
            }
        }
    }

    // ==================== the four tabs are untouched ====================

    @Test
    fun the_bottom_bar_still_has_exactly_its_four_destinations() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Settings is not a fifth tab, and adding it as one is the obvious
                // wrong turn this asserts against.
                for (id in listOf(R.id.nav_item_home, R.id.nav_item_player, R.id.nav_item_favorites, R.id.nav_item_info)) {
                    assertNotNull("a bottom-bar item went missing", activity.findViewById<View>(id))
                }
                val bar = activity.findViewById<android.view.ViewGroup>(R.id.bottomNavView)
                assertEquals("the bottom bar must still have four items", 4, bar.childCount)
                assertEquals(View.VISIBLE, bar.visibility)
            }
        }
    }

    // ==================== open, and come back ====================

    @Test
    fun tapping_the_control_opens_settings_and_hides_the_bottom_bar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsAndSettle()

            scenario.onActivity { activity ->
                assertEquals(R.id.settings, activity.currentDestinationId())
                assertNotNull(
                    "the settings shell must be showing",
                    activity.findViewById<View>(R.id.settings_row_theme),
                )
                // The frame has no bottom bar.
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    @Test
    fun the_profile_row_opens_the_guest_profile() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openProfileAndSettle()

            scenario.onActivity { activity ->
                assertEquals(R.id.profile, activity.currentDestinationId())
                assertNotNull(
                    "profile-guest must be showing",
                    activity.findViewById<View>(R.id.profile_guest_card),
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    @Test
    fun back_from_settings_returns_to_the_screen_that_opened_it_and_restores_the_bar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsAndSettle()
            scenario.onActivity { it.findViewById<View>(R.id.settings_back).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals("Back must return to HOME", R.id.home, activity.currentDestinationId())
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    /** Back from the profile lands on settings, not on HOME - it is one step, not two. */
    @Test
    fun back_from_the_profile_returns_to_settings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openProfileAndSettle()
            scenario.onActivity { it.findViewById<View>(R.id.profile_back).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(R.id.settings, activity.currentDestinationId())
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    // ==================== identity is untouched ====================

    @Test
    fun opening_the_profile_from_none_leaves_the_install_at_none() {
        assertEquals(IdentityState.None, IdentityStore.state(context))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openProfileAndSettle()
            scenario.onActivity { it.findViewById<View>(R.id.profile_back).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        // The whole point of both screens being inert. Looking at settings or at the
        // profile is not a reason to start existing in somebody's database.
        assertEquals(
            "opening the profile minted an identity",
            IdentityState.None,
            IdentityStore.state(context),
        )
    }

    /**
     * Settings alone must not mint either.
     *
     * G1 put a screen in front of the profile that reads the identity on every
     * resume - `ProfileRoute.destination`, for the row's value and its destination.
     * That is the same call the tap used to make, so the guarantee is the same one;
     * this asserts it against the new screen rather than assuming it carried over.
     */
    @Test
    fun opening_settings_alone_leaves_the_install_at_none() {
        assertEquals(IdentityState.None, IdentityStore.state(context))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsAndSettle()
            scenario.onActivity { it.findViewById<View>(R.id.settings_back).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        assertEquals(
            "opening settings minted an identity",
            IdentityState.None,
            IdentityStore.state(context),
        )
    }

    @Test
    fun the_entry_is_safe_in_anonymous_and_signed_out_states() {
        val uid = "11111111-2222-3333-4444-555555555555"

        IdentityStore.adoptAnonymous(context, uid)
        openAndClose()
        assertEquals(IdentityState.Anonymous(uid), IdentityStore.state(context))

        IdentityStore.signOut(context)
        openAndClose()
        // Signed out must stay signed out - the profile is exactly the screen a
        // listener would open next, and it must not quietly resume anything.
        assertEquals(IdentityState.SignedOut(uid), IdentityStore.state(context))
    }

    private fun openAndClose() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openProfileAndSettle()
            scenario.onActivity { it.findViewById<View>(R.id.profile_back).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private fun MainActivity.currentDestinationId(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        return host.navController.currentDestination?.id
    }
}
