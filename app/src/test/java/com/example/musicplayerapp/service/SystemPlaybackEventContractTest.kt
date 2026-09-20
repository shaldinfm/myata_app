package com.example.musicplayerapp.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio-route seam, held to what makes it safe.
 *
 * `SystemPlaybackEventContract.ACTION_BECOMING_NOISY` is production source that
 * exists for the tests - see that file for why the path is otherwise unreachable from
 * a suite (the system's broadcast is protected). The same three properties
 * `PlaybackIntentContractTest` checks for the restart seam are what make this
 * acceptable:
 *
 *  1. the release answer is "no", from a policy with one input and no overrides;
 *  2. it adds no component and no manifest exposure;
 *  3. nothing in the app sends it.
 */
class SystemPlaybackEventContractTest {

    // ---- 1. the release policy -------------------------------------------------

    @Test
    fun `a release build refuses the simulation`() {
        assertFalse(SystemPlaybackEventContract.isSimulationAllowed(isDebugBuild = false))
    }

    @Test
    fun `a debug build honours it`() {
        assertTrue(SystemPlaybackEventContract.isSimulationAllowed(isDebugBuild = true))
    }

    @Test
    fun `the policy has exactly one input`() {
        val params = SystemPlaybackEventContract::class.java
            .declaredMethods.single { it.name == "isSimulationAllowed" }
            .parameterTypes
        assertEquals(listOf(Boolean::class.javaPrimitiveType), params.toList())
    }

    // ---- 2. no new exported surface --------------------------------------------

    /**
     * It is an `ACTION` extra on a start intent for the service that has been
     * declared and exported since it became a `MediaSessionService`. Not a component,
     * not an `<intent-filter>` action, not a permission - and this is the assertion
     * that keeps it that way.
     */
    @Test
    fun `the action appears nowhere in the manifest`() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "the simulation action must never become a manifest action",
            manifest.contains(SystemPlaybackEventContract.ACTION_BECOMING_NOISY),
        )
    }

    /**
     * No receiver of the app's own for the system's broadcast, in the manifest or out
     * of it: the listener is ExoPlayer's, switched on with
     * `setHandleAudioBecomingNoisy(true)`, and a second one beside it would be a second
     * thing to keep in step. The manifest half is asserted here; the component count
     * itself (zero receivers, one service, two activities) is pinned by
     * `PlaybackIntentContractTest`.
     */
    @Test
    fun `the app registers no receiver for the system broadcast`() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertEquals(
            "no receiver and no filter for the system's route-loss broadcast",
            0,
            Regex("AUDIO_BECOMING_NOISY").findAll(manifest).count(),
        )
    }

    // ---- 3. nothing in the app sends it ----------------------------------------

    @Test
    fun `no production code sends the simulation action`() {
        val senders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ACTION_BECOMING_NOISY") }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            "only the contract and the service that refuses it may mention the action",
            sortedSetOf("MediaPlayerService.kt", "SystemPlaybackEventContract.kt"),
            senders,
        )
    }

    /** Unit tests run from the module directory; the positive control is that the file exists. */
    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
