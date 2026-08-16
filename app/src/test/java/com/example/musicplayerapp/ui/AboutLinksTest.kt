package com.example.musicplayerapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The destinations "О нас" opens.
 *
 * Two of them moved - Я.Музыка to the playlists page, the one-time donation
 * straight to YooMoney - so the rest are asserted here too. The point of the
 * unchanged half is that a later edit to this list has to be deliberate.
 */
class AboutLinksTest {

    @Test
    fun yandexTileOpensThePlaylistsPage() {
        assertEquals("https://links.radiomyata.ru/playlists/", AboutLinks.YANDEX_MUSIC)
    }

    /**
     * The wallet is the whole point of the change: the app hands payment to
     * YooMoney instead of collecting an amount first, and it has to land on the
     * same receiver DonateFragment posted to (410015757768507).
     */
    @Test
    fun oneTimeDonationGoesToTheVerifiedWallet() {
        assertEquals("https://yoomoney.ru/to/410015757768507", AboutLinks.YOOMONEY_DONATE)
    }

    /**
     * `yoomoney.ru/to/<wallet>` is the form that lets the visitor type an
     * amount. A `quickpay/confirm.xml` URL, or a `sum=` on this one, would put
     * the amount back under the app's control - which is what was removed.
     */
    @Test
    fun oneTimeDonationLeavesTheAmountToYooMoney() {
        assertEquals(
            "amount must not be fixed by the app",
            "https://yoomoney.ru/to/",
            AboutLinks.YOOMONEY_DONATE.substringBeforeLast('/') + "/",
        )
        assertEquals(
            "the direct wallet page takes no query parameters",
            AboutLinks.YOOMONEY_DONATE,
            AboutLinks.YOOMONEY_DONATE.substringBefore('?'),
        )
    }

    @Test
    fun boostyIsUnchanged() {
        assertEquals("https://boosty.to/myata", AboutLinks.BOOSTY)
    }

    @Test
    fun theOtherSocialTilesAreUnchanged() {
        assertEquals("https://t.me/radiomyata", AboutLinks.TELEGRAM)
        assertEquals(
            "https://open.spotify.com/user/31b7rfatuqf7lc76thiudm5bxxuy",
            AboutLinks.SPOTIFY,
        )
        assertEquals("https://www.instagram.com/radiomyata/", AboutLinks.INSTAGRAM)
        assertEquals("https://www.tiktok.com/@radio_myata", AboutLinks.TIKTOK)
        assertEquals(
            "https://www.youtube.com/channel/UC30ExLCP-enuCrHH2qRRlCw",
            AboutLinks.YOUTUBE,
        )
        assertEquals("https://www.threads.com/@radiomyata", AboutLinks.THREADS)
    }
}
