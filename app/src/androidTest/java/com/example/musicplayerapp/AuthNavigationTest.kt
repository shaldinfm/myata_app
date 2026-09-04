package com.example.musicplayerapp

import android.content.Context
import android.view.View
import android.widget.TextView
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
 * Getting to the two auth screens, moving between them, and getting out.
 *
 * Driven through the real activity and the real nav graph, from the real entry
 * point: HOME's 40x40 profile control, then a CTA on profile-guest. Nothing here
 * stubs navigation, because the thing worth asserting is what a listener's taps
 * actually do - including the one nobody plans for, which is changing their mind
 * twice about which screen they wanted.
 *
 * No auth backend is installed. Every test here stops short of submitting anything,
 * so the harness's own refusing backend is the right one and the network boundary is
 * never reached.
 */
@RunWith(AndroidJUnit4::class)
class AuthNavigationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun freshInstall() {
        // API 33+ puts a POST_NOTIFICATIONS dialog over the activity on first launch,
        // and a dialog over the screen makes every click below land on nothing.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
        IdentityStore.clearForTest(context)
    }

    @After
    fun tidy() = IdentityStore.clearForTest(context)

    // ==================== 1 and 2: the guest CTAs are live ====================

    @Test
    fun profile_guest_sign_in_opens_auth_sign_in() {
        scenario { scenario ->
            scenario.openProfile()

            scenario.tap(R.id.profile_sign_in)

            scenario.onActivity { activity ->
                assertEquals(R.id.auth_sign_in, activity.currentDestinationId())
                assertNotNull(
                    "auth-sign-in must be showing",
                    activity.findViewById<View>(R.id.auth_continue_as_guest),
                )
                assertEquals(
                    "the frame has no bottom bar",
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    @Test
    fun profile_guest_create_account_opens_auth_create_account() {
        scenario { scenario ->
            scenario.openProfile()

            scenario.tap(R.id.profile_create_account)

            scenario.onActivity { activity ->
                assertEquals(R.id.auth_create_account, activity.currentDestinationId())
                assertNotNull(
                    "auth-create-account must be showing",
                    activity.findViewById<View>(R.id.auth_name),
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    // ==================== 3: the two screens swap ====================

    @Test
    fun the_two_auth_screens_cross_link_both_ways() {
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)

            scenario.tap(R.id.auth_create_account)
            scenario.onActivity {
                assertEquals(R.id.auth_create_account, it.currentDestinationId())
            }

            scenario.tap(R.id.auth_have_account)
            scenario.onActivity {
                assertEquals(R.id.auth_sign_in, it.currentDestinationId())
            }
        }
    }

    /**
     * Changing your mind repeatedly must not build a pile of screens.
     *
     * Each cross-link pops the screen it came from, so after any number of these the
     * stack still holds one auth entry and Back returns to the profile. Without the
     * `popUpTo` this asserts, Back would walk the listener back through every
     * indecisive tap they ever made.
     */
    @Test
    fun ping_ponging_between_them_leaves_one_entry_on_the_stack() {
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)

            repeat(3) {
                scenario.tap(R.id.auth_create_account)
                scenario.tap(R.id.auth_have_account)
            }

            scenario.onActivity { assertEquals(R.id.auth_sign_in, it.currentDestinationId()) }

            // One Back, and we are at the profile - not three screens deep in it.
            scenario.tap(R.id.auth_back)
            scenario.onActivity { assertEquals(R.id.profile, it.currentDestinationId()) }
        }
    }

    /**
     * `Забыли пароль?` opens recovery, and Back comes back to the form.
     *
     * Deliberately unlike the two cross-links above, which pop what they came from:
     * somebody who decides they meant to register does not want the sign-in form back,
     * but recovery is an errand *inside* signing in and abandoning it should return the
     * half-filled form rather than the profile. The typed address surviving is the
     * observable half of that rule.
     */
    @Test
    fun forgot_password_opens_recovery_and_back_returns_to_the_form() {
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)

            scenario.onActivity {
                it.findViewById<android.widget.EditText>(R.id.auth_email).setText("denis@example.com")
            }
            sync()

            scenario.tap(R.id.auth_forgot_password)
            scenario.onActivity { assertEquals(R.id.auth_recovery, it.currentDestinationId()) }

            scenario.tap(R.id.auth_back)
            scenario.onActivity {
                assertEquals(R.id.auth_sign_in, it.currentDestinationId())
                assertEquals(
                    "the form the listener was filling in must still be there",
                    "denis@example.com",
                    it.findViewById<android.widget.EditText>(R.id.auth_email).text.toString(),
                )
            }

            // And one more Back leaves auth entirely: recovery added no extra entry.
            scenario.tap(R.id.auth_back)
            scenario.onActivity { assertEquals(R.id.profile, it.currentDestinationId()) }
        }
    }

    // ==================== 4: continuing without an account ====================

    @Test
    fun continue_without_account_returns_to_the_profile_and_changes_no_identity() {
        assertEquals(IdentityState.None, IdentityStore.state(context))

        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)

            scenario.tap(R.id.auth_continue_as_guest)

            scenario.onActivity { assertEquals(R.id.profile, it.currentDestinationId()) }
        }

        // Being a guest is the absence of an account, not another kind of one. This
        // control creates nothing, and an install that had never signed in has still
        // never signed in.
        assertEquals(
            "continuing without an account minted an identity",
            IdentityState.None,
            IdentityStore.state(context),
        )
    }

    @Test
    fun an_anonymous_install_is_not_disturbed_by_visiting_the_auth_screens() {
        val uid = "11111111-1111-4111-8111-111111111111"
        IdentityStore.adoptAnonymous(context, uid)

        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_create_account)
            scenario.tap(R.id.auth_have_account)
            scenario.tap(R.id.auth_continue_as_guest)
        }

        assertEquals(IdentityState.Anonymous(uid), IdentityStore.state(context))
    }

    // ==================== 12: back from both screens ====================

    @Test
    fun back_from_sign_in_returns_to_the_profile_and_leaves_the_bar_hidden() {
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)

            scenario.tap(R.id.auth_back)

            scenario.onActivity { activity ->
                assertEquals(R.id.profile, activity.currentDestinationId())
                // profile-guest has no bottom bar either, so it stays hidden - and
                // the entry that restores it is Back from the profile itself.
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }

            scenario.tap(R.id.profile_back)
            scenario.onActivity { activity ->
                assertEquals(R.id.home, activity.currentDestinationId())
                assertEquals(
                    "the bar must come back with HOME",
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    @Test
    fun back_from_create_account_returns_to_the_profile() {
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_create_account)

            scenario.tap(R.id.auth_back)

            scenario.onActivity { assertEquals(R.id.profile, it.currentDestinationId()) }
        }
    }

    @Test
    fun the_system_back_gesture_leaves_by_the_same_route() {
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            sync()

            scenario.onActivity { assertEquals(R.id.profile, it.currentDestinationId()) }
        }
    }

    // ==================== the control G-A4c2 finally wired ====================

    /**
     * `Забыли пароль?` is drawn, and since G-A4c2 it works.
     *
     * Until then this asserted the opposite - drawn, disabled, going nowhere - because
     * the domain primitives existed with no screen to reach them, and a control that
     * says `Забыли пароль?` and does nothing misrepresents the build as surely as
     * removing it would misrepresent the product. Both halves still matter; it is the
     * second one that changed.
     */
    @Test
    fun forgot_password_is_visible_and_opens_recovery() {
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)

            scenario.onActivity { activity ->
                val forgot = activity.findViewById<TextView>(R.id.auth_forgot_password)
                assertEquals(View.VISIBLE, forgot.visibility)
                assertEquals(
                    activity.getString(R.string.auth_forgot_password),
                    forgot.text.toString(),
                )
                assertTrue("it must be reachable now", forgot.isClickable)
                assertTrue(forgot.isEnabled)
            }

            scenario.tap(R.id.auth_forgot_password)
            scenario.onActivity { assertEquals(R.id.auth_recovery, it.currentDestinationId()) }
        }
    }

    // ==================== the frames' own text ====================

    @Test
    fun both_screens_carry_the_strings_their_frames_draw() {
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)

            scenario.onActivity { activity ->
                assertEquals("Вход", activity.text(R.id.auth_title))
                assertEquals("Email", activity.text(R.id.auth_email_label))
                assertEquals("Пароль", activity.text(R.id.auth_password_label))
                assertEquals("Войти", activity.text(R.id.auth_submit_label))
                assertEquals("Создать аккаунт", activity.text(R.id.auth_create_account))
                assertEquals("Продолжить без аккаунта", activity.text(R.id.auth_continue_as_guest))
                assertEquals("Забыли пароль?", activity.text(R.id.auth_forgot_password))
            }

            scenario.tap(R.id.auth_create_account)

            scenario.onActivity { activity ->
                assertEquals("Создать аккаунт", activity.text(R.id.auth_title))
                assertEquals("Имя", activity.text(R.id.auth_name_label))
                assertEquals("Email", activity.text(R.id.auth_email_label))
                assertEquals("Пароль", activity.text(R.id.auth_password_label))
                assertEquals("Минимум 8 символов", activity.text(R.id.auth_password_rule))
                assertEquals("Создать аккаунт", activity.text(R.id.auth_submit_label))
                assertEquals("Уже есть аккаунт? Войти", activity.text(R.id.auth_have_account))
            }
        }
    }

    @Test
    fun no_error_row_is_present_until_something_fails() {
        // The frozen geometry is measured with none of them in the layout, so a row
        // that arrived visible-and-empty would move every measurement below it.
        scenario { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_create_account)

            scenario.onActivity { activity ->
                for (id in listOf(R.id.auth_name_error, R.id.auth_email_error,
                        R.id.auth_password_error, R.id.auth_form_error)) {
                    assertEquals(View.GONE, activity.findViewById<View>(id).visibility)
                }
                assertEquals(
                    "the indicator is only for a request in flight",
                    View.GONE,
                    activity.findViewById<View>(R.id.auth_submit_progress).visibility,
                )
                assertTrue(activity.findViewById<View>(R.id.auth_submit).isEnabled)
            }
        }
    }

    // ==================== helpers ====================

    /** See [withMainActivity]: `use {}` turns the API 24 close timeout into a hard failure. */
    private fun scenario(body: (ActivityScenario<MainActivity>) -> Unit) = withMainActivity(body)

    private fun ActivityScenario<MainActivity>.openProfile() {
        // See [openProfileAndSettle]: `ProfileRoute` proves a session before it
        // navigates, so the destination is not decided in the tap's own frame.
        openProfileAndSettle()
        onActivity { assertEquals(R.id.profile, it.currentDestinationId()) }
    }

    /**
     * One round trip through the main thread, not a wait for it to go idle.
     *
     * Nothing in this class puts an indeterminate indicator on screen, so
     * `waitForIdleSync` would work here - but it is the call that hung `AuthFormTest`
     * on API 24 (an animating ProgressBar never lets the looper idle, and it has no
     * timeout), and leaving one copy of it behind is leaving the trap set.
     */
    private fun sync() = InstrumentationRegistry.getInstrumentation().runOnMainSync { }

    private fun ActivityScenario<MainActivity>.tap(id: Int) {
        onActivity { it.findViewById<View>(id).performClick() }
        sync()
    }

    private fun MainActivity.text(id: Int): String =
        findViewById<TextView>(id).text.toString()

    private fun MainActivity.currentDestinationId(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        return host.navController.currentDestination?.id
    }
}
