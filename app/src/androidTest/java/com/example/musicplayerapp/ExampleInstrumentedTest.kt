package com.example.musicplayerapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the published application id.
 *
 * The Kotlin package and the `namespace` are `com.example.musicplayerapp` - AGP
 * template residue that only names the R class and the source tree. What the
 * installed app is actually called is the `applicationId`,
 * `dlinemedia.radioplayer.myata`, and that is what the stores key updates on:
 * changing it orphans every existing install. The two are unrelated, and this
 * test asserts the one that matters.
 *
 * As written by the template this asserted the namespace instead, so it failed
 * on every device the app has ever run on.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun targetContextCarriesThePublishedApplicationId() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dlinemedia.radioplayer.myata", appContext.packageName)
    }
}
