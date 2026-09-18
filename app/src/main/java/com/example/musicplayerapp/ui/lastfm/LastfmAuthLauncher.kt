package com.example.musicplayerapp.ui.lastfm

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.annotation.VisibleForTesting

/**
 * Opens Last.fm's authorisation page in the listener's browser.
 *
 * `ACTION_VIEW` with `CATEGORY_BROWSABLE`, the way the ABOUT screen opens its links:
 * the browser, not a WebView inside this app, because the whole point of the
 * desktop flow is that the listener types their Last.fm password into Last.fm and
 * nowhere else. No deep link and no callback come back; the listener returns to
 * the app on their own and the screen's `onResume` finishes the exchange.
 *
 * ## Why this is a seam
 *
 * The URL carries the request token, so a test needs to assert the exact Intent -
 * and on API 24 an `ActivityMonitor` records that a matching activity was started
 * but cannot hand the Intent over (`PlayerMissingFlowsTest` documents this). So the
 * launch goes through [launchOverrideForTest], which instrumentation sets to capture
 * the Intent instead of starting a browser. A separate test with no override and a
 * blocking monitor proves the real path fires `ACTION_VIEW` at `www.last.fm`.
 */
object LastfmAuthLauncher {

    /**
     * Test-only: receives the Intent instead of starting an activity. Null in a
     * shipped app - nothing in `src/main` writes it.
     */
    @VisibleForTesting
    @Volatile
    var launchOverrideForTest: ((Intent) -> Unit)? = null

    /** The Intent for [url]. Pure, so its exact shape is assertable. */
    fun intentFor(url: String): Intent =
        Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * Opens [url]. Throws `ActivityNotFoundException` when nothing can - the caller
     * shows `Не удалось открыть браузер.` and the pending token stays, so
     * `Открыть Last.fm` can try again once a browser exists.
     */
    fun launch(context: Context, url: String) {
        val intent = intentFor(url)
        val override = launchOverrideForTest
        if (override != null) override(intent) else context.startActivity(intent)
    }
}
