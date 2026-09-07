package com.example.musicplayerapp

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.report.ReportCategory
import com.example.musicplayerapp.data.report.ReportConfig
import com.example.musicplayerapp.data.report.ReportPayload
import com.example.musicplayerapp.data.report.ReportTransport
import com.example.musicplayerapp.fragments.ReportProblemFragment
import com.example.musicplayerapp.ui.report.ReportProblemViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The form, driven end to end against a transport that never leaves the device.
 *
 * G3 ships before its endpoint exists, and the whole state machine the frozen
 * frames describe - a category gating Send, an in-flight state, a failure that
 * keeps everything the listener typed, a retry, a terminal success - has to be
 * provable without one. [FakeTransport] is that seam; it is the only thing about
 * this suite that is not the shipping code path.
 */
@RunWith(AndroidJUnit4::class)
class ReportProblemFlowTest {

    /**
     * A transport whose answer the test chooses, and which records what it was asked
     * to send.
     *
     * [gate] lets a test hold a send open, which is how `report-sending` and the
     * double-submit guard are observed at all: without it the coroutine resolves
     * inside the same main-thread turn and the in-flight state is never seen.
     *
     * It is a `CompletableDeferred` rather than a latch, and that is not a
     * preference. `viewModelScope` dispatches on `Main.immediate`, so a latch's
     * `await` would **block the main thread** - the first version of this suite did,
     * and the whole ten-second send ran to completion inside the very `performClick`
     * that started it, which made the in-flight state unobservable and the test
     * failed reporting that the success screen was already up. A deferred suspends
     * the coroutine and leaves the looper free, which is what the real transport
     * does by hopping to `Dispatchers.IO`.
     */
    private class FakeTransport : ReportTransport {
        val attempts = AtomicInteger(0)
        val sent = mutableListOf<ReportPayload>()
        @Volatile var answer: ReportTransport.Result = ReportTransport.Result.Sent
        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun send(payload: ReportPayload): ReportTransport.Result {
            attempts.incrementAndGet()
            synchronized(sent) { sent += payload }
            gate?.await()
            return answer
        }
    }

    private lateinit var transport: FakeTransport

    @After
    fun clearEndpointOverride() {
        ReportConfig.endpointOverrideForTest = null
    }

    @Before
    fun grantNotifications() {
        // API 33+ puts a POST_NOTIFICATIONS dialog over the activity on first
        // launch, and a dialog over the screen makes every click below land on
        // nothing. AuthNavigationTest does the same for the same reason.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${instrumentation.targetContext.packageName} " +
                    "android.permission.POST_NOTIFICATIONS"
            ).close()
        }
    }

    // ==================== the category gates Send ====================

    @Test
    fun send_is_inert_until_a_category_is_chosen() {
        onReportScreen {
            // report-empty: nothing preselected, and the button says so - disabled
            // fill, disabled label colour, and a tap that does nothing.
            on { activity ->
                assertFalse(
                    "Send must be inert with no category chosen",
                    activity.findViewById<View>(R.id.report_send).isClickable,
                )
                assertEquals("Отправить", activity.label())
                for (i in 0..4) {
                    assertEquals(
                        "no category may be preselected",
                        View.INVISIBLE, activity.check(i).visibility,
                    )
                }
            }

            tap(R.id.report_send)
            assertEquals("a tap with no category must not post anything", 0, transport.attempts.get())

            // A description alone does not enable it either: the frozen form makes
            // the text optional and the category required, not the other way round.
            type("что-то сломалось")
            on { activity ->
                assertFalse(
                    "a description must not enable Send on its own",
                    activity.findViewById<View>(R.id.report_send).isClickable,
                )
            }
            tap(R.id.report_send)
            assertEquals(0, transport.attempts.get())

            tapCategory(1)
            on { activity ->
                assertTrue(
                    "a category alone must enable Send",
                    activity.findViewById<View>(R.id.report_send).isClickable,
                )
                assertEquals(View.VISIBLE, activity.check(1).visibility)
            }
        }
    }

    @Test
    fun choosing_a_category_is_single_select_and_moves() {
        onReportScreen {
            tapCategory(0)
            on { activity ->
                assertEquals(View.VISIBLE, activity.check(0).visibility)
                for (i in 1..4) assertEquals(View.INVISIBLE, activity.check(i).visibility)
            }

            tapCategory(3)
            on { activity ->
                assertEquals(View.INVISIBLE, activity.check(0).visibility)
                assertEquals(View.VISIBLE, activity.check(3).visibility)
            }

            // Tapping the chosen row again confirms rather than clears: the frozen
            // form has no "no category" state to return to once one is picked, and
            // Send must not vanish under the finger that was reaching for it.
            tapCategory(3)
            on { activity ->
                assertEquals(View.VISIBLE, activity.check(3).visibility)
                assertTrue(activity.findViewById<View>(R.id.report_send).isClickable)
            }
        }
    }

    // ==================== sending, once ====================

    @Test
    fun a_send_in_flight_shows_the_frozen_sending_state_and_refuses_a_second_tap() {
        onReportScreen {
            val gate = CompletableDeferred<Unit>()
            transport.gate = gate

            tapCategory(1)
            type("звук пропал минут через двадцать")
            tap(R.id.report_send)

            on { activity ->
                assertEquals("Отправляем…", activity.label())
                assertFalse(
                    "the button is inert while a request is running",
                    activity.findViewById<View>(R.id.report_send).isClickable,
                )
                assertFalse(
                    "the field stops accepting input while a request is running",
                    activity.findViewById<View>(R.id.report_message).isEnabled,
                )
                assertEquals(
                    "the form stays visible - report-sending is the same frame",
                    View.VISIBLE, activity.findViewById<View>(R.id.report_scroll).visibility,
                )
            }

            // Three more taps, and a category change for good measure.
            repeat(3) { tap(R.id.report_send) }
            tapCategory(4)

            gate.complete(Unit)
            transport.gate = null
            awaitSuccess()

            assertEquals("exactly one report per tap", 1, transport.attempts.get())
            assertEquals(
                "the category must not change under a running request",
                ReportCategory.STOPPED_BY_ITSELF, transport.sent.single().category,
            )
        }
    }

    // ==================== failure keeps everything ====================

    @Test
    fun a_failure_keeps_the_category_and_the_words_and_offers_a_retry() {
        onReportScreen {
            transport.answer = ReportTransport.Result.Failed("offline")

            tapCategory(2)
            type("в наушниках трещит")
            tap(R.id.report_send)
            awaitBanner()

            on { activity ->
                // The frozen note on report-error, asserted: "The chosen category
                // and the typed description are preserved. Nothing is cleared on
                // failure."
                assertEquals(View.VISIBLE, activity.check(2).visibility)
                assertEquals(
                    "в наушниках трещит",
                    activity.findViewById<EditText>(R.id.report_message).text.toString(),
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.report_error_banner).visibility,
                )
                assertEquals(
                    "Не удалось отправить сообщение.",
                    activity.string(R.id.report_error_message),
                )
                assertEquals("Отправить ещё раз", activity.label())
                assertTrue(
                    "retry must be one tap away",
                    activity.findViewById<View>(R.id.report_send).isClickable,
                )
                assertEquals(
                    "a failure is not a success",
                    View.GONE, activity.findViewById<View>(R.id.report_success).visibility,
                )
            }

            // And the retry works, on the same content, without re-choosing anything.
            transport.answer = ReportTransport.Result.Sent
            tap(R.id.report_send)
            awaitSuccess()

            assertEquals(2, transport.attempts.get())
            val second = transport.sent[1]
            assertEquals(ReportCategory.HEADPHONES, second.category)
            assertEquals("в наушниках трещит", second.message)
        }
    }

    @Test
    fun the_banner_clears_when_the_retry_starts_rather_than_when_it_finishes() {
        onReportScreen {
            transport.answer = ReportTransport.Result.Failed("offline")
            tapCategory(0)
            tap(R.id.report_send)
            awaitBanner()

            val gate = CompletableDeferred<Unit>()
            transport.gate = gate
            transport.answer = ReportTransport.Result.Sent
            tap(R.id.report_send)

            on { activity ->
                assertEquals(
                    "a stale error must not sit over a running retry",
                    View.GONE, activity.findViewById<View>(R.id.report_error_banner).visibility,
                )
                assertEquals("Отправляем…", activity.label())
            }
            gate.complete(Unit)
            transport.gate = null
            awaitSuccess()
        }
    }

    // ==================== success is terminal ====================

    @Test
    fun a_delivered_report_shows_the_frozen_success_screen_and_leaves() {
        onReportScreen {
            tapCategory(4)
            tap(R.id.report_send)
            awaitSuccess()

            on { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.report_scroll).visibility)
                assertEquals("Спасибо!", activity.string(R.id.report_success_headline))
                assertEquals("Готово", activity.string(R.id.report_success_done))
            }

            tap(R.id.report_success_done)
            on { activity ->
                assertEquals(
                    "Готово leaves the screen",
                    R.id.settings, activity.currentDestination(),
                )
            }
        }
    }

    // ==================== back and cancel ====================

    @Test
    fun back_from_a_half_filled_form_discards_it_without_asking() {
        onReportScreen {
            tapCategory(1)
            type("наполовину написанное сообщение")

            tap(R.id.report_back)

            on { activity ->
                assertEquals(R.id.settings, activity.currentDestination())
                assertNull(
                    "no confirmation dialog is drawn by any frozen frame",
                    activity.findViewById<View>(R.id.report_send),
                )
            }
            assertEquals("leaving must not post anything", 0, transport.attempts.get())
        }
    }

    @Test
    fun the_system_back_gesture_leaves_by_the_same_route() {
        onReportScreen {
            tapCategory(1)
            on { it.onBackPressedDispatcher.onBackPressed() }
            sync()
            on { assertEquals(R.id.settings, it.currentDestination()) }
            assertEquals(0, transport.attempts.get())
        }
    }

    // ==================== what is actually posted ====================

    /**
     * The payload, observed at the transport boundary rather than reasoned about.
     *
     * `ReportPayloadTest` holds the schema and the forbidden list as a unit test;
     * this holds that the object arriving at the transport in a real run of the real
     * screen is that object, built from what the listener actually did.
     */
    @Test
    fun the_posted_report_is_the_listener_s_choice_and_the_card_s_diagnostics() {
        onReportScreen {
            tapCategory(0)
            type("не запускается с утра")
            tap(R.id.report_send)
            awaitSuccess()

            val payload = transport.sent.single()
            assertEquals(ReportCategory.PLAYBACK_WONT_START, payload.category)
            assertEquals("не запускается с утра", payload.message)

            // The six values on the card are the six on the wire, and they are the
            // ones this device actually has.
            val fields = payload.fields()
            assertEquals(8, fields.size)
            assertNotNull(fields["app_version"])
            assertTrue(fields["android"]!!.contains("API"))
            assertTrue(
                "the stream must be one of the three the app knows",
                fields["stream"] in listOf("MYATA", "GOLD", "XTRA"),
            )
            assertTrue(
                "the network line is a class, never an SSID or an address",
                fields["network"] in listOf("Wi-Fi", "Мобильная сеть", "Другая сеть", "Нет сети"),
            )

            // No error has been recorded in this process, so the honest answer is
            // `нет` rather than a hidden line or an invented code - owner decision D3.
            assertTrue(
                "an unrecorded error must be reported as absent, not omitted",
                fields["last_error"] == "нет" || fields["last_error"]!!.contains("ERROR_CODE"),
            )

            // And the negative half, at the boundary: nothing identifying got in.
            for ((key, value) in fields - setOf("message")) {
                assertFalse("$key must not carry an address", value.contains("@"))
                assertFalse("$key must not carry a url", value.contains("://"))
                assertFalse("$key must not carry a supabase key", value.contains("sb_"))
                assertFalse("$key must not carry a jwt", value.startsWith("eyJ"))
            }
        }
    }

    @Test
    fun the_card_on_screen_is_the_payload_that_leaves() {
        onReportScreen {
            tapCategory(4)

            val drawn = mutableListOf<String>()
            on { activity ->
                for (id in diagnosticLineIds) drawn += activity.string(id)
            }

            tap(R.id.report_send)
            awaitSuccess()

            // Every value posted appears in the line the listener read. Not string
            // equality of the whole line, because the line is "Устройство — X" and
            // the field is "X" - the promise is that nothing is sent that was not
            // shown, and that is containment.
            val posted = transport.sent.single().diagnostics.wire()
            assertEquals(drawn.size, posted.size)
            posted.values.forEachIndexed { i, value ->
                assertTrue(
                    "line ${i + 1} on the card must carry what was posted: " +
                        "drawn=\"${drawn[i]}\" posted=\"$value\"",
                    drawn[i].contains(value),
                )
            }
        }
    }

    // ==================== harness ====================

    private val diagnosticLineIds = listOf(
        R.id.report_diagnostics_line_0, R.id.report_diagnostics_line_1,
        R.id.report_diagnostics_line_2, R.id.report_diagnostics_line_3,
        R.id.report_diagnostics_line_4, R.id.report_diagnostics_line_5,
    )

    /**
     * Opens the report screen through the Settings door and installs the fake.
     *
     * Settings rather than the player because it is one tap from HOME and needs no
     * popup; `ReportEntryPointsTest` is where the two doors are compared.
     */
    private fun onReportScreen(body: () -> Unit) {
        transport = FakeTransport()
        // The endpoint does not exist yet, so every build is the unconfigured one
        // and both entry points are absent in it. This is the door in - see
        // ReportConfig, and ReportEntryPointsTest for the case where it stays shut.
        // Set before the activity launches: SettingsFragment reads it once, in
        // onCreateView, which is the behaviour being relied on rather than worked
        // around.
        ReportConfig.endpointOverrideForTest = "https://example.invalid/report"
        withMainActivity {
            openSettingsAndSettle()
            tap(R.id.settings_row_report_problem)
            awaitDestination(R.id.report_problem)

            // The fragment's own ViewModel, reached the way the fragment reaches it,
            // so the instance the screen renders is the instance under test.
            on { activity ->
                val fragment = activity.reportFragment()
                ViewModelProvider(fragment)[ReportProblemViewModel::class.java].transport = transport
            }

            body()
        }
    }

    private fun tap(id: Int) {
        on { it.findViewById<View>(id).performClick() }
        sync()
    }

    private fun tapCategory(index: Int) = tap(
        listOf(
            R.id.report_category_0, R.id.report_category_1, R.id.report_category_2,
            R.id.report_category_3, R.id.report_category_4,
        )[index]
    )

    private fun type(text: String) {
        on { it.findViewById<EditText>(R.id.report_message).setText(text) }
        sync()
    }

    /**
     * `runOnMainSync`, never `onActivity` or `waitForIdleSync`.
     *
     * Recorded across this repository's suites: on API 24 a screen that is animating
     * or spinning may never give the looper an idle frame, and `onActivity` waits for
     * one. This screen has no indeterminate indicator, but the trap is left set by
     * using the idle-waiting variant anywhere.
     */
    private fun on(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val current = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .lastOrNull() ?: error("no resumed MainActivity")
            block(current)
        }
    }

    private fun sync() = InstrumentationRegistry.getInstrumentation().runOnMainSync { }

    private fun awaitDestination(id: Int, timeoutMs: Long = 10_000) =
        await(timeoutMs, "destination $id") { it.currentDestination() == id }

    private fun awaitSuccess(timeoutMs: Long = 10_000) = await(timeoutMs, "the success screen") {
        it.findViewById<View>(R.id.report_success)?.visibility == View.VISIBLE
    }

    private fun awaitBanner(timeoutMs: Long = 10_000) = await(timeoutMs, "the error banner") {
        it.findViewById<View>(R.id.report_error_banner)?.visibility == View.VISIBLE
    }

    private fun await(timeoutMs: Long, what: String, condition: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var there = false
            on { there = condition(it) }
            if (there) return
            Thread.sleep(25)
        }
        throw AssertionError("never reached $what")
    }

    private fun MainActivity.currentDestination(): Int? = currentDestinationIdOrNull()

    private fun MainActivity.reportFragment(): ReportProblemFragment {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        return host.childFragmentManager.fragments
            .filterIsInstance<ReportProblemFragment>()
            .firstOrNull() ?: error("the report screen is not showing")
    }

    private fun MainActivity.label(): String = string(R.id.report_send_label)

    private fun MainActivity.string(id: Int): String =
        findViewById<TextView>(id).text.toString()

    private fun MainActivity.check(index: Int): View = findViewById(
        listOf(
            R.id.report_category_0_check, R.id.report_category_1_check,
            R.id.report_category_2_check, R.id.report_category_3_check,
            R.id.report_category_4_check,
        )[index]
    )
}
