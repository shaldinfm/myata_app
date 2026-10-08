package com.example.musicplayerapp

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.adapters.PlaylistAdapter
import com.example.musicplayerapp.data.MyataPlaylist
import com.example.musicplayerapp.ui.PlaylistLink
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A HOME playlist card opens its playlist when a finger - not `performClick()` - taps it.
 *
 * The 3.6.6 regression was invisible to the existing suite because that suite called
 * `performClick()` on the item root, which skips touch dispatch: the clickable card inside
 * the root swallowed every real tap, and the root's listener never ran. So this taps the
 * card's centre through the instrumentation, the same path a touchscreen takes.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistCardClickTest {

    private val danceMix = "https://links.radiomyata.ru/playlists/?slug=dance-mix"
    private val plain = "https://links.radiomyata.ru/playlists/?slug=chill"

    private val samples = listOf(
        MyataPlaylist(danceMix, Uri.parse("https://example.invalid/dance.jpg")),
        MyataPlaylist(plain, Uri.parse("https://example.invalid/chill.jpg")),
    )

    /**
     * API 33+ puts a POST_NOTIFICATIONS dialog over the activity on first launch, and
     * the injected tap is then refused as aimed at a window this process does not own.
     */
    @Before
    fun grantNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
    }

    @Test
    fun aRealTapOnEachCardDeliversThatCardsPlaylist() {
        val clicked = CopyOnWriteArrayList<MyataPlaylist>()
        var row: RecyclerView? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val created = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
                    adapter = PlaylistAdapter { clicked += it }.also { it.submit(samples) }
                    // On top of everything else in the content frame, so the tap
                    // reaches this row and nothing behind it.
                    elevation = 1000f
                }
                row = created
                activity.addContentView(
                    created,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            for (index in samples.indices) {
                var card: View? = null
                scenario.onActivity {
                    card = row!!.findViewHolderForAdapterPosition(index)
                        ?.itemView?.findViewById(R.id.playlist_card)
                }
                assertNotNull("card $index was not laid out", card)
                tapCentreOf(card!!)
            }
        }

        assertEquals(
            "each tap must deliver exactly its own playlist, slug and all",
            listOf(danceMix, plain),
            clicked.map { it.uri },
        )
    }

    @Test
    fun theIntentATapSendsCarriesTheWholeSlug() {
        for ((link, slug) in listOf(danceMix to "dance-mix", plain to "chill")) {
            val intent = PlaylistLink.viewIntent(link)

            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            // Character for character - nothing between the file and the browser may
            // touch the link.
            assertEquals(link, intent.dataString)
            assertEquals(slug, intent.data?.getQueryParameter("slug"))
        }
    }

    /** One down and one up at the centre of [view], through the real input path. */
    private fun tapCentreOf(view: View) {
        val location = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.getLocationOnScreen(location) }
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        instrumentation.waitForIdleSync()
    }
}
