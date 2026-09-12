package com.example.musicplayerapp.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden cases for canonical artwork selection (G5b).
 *
 * The fixtures under `src/test/resources/artwork` are **real iTunes answers**,
 * captured during the G5 recon from the live search endpoint for tracks the
 * station was actually broadcasting. They are the payloads that produced the
 * wrong covers, kept exactly as they arrived, so each case below is the failure
 * the owner reported rather than a reconstruction of it. Nothing here touches the
 * network: the fixture is the provider.
 *
 * Where a case needs a shape the sample did not contain - a karaoke record, a
 * station asking for a live take - the candidates are written out inline, which
 * also keeps those tests readable as statements of the rule.
 */
class ArtworkMatcherGoldenTest {

    private val repository = ArtworkRepository(OkHttpClient())

    /** A captured provider answer, parsed the way the app parses it. */
    private fun fixture(name: String): List<ArtworkCandidate> {
        val json = javaClass.getResourceAsStream("/artwork/$name.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("fixture $name.json is missing")
        val candidates = repository.parseCandidates(json)
        assertTrue("fixture $name parsed to nothing", candidates.isNotEmpty())
        return candidates
    }

    private fun candidate(
        artist: String,
        track: String,
        collection: String,
        collectionArtist: String? = null,
        releaseDate: String? = "2010-01-01",
    ) = ArtworkCandidate(
        trackName = track,
        artistName = artist,
        collectionName = collection,
        collectionArtistName = collectionArtist,
        releaseDate = releaseDate,
        artworkUrl = "https://example.test/${collection.hashCode()}.jpg",
    )

    private fun choose(artist: String, title: String, candidates: List<ArtworkCandidate>) =
        ArtworkMatcher.choose(artist, title, candidates)

    // ============== the reported failures ==============

    /**
     * The headline case. A 2025 remix single called "Maneater (Nala Remix) -
     * Single" scored the old matcher's two boosts and beat the album the track is
     * actually from.
     */
    @Test
    fun `a remix single does not stand in for the original album`() {
        val choice = choose("NELLY FURTADO", "MANEATER", fixture("maneater"))

        assertNotNull(choice)
        assertEquals("Loose", choice!!.candidate.collectionName)
        assertEquals("Maneater", choice.candidate.trackName)
        assertEquals(ArtworkConfidence.HIGH, choice.confidence)
    }

    /** Same shape, and the remix was even credited to a different pairing. */
    @Test
    fun `the Kanye West remix single loses to Infinity On High`() {
        val choice = choose(
            "FALL OUT BOY",
            "THIS AIN'T A SCENE, IT'S AN ARMS RACE",
            fixture("fall_out_boy_arms_race"),
        )

        assertEquals("Infinity On High", choice?.candidate?.collectionName)
    }

    @Test
    fun `a remixes EP loses to the album the track is from`() {
        val choice = choose("JAMIROQUAI", "COSMIC GIRL", fixture("jamiroquai_cosmic_girl"))

        assertNotNull(choice)
        assertTrue(
            "picked a remix: ${choice!!.candidate.trackName} / ${choice.candidate.collectionName}",
            !choice.candidate.trackName.contains("Remix", ignoreCase = true) &&
                !choice.candidate.collectionName.contains("Remix", ignoreCase = true),
        )
    }

    @Test
    fun `a 2020 remix single loses to the original Romeo release`() {
        val choice = choose("BASEMENT JAXX", "ROMEO", fixture("basement_jaxx_romeo"))

        assertNotNull(choice)
        assertTrue(
            "picked a remix: ${choice!!.candidate.collectionName}",
            !choice.candidate.collectionName.contains("Remix", ignoreCase = true),
        )
    }

    /**
     * Cyrillic throughout, and the trap is a New Year's version released as its
     * own single four days before the station's plain title would sort.
     */
    @Test
    fun `a seasonal version does not stand in for the single`() {
        val choice = choose("ИВАН ДОРН", "КРОМЕ ТЕБЯ", fixture("dorn_krome_tebya"))

        assertNotNull(choice)
        assertEquals("Кроме тебя", choice!!.candidate.trackName)
        assertEquals("Кроме тебя - Single", choice.candidate.collectionName)
    }

    /** Every candidate is on a Various Artists compilation, so there is no cover. */
    @Test
    fun `a track that only appears on VA compilations gets no cover`() {
        val choice = choose(
            "ANTIBAZZ VS. DEEP MELANGE",
            "WONDERFUL LIFE",
            fixture("antibazz_wonderful_life"),
        )

        assertNull("a Various Artists compilation is not this track's artwork", choice)
    }

    /** A reissue is the same record; the plain release is still the canonical one. */
    @Test
    fun `a remastered edition loses to the plain album`() {
        val choice = choose("THE CARDIGANS", "ERASE/REWIND", fixture("cardigans_erase_rewind"))

        assertEquals("Gran Turismo", choice?.candidate?.collectionName)
    }

    /**
     * The 2017 re-release is credited to another lead act; the station's own
     * artist made the record in 2007, and that is the release it should show.
     */
    @Test
    fun `an older release by the credited artist beats a later re-release`() {
        val choice = choose(
            "IAN CAREY FT. MICHELLE SHELLERS",
            "KEEP ON RISING",
            fixture("ian_carey_keep_on_rising"),
        )

        assertNotNull(choice)
        assertEquals("Ian Carey", choice!!.candidate.artistName)
        assertTrue("expected the 2007 release, got ${choice.candidate.releaseDate}",
            choice.candidate.releaseDate!!.startsWith("2007"))
    }

    // ============== the version rules ==============

    @Test
    fun `when the station asks for a remix, a remix is what it gets`() {
        val choice = choose("NELLY FURTADO", "MANEATER (NALA REMIX)", fixture("maneater"))

        assertNotNull("a remix was asked for and one exists", choice)
        assertEquals("Maneater (Nala Remix)", choice!!.candidate.trackName)
    }

    @Test
    fun `when the station asks for a live take, the live release is allowed`() {
        val choice = choose("NELLY FURTADO", "MANEATER (LIVE)", fixture("maneater"))

        assertNotNull(choice)
        assertTrue(
            "expected a live release, got ${choice!!.candidate.collectionName}",
            choice.candidate.trackName.contains("Live", ignoreCase = true) ||
                choice.candidate.collectionName.contains("Live", ignoreCase = true),
        )
    }

    @Test
    fun `a remix is not accepted for a station title that asks for a live take`() {
        val candidates = listOf(
            candidate("Some Act", "Song (Bassline Remix)", "Song (Bassline Remix) - Single"),
        )

        assertNull(choose("SOME ACT", "SONG (LIVE)", candidates))
    }

    /**
     * An edit is the same recording trimmed and ships under the same artwork, so
     * it is kept - but only ever behind a plainer spelling of the same title.
     */
    @Test
    fun `a radio edit is accepted, and still loses to the plain title`() {
        val edit = candidate("Una Mas", "I Will Follow You (Radio Edit)", "I Will Follow You")
        val plain = candidate("Una Mas", "I Will Follow You", "I Will Follow You")

        assertEquals("I Will Follow You (Radio Edit)", choose("UNA MAS", "I WILL FOLLOW YOU", listOf(edit))?.candidate?.trackName)
        assertEquals("I Will Follow You", choose("UNA MAS", "I WILL FOLLOW YOU", listOf(edit, plain))?.candidate?.trackName)
    }

    // ============== never the canonical cover ==============

    @Test
    fun `karaoke, tribute and made-famous-by records are refused`() {
        val candidates = listOf(
            candidate("The Karaoke Crew", "Song", "Karaoke Hits Vol. 3"),
            candidate("Tribute Players", "Song", "A Tribute to Some Act"),
            candidate("Studio Group", "Song", "Made Famous By Some Act"),
            candidate("Piano Dreamers", "Song", "Piano Renditions of Some Act"),
        )

        assertNull(choose("SOME ACT", "SONG", candidates))
    }

    @Test
    fun `a cover by another artist is not this track's artwork`() {
        // Daryl Hall & John Oates also have a "Maneater", and it is not this one.
        val choice = choose("NELLY FURTADO", "MANEATER", fixture("maneater"))

        assertEquals("Nelly Furtado", choice?.candidate?.artistName)
    }

    @Test
    fun `a VA compilation loses to the artist's own release`() {
        val candidates = listOf(
            candidate("Some Act", "Song", "Massive Dance Hits 2006", collectionArtist = "Various Artists", releaseDate = "2006-01-01"),
            candidate("Some Act", "Song", "The Album", releaseDate = "2006-06-01"),
        )

        assertEquals("The Album", choose("SOME ACT", "SONG", candidates)?.candidate?.collectionName)
    }

    // ============== artist identity ==============

    @Test
    fun `a slash in a band name is not a separator`() {
        val candidates = listOf(
            candidate("AC/DC", "Highway to Hell", "Highway to Hell", releaseDate = "1979-07-27"),
            candidate("AC", "Highway to Hell", "Something Else", releaseDate = "1979-01-01"),
        )

        val choice = choose("AC/DC", "HIGHWAY TO HELL", candidates)

        assertEquals("AC/DC", choice?.candidate?.artistName)
    }

    @Test
    fun `a comma in a band name is not a separator`() {
        val candidates = listOf(
            candidate("Earth, Wind & Fire", "September", "The Best of Earth, Wind & Fire", releaseDate = "1978-11-18"),
            candidate("Earth", "September", "September - Single", releaseDate = "1978-01-01"),
        )

        val choice = choose("EARTH, WIND & FIRE", "SEPTEMBER", candidates)

        assertEquals("Earth, Wind & Fire", choice?.candidate?.artistName)
    }

    @Test
    fun `an x collaboration matches the same pairing`() {
        val candidates = listOf(candidate("Sigala & Ella Eyre", "Came Here for Love", "Came Here for Love - Single"))

        assertNotNull(choose("SIGALA X ELLA EYRE", "CAME HERE FOR LOVE", candidates))
    }

    @Test
    fun `a with credit is a credit, not another act`() {
        val candidates = listOf(candidate("Robyn", "With Every Heartbeat", "Robyn"))

        assertNotNull(choose("ROBYN WITH KLEERUP", "WITH EVERY HEARTBEAT", candidates))
    }

    @Test
    fun `a featured guest does not have to be in the credit`() {
        val candidates = listOf(candidate("Breakbot", "Fantasy", "Fantasy (feat. Ruckazoid) - EP"))

        assertNotNull(choose("BREAKBOT FT. RUCKAZOID", "FANTASY", candidates))
    }

    /** The substring trap: three Cyrillic letters that live inside another band. */
    @Test
    fun `a short artist name does not match a longer unrelated one`() {
        val candidates = listOf(
            candidate("Motorhead", "Ace of Spades", "Ace of Spades"),
            candidate("Mother Mother", "Hayloft", "O My Heart"),
        )

        assertNull(choose("МОТ", "ACE OF SPADES", candidates))
        assertNull(choose("MOT", "HAYLOFT", candidates))
    }

    @Test
    fun `transliteration matches the same act across scripts`() {
        val candidates = listOf(candidate("Detsl aka Le Truk", "Кто? Ты", "Кто? Ты", releaseDate = "1999-01-01"))

        assertNotNull(choose("ДЕЦЛ", "КТО? ТЫ", candidates))
    }

    @Test
    fun `a company suffix is not part of the act`() {
        val candidates = listOf(candidate("Supafly", "Let's Get Down", "Let's Get Down - Single"))

        assertNotNull(choose("SUPAFLY INC", "LET'S GET DOWN", candidates))
    }

    // ============== nothing rather than something wrong ==============

    @Test
    fun `no credible candidate means no artwork`() {
        val candidates = listOf(
            candidate("Another Band", "Different Song", "Their Album"),
            candidate("Someone Else", "Song", "Their Other Album"),
        )

        assertNull(choose("SOME ACT", "SONG", candidates))
    }

    @Test
    fun `an empty answer is no artwork`() {
        assertNull(choose("SOME ACT", "SONG", emptyList()))
    }

    /**
     * Two releases of the same record on the same day: the album that carries the
     * track among others, and an EP that also carries it. The fuller release is
     * the answer, rather than whichever name sorts first - which is how `Gaslight`
     * was landing on an EP instead of `L.A. Times` in the recon sample.
     */
    @Test
    fun `an album beats an EP released the same day`() {
        val ep = candidate("Travis", "Gaslight", "Avalon - EP", releaseDate = "2024-07-12").copy(trackCount = 4)
        val album = candidate("Travis", "Gaslight", "L.A. Times", releaseDate = "2024-07-12").copy(trackCount = 12)

        assertEquals("L.A. Times", choose("TRAVIS", "GASLIGHT", listOf(ep, album))?.candidate?.collectionName)
        assertEquals("L.A. Times", choose("TRAVIS", "GASLIGHT", listOf(album, ep))?.candidate?.collectionName)
    }

    /** The track's own single still outranks the album that contains it. */
    @Test
    fun `the track's own single beats a bigger album of the same day`() {
        val single = candidate("Some Act", "Song", "Song - Single", releaseDate = "2020-01-01").copy(trackCount = 1)
        val album = candidate("Some Act", "Song", "The Album", releaseDate = "2020-01-01").copy(trackCount = 12)

        assertEquals("Song - Single", choose("SOME ACT", "SONG", listOf(album, single))?.candidate?.collectionName)
    }

    // ============== stability ==============

    /**
     * The provider's ordering is never consulted, so the same candidates always
     * produce the same cover. Before G5b the winner was the first of the equal
     * scores, which made the answer a function of how the search happened to sort.
     */
    @Test
    fun `the same candidates give the same answer in any order`() {
        val candidates = fixture("maneater")
        val expected = choose("NELLY FURTADO", "MANEATER", candidates)?.candidate?.collectionName

        assertEquals(expected, choose("NELLY FURTADO", "MANEATER", candidates.reversed())?.candidate?.collectionName)
        assertEquals(expected, choose("NELLY FURTADO", "MANEATER", candidates.shuffled())?.candidate?.collectionName)
        assertEquals(
            expected,
            choose("NELLY FURTADO", "MANEATER", candidates.sortedBy { it.trackName })?.candidate?.collectionName,
        )
    }
}
