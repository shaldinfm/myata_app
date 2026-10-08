package com.example.musicplayerapp.data.supabase

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The account surfaces decide "account or guest" from the **stored** session.
 *
 * supabase-kt 3.2.6 reports no live session - `currentUserOrNull()` is null - whenever its
 * status is not `Authenticated`: an access token that expired while Supabase could not be
 * reached (`RefreshFailure`, session kept in storage and retried), a refresh in flight, or
 * the app just back from the background (`Initializing`). The session is still on the
 * device in all three. Deciding on the live read turned each of them into "you are not
 * signed in" an hour after sign-in on a network that cannot reach Supabase - the 3.6.6
 * "thrown out of my account" report.
 *
 * `AuthSessionResilienceTest` makes the same claim on a device; this one runs in CI. It
 * reads the sources because the decision is spread across screens that cannot be built
 * on the JVM, and what it forbids is exactly one thing: these files asking the *live*
 * session who the listener is. Writes as the account (avatar, deletion, pull) still need
 * the live session and live elsewhere.
 */
class AccountSurfaceSessionSourceTest {

    private val surfaces = listOf(
        "app/src/main/java/com/example/musicplayerapp/ui/profile/ProfileRoute.kt",
        "app/src/main/java/com/example/musicplayerapp/ui/profile/ProfileEntry.kt",
        "app/src/main/java/com/example/musicplayerapp/fragments/SettingsFragment.kt",
        "app/src/main/java/com/example/musicplayerapp/fragments/ProfileAuthenticatedFragment.kt",
        "app/src/main/java/com/example/musicplayerapp/data/supabase/StartupAccountGate.kt",
    )

    private val liveReads = listOf(".currentUid()", ".currentAccount()")

    @Test
    fun `account surfaces never decide on the live session`() {
        val findings = surfaces.flatMap { path ->
            repoFile(path).readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*")) return@mapIndexedNotNull null // KDoc
                liveReads.firstOrNull { code.contains(it) }?.let { "$path:${index + 1}: $it" }
            }
        }
        assertTrue(
            "These read the live session, which is null while a token waits for the " +
                "network - use storedSessionUid()/storedAccount():\n" + findings.joinToString("\n"),
            findings.isEmpty(),
        )
    }

    @Test
    fun `account surfaces do read the stored session`() {
        for (path in surfaces) {
            val source = repoFile(path).readText()
            assertTrue(
                "$path no longer reads the stored session at all",
                source.contains("storedSessionUid()") || source.contains("storedAccount()"),
            )
        }
    }

    /** Finds a repository-relative file from wherever the test runner starts. */
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not find $relative from ${File("").absolutePath}")
        error("unreachable")
    }
}
