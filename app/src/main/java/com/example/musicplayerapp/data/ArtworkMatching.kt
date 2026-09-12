package com.example.musicplayerapp.data

import java.text.Normalizer
import java.util.Locale

/**
 * One release a provider offered as a possible cover.
 *
 * The fields are the ones iTunes already returns and the matcher actually reads.
 * [collectionArtistName] is the one that was there all along and never looked at:
 * it is how a Various Artists compilation says so, rather than having to be
 * guessed from an English album title (G5 recon).
 */
data class ArtworkCandidate(
    val trackName: String,
    val artistName: String,
    val collectionName: String,
    val collectionArtistName: String? = null,
    val releaseDate: String? = null,
    /** How many tracks the release holds - an album, an EP or a single. */
    val trackCount: Int? = null,
    val artworkUrl: String,
)

/** How sure the matcher is, for reporting and for owner review. Never persisted. */
enum class ArtworkConfidence { HIGH, MEDIUM, LOW }

/**
 * What the picture actually is.
 *
 * The distinction exists because step 5 of the owner's fallback hierarchy is a
 * photograph of the artist, which is a reasonable thing to show when nothing else
 * can be found and a wrong thing to call album artwork. Callers that need to know
 * can ask; nothing persists it.
 */
enum class ArtworkSource {
    /** A release's own cover. */
    RELEASE,

    /** A picture of the act, standing in because no release could be matched. */
    ARTIST_IMAGE,
}

/** The chosen release, with why it was chosen. */
data class ArtworkChoice(
    val candidate: ArtworkCandidate,
    val confidence: ArtworkConfidence,
    val reason: String,
)

/**
 * Picks the canonical cover for a station's artist/title pair (G5b).
 *
 * The station's metadata is two strings and nothing else - no album, no ISRC, no
 * id - so every decision here is made from those two strings against what the
 * provider returned. What changed in G5b is which decisions are made at all.
 *
 * ## What went wrong before
 *
 * The old matcher accepted a candidate whose artist merely *contained* the
 * station's artist as a substring and whose title merely contained the station's
 * title as words, then scored the survivors with two boosts: `+2` for an album
 * ending in "- Single" and `+3` for an album whose name contains the track name.
 * A remix single is called "Song (X Remix) - Single", so it scored both and beat
 * the original album's `1` every time. Measured over 90 live station tracks,
 * about a quarter of the covers were remixes, re-recordings or compilations
 * rather than the release the listener is hearing.
 *
 * ## What it does now
 *
 * Rank almost everything; reject only what is genuinely not this record.
 *
 * **Coverage matters** (owner decision): the branded plate is the *last* resort,
 * not the preferred answer when the match is imperfect. A remix, a reissue or a
 * compilation that really does carry this track is still this track's artwork -
 * it is simply worse than the canonical release, so it is ranked below it and
 * used only when nothing better was offered.
 *
 *  1. **Artist** must match by *identity*, not by substring - whole normalised
 *     names, whole collaboration parts, or whole tokens, so `МОТ` can no longer
 *     match `Motörhead` and `AC/DC` is never reduced to `AC`.
 *  2. **Title** must match on its base. The version then decides *rank* rather
 *     than admission: if the station does not say remix, live or acoustic, a
 *     candidate that does is a different recording and sorts below the plain
 *     release, but it is still offered when there is no plain release. If the
 *     station *does* name a version, candidates of that family sort first - the
 *     marker is honoured, never stripped away.
 *  3. **Hard rejection** is kept for what is not this record at all: another
 *     artist's cover, tributes, karaoke, "made famous by", and instrument covers
 *     nobody asked for.
 *  4. **[Level]** is the owner's fallback hierarchy - canonical, then reissue,
 *     then another version, then a compilation - and it is the first thing
 *     compared. Within a level a fixed sequence of tiers decides, ending in a
 *     lexicographic tie-break, so the same candidates always produce the same
 *     answer whatever order the provider returned them in.
 *
 * Nothing here fetches, caches or knows about a provider. It is a function of
 * (artist, title, candidates), which is what makes the golden tests possible.
 */
object ArtworkMatcher {

    // ============== markers ==============

    /**
     * Version words that usually mean *different artwork*: a remix single, a live
     * album, an acoustic session. Rejected when the station did not ask for one.
     *
     * Grouped into families because a station that says "remix" should be allowed
     * any remix, not only the one whose DJ it happens to name.
     */
    private val REMIX_FAMILY = setOf(
        "remix", "remixes", "rmx", "mix", "mixes", "rework", "reworked", "bootleg",
        "mashup", "dub", "vip", "reform", "refix", "ремикс", "ремиксы",
    )
    private val LIVE_FAMILY = setOf("live", "concert", "unplugged", "session", "sessions", "живьём", "живьем")
    private val ACOUSTIC_FAMILY = setOf("acoustic", "acoustics", "акустика", "акустическая")
    private val INSTRUMENTAL_FAMILY = setOf("instrumental", "инструментал", "минус")
    private val EDIT_FAMILY = setOf("edit", "edits", "version", "versions", "версия", "версии", "версией")

    private val FAMILIES = listOf(REMIX_FAMILY, LIVE_FAMILY, ACOUSTIC_FAMILY, INSTRUMENTAL_FAMILY, EDIT_FAMILY)

    /**
     * The families that mean a *different record*, and so a different cover: a
     * remix single, a live album, an acoustic session, an instrumental. A
     * candidate carrying one of these when the station asked for none is rejected.
     *
     * [EDIT_FAMILY] is deliberately not among them. A radio edit or a single
     * version is the same recording trimmed, released under the same artwork, so
     * rejecting it costs a correct cover and gains nothing - it is demoted instead
     * (see [titleTier]), which lets a plain title win whenever there is one.
     */
    private val VERSION_ALTERING = listOf(REMIX_FAMILY, LIVE_FAMILY, ACOUSTIC_FAMILY, INSTRUMENTAL_FAMILY)

    /**
     * Version words that leave the artwork alone. A remaster is the same cover
     * more often than not, a radio version of a single is the single's cover, and
     * a credit is not a version at all - so these never reject a candidate, they
     * only lose to a plainer title of the same release.
     */
    private val BENIGN_PHRASES = listOf(
        "original mix", "original version", "album version", "single version",
        "remastered", "remaster", "explicit", "clean", "bonus track", "mono", "stereo",
    )

    /** Never the canonical cover, whoever released it. */
    private val NEVER = listOf(
        "tribute", "karaoke", "made famous", "in the style of", "originally performed",
        "as made popular", "backing track", "sing along", "singalong",
    )

    /** Instrument covers - only when the station did not ask for one. */
    private val INSTRUMENT_COVERS = listOf(
        "piano", "lullaby", "8 bit", "8bit", "string quartet", "music box", "guitar tribute",
    )

    /** Album names that are a compilation outright, as their whole name. */
    private val COMPILATION_EXACT = setOf(
        "best", "the best", "hits", "the hits", "лучшее", "хиты", "сборник",
    )

    /** Album names that are collections of other releases rather than a release. */
    private val COMPILATION_WORDS = listOf(
        "greatest hits", "best of", "the essential", "essentials", "anthology",
        "compilation", "collection", "now thats what i call", "megamix", "hitz", "hits",
    )

    /** A reissue of a release: the same record, usually the same or a tweaked cover. */
    private val REISSUE_WORDS = listOf(
        "remaster", "remastered", "deluxe", "anniversary", "expanded", "reissue",
        "special edition", "legacy edition", "bonus edition",
    )

    /** Words that introduce a credit rather than another act. */
    private val CREDIT_MARKERS = setOf("feat", "ft", "featuring", "with", "pres", "presents", "presenting")

    /** Words that join two acts. `and` covers `&` and `+` after normalisation. */
    private val COLLAB_MARKERS = setOf("and", "x", "vs", "versus", "meets", "aka")

    /** `Supafly Inc` and `Supafly` are one act; the suffix is not identity. */
    private val COMPANY_SUFFIXES = setOf("inc", "ltd", "llc")

    /**
     * The owner's fallback hierarchy, best first. Compared before anything else,
     * so a worse *kind* of release never wins on a tie-break.
     *
     * What is not here is the artist photograph and the branded plate: those are
     * steps 5 and 6, and neither is a candidate this matcher can return - the
     * repository falls back to them only when nothing at all was matched.
     */
    internal enum class Level {
        /** The release the track came out on. */
        CANONICAL,

        /** The same record again: remaster, deluxe, anniversary, reissue. */
        REISSUE,

        /** Another version of it - a remix, a live take, an edit, a re-release. */
        ALTERNATE,

        /** A compilation that really does carry this track. */
        COMPILATION,
    }

    // ============== entry point ==============

    /**
     * The cover for [artist] / [title], or null when nothing offered is credibly
     * that recording's own artwork.
     *
     * Null is a real answer. A station track that only exists on Various Artists
     * compilations, or whose only matches are remixes of it, gets no cover rather
     * than somebody else's.
     */
    fun choose(artist: String, title: String, candidates: List<ArtworkCandidate>): ArtworkChoice? {
        if (candidates.isEmpty()) return null

        val wanted = TitleParts.of(title)
        val stationArtist = ArtistIdentity.of(artist)

        val scored = candidates.mapNotNull { score(stationArtist, wanted, it) }
        if (scored.isEmpty()) return null

        val best = scored.minWithOrNull(ORDER) ?: return null
        return ArtworkChoice(best.candidate, best.confidence(), best.reason())
    }

    /**
     * Whether two credit strings name the same act, by the same reading the
     * candidate filter uses.
     *
     * Exposed for the artist-image fallback, which has no release to judge and
     * must at least be sure the photograph is of the right act.
     */
    fun sameArtist(station: String, candidate: String): Boolean =
        ArtistIdentity.tier(ArtistIdentity.of(station), candidate) != null

    // ============== filtering ==============

    private fun score(
        stationArtist: ArtistIdentity,
        wanted: TitleParts,
        candidate: ArtworkCandidate,
    ): Scored? {
        val collection = Norm.text(candidate.collectionName)
        val credited = Norm.text(candidate.artistName)

        // Not this record by anybody's reading: somebody else performing it.
        if (NEVER.any { collection.contains(it) || credited.contains(it) }) return null

        val candidateTitle = TitleParts.of(candidate.trackName)
        if (INSTRUMENT_COVERS.any { word ->
                (collection.contains(word) || credited.contains(word)) &&
                    !wanted.all.contains(word) && !stationArtist.normalised.contains(word)
            }
        ) {
            return null
        }

        val artistTier = ArtistIdentity.tier(stationArtist, candidate.artistName) ?: return null

        // Only a title that is not this track at all is refused here.
        val titleTier = titleTier(wanted, candidateTitle) ?: return null

        // The version can sit on the release rather than on the track: a plain
        // title on a live album is still the live recording.
        val offered = candidateTitle.markers + markersIn(Norm.tokens(candidate.collectionName))
        val otherVersion = if (wanted.markers.isEmpty()) {
            offered.any(::changesArtwork)
        } else {
            !compatible(wanted.markers, offered)
        }

        val variousArtists = Norm.text(candidate.collectionArtistName.orEmpty()).contains("various artists")
        val compilation = variousArtists ||
            collection in COMPILATION_EXACT ||
            COMPILATION_WORDS.any { collection.contains(it) }

        val level = when {
            compilation -> Level.COMPILATION
            otherVersion -> Level.ALTERNATE
            REISSUE_WORDS.any { collection.contains(it) } -> Level.REISSUE
            else -> Level.CANONICAL
        }

        return Scored(
            candidate = candidate,
            level = level,
            artistTier = artistTier,
            titleTier = titleTier,
            variousArtists = if (variousArtists) 1 else 0,
            releaseOrder = releaseOrder(candidate.releaseDate),
            exactRelease = ownReleaseTier(wanted, collection),
            trackCount = candidate.trackCount ?: 0,
        )
    }

    /**
     * How well the candidate's title reads as the station's, or null when it is
     * simply a different song - the one thing a title can be refused for.
     *
     * Within a match, plainer is better: an exact plain title, then one carrying
     * a benign phrase, then a plural slip, then a trim or another version. Those
     * last ones also raise [Level], so this only orders them among themselves.
     */
    private fun titleTier(wanted: TitleParts, candidate: TitleParts): Int? {
        val exact = wanted.base == candidate.base
        val nearly = !exact && Norm.equalIgnoringPlural(wanted.base, candidate.base)
        if (!exact && !nearly) return null

        if (wanted.markers.isEmpty()) {
            val benign = candidate.hasBenign
            val plain = candidate.markers.isEmpty()
            return when {
                exact && plain && !benign -> 0
                exact && plain -> 1
                nearly && plain -> 2
                exact -> 3
                else -> 4
            }
        }

        // The station named a version. One of the same family is what it asked
        // for; anything else is a fallback and sorts after it.
        val sameFamily = compatible(wanted.markers, candidate.markers)
        val sameName = wanted.markerWords.isNotEmpty() && wanted.markerWords == candidate.markerWords
        return when {
            sameFamily && exact && sameName -> 0
            sameFamily && exact -> 1
            sameFamily -> 2
            exact -> 3
            else -> 4
        }
    }

    /** Does this marker mean a different record rather than a trim of the same one? */
    private fun changesArtwork(marker: String): Boolean =
        VERSION_ALTERING.any { marker in it }

    /** Two marker sets agree when they name at least one family in common. */
    private fun compatible(wanted: Set<String>, offered: Set<String>): Boolean {
        if (offered.isEmpty()) return false
        return FAMILIES.any { family ->
            wanted.any { it in family } && offered.any { it in family }
        }
    }

    private fun markersIn(tokens: List<String>): Set<String> =
        tokens.filter { token -> FAMILIES.any { token in it } }.toSet()

    /**
     * How much this album is the track's *own* release.
     *
     * 0 is the release named after the track - its single, its EP, or an album of
     * the same name. 1 is a release whose name begins with the track's, which is
     * how a numbered or subtitled edition of it reads. 2 is everything else: a
     * studio album that contains it among others, which is still perfectly
     * canonical and simply less specific.
     */
    private fun ownReleaseTier(wanted: TitleParts, collection: String): Int {
        val base = wanted.base
        if (base.isEmpty()) return 2
        if (collection == base || collection == "$base single" ||
            collection == "$base ep" || collection == "the $base"
        ) {
            return 0
        }
        return if (collection.startsWith("$base ")) 1 else 2
    }

    /**
     * The release date as it sorts. ISO dates sort correctly as text, so the whole
     * date is compared rather than the year: two releases from the same year are
     * ordered by which actually came first, instead of by a later tie-break.
     */
    private fun releaseOrder(releaseDate: String?): String =
        releaseDate?.takeIf { it.isNotBlank() } ?: "9999" 

    // ============== ranking ==============

    private class Scored(
        val candidate: ArtworkCandidate,
        val level: Level,
        val artistTier: Int,
        val titleTier: Int,
        val variousArtists: Int,
        val releaseOrder: String,
        val exactRelease: Int,
        val trackCount: Int,
    ) {
        /**
         * How sure this is, which is mostly which rung of the hierarchy it came
         * from. Anything below a canonical release is reported LOW: it is the
         * right track, but not the release the listener is hearing, and the owner
         * should be able to see that in the log.
         */
        fun confidence(): ArtworkConfidence = when {
            level == Level.CANONICAL && artistTier <= 1 && titleTier == 0 -> ArtworkConfidence.HIGH
            level == Level.CANONICAL || level == Level.REISSUE -> ArtworkConfidence.MEDIUM
            else -> ArtworkConfidence.LOW
        }

        fun reason(): String =
            "level=$level artist=$artistTier title=$titleTier " +
                "date=${releaseOrder.take(10)} own=$exactRelease"
    }

    /**
     * The order the owner asked for, as a fixed sequence of comparisons: the
     * rung of the hierarchy first, then how exactly the artist and the title
     * match, then the earliest such release, then the track's own single or EP
     * over an album that merely contains it, then the fuller release.
     *
     * The last comparison is lexicographic rather than positional on purpose: the
     * provider's own ordering is never consulted, so the same candidates always
     * produce the same cover however they arrive.
     */
    private val ORDER: Comparator<Scored> = compareBy(
        // The hierarchy first: a canonical release outranks a reissue, which
        // outranks another version, which outranks a compilation - whatever the
        // finer tiers say about any of them.
        { it.level },
        { it.artistTier },
        { it.titleTier },
        // Among compilations, the artist's own beats a Various Artists one.
        { it.variousArtists },
        { it.releaseOrder },
        { it.exactRelease },
        // Two releases of the same record on the same day - an album and an EP
        // that both carry the track - are separated by which is the fuller
        // release, so the answer is the album rather than whichever name happens
        // to sort first.
        { -it.trackCount },
        { Norm.text(it.candidate.collectionName) },
        { Norm.text(it.candidate.trackName) },
    )

    // ============== identity ==============

    /** A station artist string, in the shapes the comparisons need. */
    internal class ArtistIdentity private constructor(
        val normalised: String,
        val credited: String,
        val tokens: List<String>,
    ) {
        companion object {
            fun of(artist: String): ArtistIdentity {
                val normalised = Norm.text(artist)
                val credited = stripCredits(normalised)
                return ArtistIdentity(normalised, credited, Norm.tokens(credited))
            }

            /**
             * A leading definite article is not part of an act's identity.
             *
             * The station writes `THE COURTEENERS`; iTunes indexes them as
             * `Courteeners`, and `St. Jude` was being missed over the word "the"
             * alone - the track fell all the way to an artist photograph. Both
             * sides are read the same way, so `The The` still matches itself.
             */
            private fun withoutArticle(name: String): String =
                name.removePrefix("the ").ifEmpty { name }

            /** Everything up to the first credit word: `A ft. B` is A's record. */
            private fun stripCredits(normalised: String): String {
                val tokens = normalised.split(' ')
                val cut = tokens.indexOfFirst { it in CREDIT_MARKERS }
                val head = (if (cut > 0) tokens.take(cut) else tokens).toMutableList()
                while (head.size > 1 && head.last() in COMPANY_SUFFIXES) head.removeAt(head.size - 1)
                return head.joinToString(" ").trim()
            }

            /** The acts a credit string names, in order. `A & B` is two acts. */
            private fun parts(normalised: String): List<String> {
                val out = mutableListOf<String>()
                val current = mutableListOf<String>()
                for (token in normalised.split(' ')) {
                    if (token in COLLAB_MARKERS || token in CREDIT_MARKERS) {
                        if (current.isNotEmpty()) out += current.joinToString(" ")
                        current.clear()
                    } else if (token.isNotEmpty()) {
                        current += token
                    }
                }
                if (current.isNotEmpty()) out += current.joinToString(" ")
                return out
            }

            /**
             * How well a candidate's credit matches the station's artist, or null
             * when it does not match at all.
             *
             * Every comparison is between *whole* names, whole parts or whole
             * tokens. None of them is a substring test, which is what used to let
             * a three-letter artist match an unrelated band, and none of them
             * splits a name on punctuation, which is what used to turn `AC/DC`
             * into `AC` and `Earth, Wind & Fire` into `Earth`.
             */
            fun tier(station: ArtistIdentity, candidateArtist: String): Int? {
                val candidate = Norm.text(candidateArtist)
                if (candidate.isEmpty() || station.normalised.isEmpty()) return null

                if (Norm.same(candidate, station.normalised) ||
                    Norm.same(withoutArticle(candidate), withoutArticle(station.normalised))
                ) {
                    return 0
                }

                val candidateCredited = stripCredits(candidate)
                if (Norm.same(candidateCredited, station.credited) ||
                    Norm.same(withoutArticle(candidateCredited), withoutArticle(station.credited))
                ) {
                    return 1
                }

                val candidateParts = parts(candidate)

                // The same pairing spelled differently: `A x B`, `A & B` and
                // `B vs. A` are one act list, and the order they are printed in is
                // the provider's choice rather than a difference.
                val stationParts = parts(station.normalised)
                if (stationParts.size > 1 && stationParts.toSet() == candidateParts.toSet()) return 1

                val stationName = withoutArticle(station.credited)
                if (stationName.isNotEmpty() && stationName.length >= 3) {
                    val at = candidateParts.indexOfFirst { Norm.same(withoutArticle(it), stationName) }
                    if (at == 0) return 2
                    if (at > 0) return 3

                    // `Децл` is credited as `Detsl aka Le Truk`: every token of the
                    // station's name is there, as a whole token. Whole tokens are
                    // what keeps `мот` out of `Motörhead`.
                    val candidateTokens = Norm.tokens(candidate).map(Norm::translit).toSet()
                    val stationTokens = Norm.tokens(stationName).map(Norm::translit)
                    if (stationTokens.isNotEmpty() && candidateTokens.containsAll(stationTokens)) return 4
                }

                return null
            }
        }
    }

    /** A title split into what it is and which version of it. */
    internal class TitleParts private constructor(
        val base: String,
        val markers: Set<String>,
        val markerWords: Set<String>,
        val hasBenign: Boolean,
        val all: String,
    ) {
        companion object {
            // The closing bracket is escaped because Android's regex engine is ICU,
        // not the JVM's: ICU rejects a bare `]` or `}` here as a syntax error and
        // throws at first use on the device, while every JVM unit test passes.
        // Found by the instrumented test, which is the reason it exists.
        private val SEGMENT = Regex("\\(([^)]*)\\)|\\[([^\\]]*)\\]")

            fun of(title: String): TitleParts {
                val all = Norm.text(title)

                val segments = mutableListOf<String>()
                var head = title
                SEGMENT.findAll(title).forEach { m ->
                    segments += (m.groupValues[1] + m.groupValues[2])
                }
                head = SEGMENT.replace(head, " ")

                // A trailing " - Something" is the same thing spelled without
                // brackets: `Song - Radio Edit`.
                val dash = head.indexOf(" - ")
                if (dash > 0) {
                    segments += head.substring(dash + 3)
                    head = head.substring(0, dash)
                }

                val base = Norm.text(head)
                val segmentText = segments.joinToString(" ") { Norm.text(it) }
                val benign = BENIGN_PHRASES.any { segmentText.contains(it) || all.contains(it) }

                // Benign phrases are removed before the version words are looked
                // for, so `Original Mix` does not read as a remix.
                var searchable = segmentText
                BENIGN_PHRASES.forEach { searchable = searchable.replace(it, " ") }

                val tokens = Norm.tokens(searchable)
                val markers = tokens.filter { token -> FAMILIES.any { token in it } }.toSet()
                val words = tokens.filterNot { token -> FAMILIES.any { token in it } }.toSet()

                return TitleParts(
                    base = base,
                    markers = markers,
                    markerWords = if (markers.isEmpty()) emptySet() else words,
                    hasBenign = benign,
                    all = all,
                )
            }
        }
    }

    // ============== normalisation ==============

    internal object Norm {

        private val CYRILLIC = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
            'і' to "i", 'ї' to "yi", 'є' to "e", 'ґ' to "g",
        )

        /**
         * Case, accents, punctuation and spacing folded away; letters and digits
         * kept, whatever their script.
         *
         * Punctuation becomes a space rather than disappearing, and both sides get
         * the same treatment, so `AC/DC` is `ac dc` on both and
         * `Earth, Wind & Fire` is `earth wind and fire` on both. Nothing is split
         * off and thrown away here - that is the whole difference from the old
         * `getCleanArtistName`, which cut the string at the first `,` or `/`.
         */
        fun text(value: String): String {
            val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)
            val folded = StringBuilder(nfkc.length)
            for (ch in nfkc) {
                when (ch) {
                    '&', '+' -> folded.append(" and ")
                    'ø', 'Ø' -> folded.append('o')
                    'æ', 'Æ' -> folded.append("ae")
                    'ß' -> folded.append("ss")
                    'þ', 'Þ' -> folded.append("th")
                    'ð', 'Ð' -> folded.append('d')
                    else -> folded.append(ch)
                }
            }

            val stripped = Normalizer.normalize(folded, Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

            return stripped
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
                .trim()
        }

        fun tokens(value: String): List<String> =
            text(value).split(' ').filter { it.isNotEmpty() }

        /** Cyrillic to Latin, so one script can be compared with the other. */
        fun translit(value: String): String {
            val sb = StringBuilder(value.length)
            for (ch in value) sb.append(CYRILLIC[ch] ?: ch)
            return sb.toString()
        }

        /** Equal as written, or equal once both are read in the same script. */
        fun same(a: String, b: String): Boolean =
            a == b || (a.isNotEmpty() && translit(a) == translit(b))

        /**
         * Equal but for an English plural on one side, token for token.
         *
         * The station's strings are typed by hand and sometimes lose the `s`:
         * `MUSCLE CAR` for `Muscle Cars`. Accepted, but at a lower tier, so a
         * candidate that matches exactly always wins.
         */
        fun equalIgnoringPlural(a: String, b: String): Boolean {
            val left = a.split(' ')
            val right = b.split(' ')
            if (left.size != right.size || left.isEmpty()) return false
            return left.indices.all { i ->
                val l = left[i]
                val r = right[i]
                l == r || l.removeSuffix("s") == r.removeSuffix("s")
            }
        }
    }
}
