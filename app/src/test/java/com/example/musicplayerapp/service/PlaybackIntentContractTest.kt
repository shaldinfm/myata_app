package com.example.musicplayerapp.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The debug instrumentation seam, held to what makes it safe.
 *
 * `PlaybackIntentContract.ACTION_RESTORE` is production source that exists for
 * the tests - see that file for why the restart path is otherwise unreachable
 * from a suite. Three properties are what make it acceptable, and all three are
 * checkable here, on the JVM, without building or installing a release variant:
 *
 *  1. the release answer is "no", from a policy with one input and no overrides;
 *  2. it adds no component and no manifest exposure;
 *  3. nothing in the app sends it.
 */
class PlaybackIntentContractTest {

    // ---- 1. the release policy -------------------------------------------------

    @Test
    fun `a release build refuses the restore action`() {
        assertFalse(PlaybackIntentContract.isRestoreAllowed(isDebugBuild = false))
    }

    @Test
    fun `a debug build honours it`() {
        assertTrue(PlaybackIntentContract.isRestoreAllowed(isDebugBuild = true))
    }

    /**
     * The build flag is the only input. There is no extra, no system property, no
     * caller identity and no second argument that can turn it on - which is what
     * makes "release refuses it" a property rather than a code path somebody has
     * to remember to keep taking.
     */
    @Test
    fun `the policy has exactly one input`() {
        val params = PlaybackIntentContract::class.java
            .declaredMethods.single { it.name == "isRestoreAllowed" }
            .parameterTypes
        assertEquals(listOf(Boolean::class.javaPrimitiveType), params.toList())
    }

    // ---- 2. no new exported surface --------------------------------------------

    /**
     * The action is an `ACTION` extra on a start intent for a service that has
     * been declared and exported since it became a `MediaSessionService`. It is
     * not a component, not an `<intent-filter>` action and not a permission, and
     * this is the assertion that keeps it that way.
     */
    @Test
    fun `the action appears nowhere in the manifest`() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "the restore action must never become a manifest action",
            manifest.contains(PlaybackIntentContract.ACTION_RESTORE),
        )
    }

    @Test
    fun `the manifest declares exactly the components it declared before this slice`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file("src/main/AndroidManifest.xml"))
        for ((tag, expected) in listOf(
            "service" to listOf(".service.MediaPlayerService"),
            "activity" to listOf(".MainActivity", ".TvMainActivity"),
            "receiver" to emptyList(),
            "provider" to emptyList(),
        )) {
            val nodes = doc.getElementsByTagName(tag)
            val names = (0 until nodes.length).map {
                (nodes.item(it) as Element).getAttribute("android:name")
            }
            assertEquals("<$tag> declarations", expected, names)
        }
    }

    /**
     * The service's own filter is unchanged: the Media3 session action and the
     * media button, and nothing added beside them.
     */
    @Test
    fun `the service intent filter is unchanged`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file("src/main/AndroidManifest.xml"))
        val service = (0 until doc.getElementsByTagName("service").length)
            .map { doc.getElementsByTagName("service").item(it) as Element }
            .single { it.getAttribute("android:name") == ".service.MediaPlayerService" }
        val actions = service.getElementsByTagName("action")
        val names = (0 until actions.length).map {
            (actions.item(it) as Element).getAttribute("android:name")
        }
        assertEquals(
            listOf(
                "androidx.media3.session.MediaSessionService",
                "android.intent.action.MEDIA_BUTTON",
            ),
            names,
        )
    }

    // ---- 3. nothing in the app sends it ----------------------------------------

    /**
     * The only references are the constant itself and the service branch that
     * refuses or honours it. A UI that learned to send it would make this a
     * feature, and a feature has to be designed rather than inherited from a test.
     */
    @Test
    fun `no production code sends the restore action`() {
        val senders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ACTION_RESTORE") }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            "only the contract and the service that refuses it may mention the action",
            sortedSetOf("MediaPlayerService.kt", "PlaybackIntentContract.kt"),
            senders,
        )
    }

    /** Unit tests run from the module directory; the positive control is that the file exists. */
    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
