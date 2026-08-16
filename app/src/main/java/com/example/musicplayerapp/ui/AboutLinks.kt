package com.example.musicplayerapp.ui

/**
 * Every destination "О нас" opens, in one place so they can be asserted.
 *
 * The screen used to carry these as literals at the call sites. They are here
 * because two of them changed at once and the rest had to be provably left
 * alone - see AboutLinksTest, which locks all of them, not just the two.
 */
object AboutLinks {

    // Section 3: Social Media, in the frozen grid's order.
    const val TELEGRAM = "https://t.me/radiomyata"
    const val SPOTIFY = "https://open.spotify.com/user/31b7rfatuqf7lc76thiudm5bxxuy"
    const val INSTAGRAM = "https://www.instagram.com/radiomyata/"
    const val TIKTOK = "https://www.tiktok.com/@radio_myata"
    const val YOUTUBE = "https://www.youtube.com/channel/UC30ExLCP-enuCrHH2qRRlCw"
    const val THREADS = "https://www.threads.com/@radiomyata"
    const val BOOSTY = "https://boosty.to/myata"

    /**
     * The Я.Музыка tile. It used to point at a band.link redirector; the owner
     * moved it to the radiomyata playlists page. Tile art, geometry, label and
     * grid position are untouched - only where it lands.
     */
    const val YANDEX_MUSIC = "https://links.radiomyata.ru/playlists/"

    /**
     * One-time donation, "Поддержать эфир".
     *
     * YooMoney's own page for the wallet the app has always paid into
     * (receiver 410015757768507). The visitor types the amount there, which is
     * why this replaced the in-app amount form: the retired DonateFragment's
     * custom-amount branch opened this exact URL, so the recipient is the
     * verified one and nothing about the payment changed except who draws the
     * keypad. The page opens on "Перевод другому человеку" with a free "Сколько"
     * field - the 1000/1500/2000 chips beside it are suggestions, not limits.
     */
    const val YOOMONEY_DONATE = "https://yoomoney.ru/to/410015757768507"
}
