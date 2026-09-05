package com.example.musicplayerapp.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AppCompatDelegate.setDefaultNightMode` must not appear in production source.
 *
 * ## Why this is a source scan and not a behaviour test
 *
 * The behaviour tests can only observe the process default *after* the code paths
 * they happen to exercise. `setDefaultNightMode` is a static call, so a single one
 * added on a path no test walks - a rarely-reached error branch, a future screen -
 * would change the appearance of `TvMainActivity`, which runs in this same process
 * under an `<application>` theme that is now a DayNight tree, and no runtime
 * assertion in this suite would ever see it.
 *
 * The rule being protected is absolute rather than statistical: the app applies an
 * appearance through **one activity's delegate** and never process-wide. An
 * absolute rule is checkable absolutely, so it is checked that way.
 *
 * Runs on the JVM with no device, so it is part of what CI already gates on
 * (`testDebugUnitTest`).
 */
class NoProcessWideNightModeTest {

    @Test
    fun `production source never calls setDefaultNightMode`() {
        val offenders = productionSources()
            .filter { it.readText().contains("setDefaultNightMode(") }
            .map { it.path }

        assertTrue(
            "AppCompatDelegate.setDefaultNightMode is process-wide and reaches " +
                "TvMainActivity. Use MainActivity's own delegate.localNightMode " +
                "instead - see docs/SETTINGS-APPEARANCE-3.6.6.md. Found in:\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }

    /**
     * The scan is only worth anything if it is reading real files.
     *
     * A wrong working directory would make the sweep above empty and green
     * forever, which is exactly the failure mode `TypographyProbeTest` was in
     * before G1 - an absence check with no positive control. So this asserts the
     * scan can see a string that is certainly there.
     */
    @Test
    fun `the scan is actually reading the production sources`() {
        val sources = productionSources()
        assertTrue("no Kotlin sources found - the scan path is wrong", sources.size > 20)

        assertTrue(
            "the scan cannot see MainActivity's localNightMode assignment, so its " +
                "'no setDefaultNightMode' answer proves nothing",
            sources.any {
                it.name == "MainActivity.kt" && it.readText().contains("delegate.localNightMode")
            },
        )
    }

    private fun productionSources(): List<File> {
        // Gradle runs unit tests with the module directory as the working directory,
        // but that is a convention rather than a guarantee - so both are tried and
        // the first that exists wins.
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("cannot locate src/main/java from ${File("").absolutePath}")

        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
