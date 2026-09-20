package com.example.musicplayerapp

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The phone app is fixed in portrait, and the TV app is not.
 *
 * ## Why this reads the manifest rather than asking the platform
 *
 * The behaviour being pinned is one attribute in one file, and the platform's
 * answer to it is a device-only value: `PackageManager.getActivityInfo(...)
 * .screenOrientation`. An instrumentation test could ask that, but this suite's
 * CI gate is `testDebugUnitTest` (see `.github/workflows/android-ci.yml`), which
 * runs no device - so a device-only assertion would be a rule nothing checks on
 * the way in. Reading `app/src/main/AndroidManifest.xml` is checkable on the JVM
 * and fails at the same moment, in the same run, as a broken build.
 *
 * What that choice gives up is the merged manifest: a library activity that
 * declared an orientation would not appear here. That is a real gap and it is
 * accepted, because the declaration under test is this app's own launcher and the
 * failure it prevents - HOME drawn as a 390dp portrait column on a 640dp-wide
 * viewport - is caused by this file, by whoever edits it next.
 *
 * ## The rule, in full
 *
 * 1. `MainActivity` is `portrait`. It is the only launcher activity on a phone,
 *    so this fixes the whole mobile app.
 * 2. `TvMainActivity` carries **no** orientation attribute, and neither does
 *    anything else. A television is landscape, and a second orientation
 *    declaration in this app would need the owner's GO - not a quiet addition
 *    next to this one.
 * 3. Nothing calls `setRequestedOrientation` in production source. The manifest
 *    applies before the first frame and to every entry path (launcher,
 *    `ACTION_PLAY`, a notification tap); a runtime call does neither, so the two
 *    appearing together would mean the manifest half is not carrying its weight.
 *
 * Runs on the JVM with no device, so it is part of what CI already gates on.
 */
class MobileOrientationLockTest {

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        /** The attribute as the platform spells it, and the one value allowed. */
        const val PORTRAIT = "portrait"

        /** `android:name` of the two entry points this test names. */
        const val MAIN_ACTIVITY = ".MainActivity"
        const val TV_ACTIVITY = ".TvMainActivity"
    }

    @Test
    fun `the phone entry point is locked to portrait`() {
        val activity = activityNamed(MAIN_ACTIVITY)
        assertEquals(
            "MainActivity must declare android:screenOrientation=\"portrait\" - " +
                "the mobile app has a portrait HOME composition and no landscape one. " +
                "See the note on the attribute in AndroidManifest.xml.",
            PORTRAIT,
            orientation(activity),
        )
    }

    @Test
    fun `tv is not locked to portrait`() {
        val activity = activityNamed(TV_ACTIVITY)
        assertTrue(
            "TvMainActivity has picked up android:screenOrientation=" +
                "\"${orientation(activity)}\". A television is a landscape display: " +
                "no orientation attribute belongs on it, and changing TV behaviour " +
                "needs the owner's GO.",
            orientation(activity).isEmpty(),
        )
    }

    @Test
    fun `portrait is the only orientation declared anywhere in the manifest`() {
        val declared = activities()
            .filter { orientation(it).isNotEmpty() }
            .map { "${name(it)}=${orientation(it)}" }

        // Every declaration, not just the two named above - so a landscape lock
        // added to a third activity fails here rather than shipping.
        assertEquals(
            "exactly one activity may declare an orientation, and it must be " +
                "MainActivity in portrait",
            listOf("$MAIN_ACTIVITY=$PORTRAIT"),
            declared,
        )
    }

    /**
     * The manifest is the mechanism, so no source call may be doing the same job.
     *
     * A runtime `setRequestedOrientation` would be a second, weaker mechanism:
     * it lands after the first frame, and it only covers the entry paths somebody
     * remembered to put it on.
     */
    @Test
    fun `production source never sets an orientation at runtime`() {
        val offenders = productionSources()
            .filter { it.readText().contains("setRequestedOrientation(") }
            .map { it.path }

        assertTrue(
            "setRequestedOrientation is a runtime orientation hack. The mobile lock " +
                "is the manifest attribute on MainActivity - see AndroidManifest.xml - " +
                "and TV must not be touched at all. Found in:\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }

    /**
     * The parse is only worth anything if it read the real manifest.
     *
     * A wrong working directory, or a filter that matched nothing, would make the
     * assertions above pass by finding no activities at all to disagree with. So
     * this proves the document is the mobile manifest by looking for the things
     * that are certainly in it: both entry points, the two launch filters that
     * make them entry points, and the playback service.
     */
    @Test
    fun `the parse is actually reading the mobile manifest`() {
        val document = manifest()
        assertEquals(
            "the file parsed is not an Android manifest",
            "manifest",
            document.documentElement.tagName,
        )

        val names = activities().map { name(it) }
        assertTrue(
            "no activities found in ${manifestFile().path} - the path is wrong, so " +
                "every other assertion in this file is vacuous",
            names.containsAll(listOf(MAIN_ACTIVITY, TV_ACTIVITY)),
        )

        assertTrue(
            "MainActivity has lost its LAUNCHER filter, so it is no longer the " +
                "activity the portrait lock is protecting",
            categories(activityNamed(MAIN_ACTIVITY)).contains("android.intent.category.LAUNCHER"),
        )
        assertTrue(
            "TvMainActivity has lost its LEANBACK_LAUNCHER filter, so this test is " +
                "no longer looking at the TV entry point",
            categories(activityNamed(TV_ACTIVITY)).contains("android.intent.category.LEANBACK_LAUNCHER"),
        )

        val services = document.getElementsByTagName("service")
        assertTrue(
            "the playback service is missing from the parsed manifest",
            (0 until services.length).any {
                name(services.item(it) as Element) == ".service.MediaPlayerService"
            },
        )
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun manifest() =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile())

    /**
     * The manifest the app is built from.
     *
     * Gradle runs unit tests with the module directory as the working directory,
     * but that is a convention rather than a guarantee - so both are tried and the
     * first that exists wins, as in `NoProcessWideNightModeTest`.
     */
    private fun manifestFile(): File =
        listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile }
            ?: error("cannot locate src/main/AndroidManifest.xml from ${File("").absolutePath}")

    private fun activities(): List<Element> {
        val nodes = manifest().getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun activityNamed(activityName: String): Element =
        activities().firstOrNull { name(it) == activityName }
            ?: error("$activityName is not declared in ${manifestFile().path}")

    /** `android:name`. */
    private fun name(activity: Element): String = activity.getAttributeNS(ANDROID, "name")

    /** `android:screenOrientation`, or "" when the activity declares none. */
    private fun orientation(activity: Element): String =
        activity.getAttributeNS(ANDROID, "screenOrientation")

    /** Every `android:name` of the `category` elements under [activity]'s filters. */
    private fun categories(activity: Element): List<String> {
        val nodes = activity.getElementsByTagName("category")
        return (0 until nodes.length).map { name(nodes.item(it) as Element) }
    }

    private fun productionSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("cannot locate src/main/java from ${File("").absolutePath}")

        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
