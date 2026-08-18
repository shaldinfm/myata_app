package com.example.musicplayerapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.data.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TrackKey v1 golden vectors, computed **on the device**.
 *
 * `TrackKeyTest` runs on the desktop JDK, which answers whether the code matches
 * the contract but not whether a phone agrees. It cannot: normalisation leans on
 * `java.text.Normalizer` and `Character.getType`, and both are backed by the
 * Unicode tables of whatever runtime they run on. This app runs on API 24 through
 * API 36, and a key whose value depends on the device would file the same track
 * under different keys on different phones - and, worse, migrate the same
 * collection differently depending on which phone it is migrated on, because
 * `ReactionMigration` calls exactly this code from inside `Migration.migrate`.
 *
 * So the digests here are the same constants as `TrackKeyTest` and
 * `docs/TRACKKEY-V1.md`, asserted against what this device actually computes. A
 * failure means the runtime disagrees, not that the vectors are wrong.
 */
@RunWith(AndroidJUnit4::class)
class TrackKeyDeviceVectorsTest {

    @Test
    fun goldenVectorsAreTheSameOnThisDevice() {
        assertEquals(
            "0e81089c8caec4294651945b2d7253272e4a7009fd6ce66b1a8a92ed24888651",
            TrackKey.of("Depeche Mode", "Enjoy the Silence"),
        )
        assertEquals(
            "5d28c0ac6f793f82d8038406324314ca7a1f1392efccd4c79b0c74051eda0c5b",
            TrackKey.of("\u0417\u0435\u043C\u0444\u0438\u0440\u0430", "\u0418\u0441\u043A\u0430\u043B\u0430"),
        )
        assertEquals(
            "fad7e5957d2e4432e60be40237abfa7f43c68ce37d736c326cc0ac161c06af82",
            TrackKey.of("Calvin Harris feat. Rihanna", "This Is What You Came For (Radio Edit)"),
        )
        assertEquals(
            "bee6dc791fa9680cec9aae24df9f1cccba9f8be897e2da1a65d3b792d86bfbea",
            TrackKey.of("Beyonc\u00E9", "Halo"),
        )
        assertEquals(
            "2fb3e3f65cbbf5141a17d23bd78631799704b6a25fcd5b7725c93f50e77dab2a",
            TrackKey.of("Beyonce", "Halo"),
        )
        assertEquals(
            "b419b6ea145f9e3e5a7ac280e027298fc261a888121601afce1326833baa0d01",
            TrackKey.of("Nick Cave", "Red Right Hand - Live"),
        )
        assertEquals(
            "5ba97feb2d040f45bc5ff161994182d7249cad8e6d091cbb7e7a1cc6e6311539",
            TrackKey.of("AC/DC", "T.N.T."),
        )
    }

    /**
     * The normalisation steps that are actually backed by Unicode tables, checked
     * one at a time so a failure names which one the runtime disagrees about.
     */
    @Test
    fun normalisationStepsBehaveTheSameOnThisDevice() {
        // NFKC: composition, compatibility forms, ligatures.
        assertEquals("beyonc\u00E9", TrackKey.normalize("Beyonce\u0301"))
        assertEquals("abba", TrackKey.normalize("\uFF21\uFF22\uFF22\uFF21"))
        assertEquals("definition", TrackKey.normalize("De\uFB01nition"))

        // Category-driven: Cf removed, whitespace folded, other Cc removed.
        assertEquals("depeche mode", TrackKey.normalize("\uFEFFDe\u200Bpeche\u00A0Mode"))
        assertEquals("depeche mode", TrackKey.normalize("Depeche\u2028Mode"))
        assertEquals("depechemode", TrackKey.normalize("Depeche\u001FMode"))

        // Dash folding and Locale.ROOT casing.
        assertEquals("red right hand - live", TrackKey.normalize("Red Right Hand \u2014 Live"))
        assertEquals("istanbul", TrackKey.normalize("ISTANBUL"))
    }
}
