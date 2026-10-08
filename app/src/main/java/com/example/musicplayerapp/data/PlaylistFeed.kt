package com.example.musicplayerapp.data

/**
 * The parser for `radiomyata.ru/covers/playlists.txt`, the file HOME's playlist row is
 * built from.
 *
 * Each entry is a cover URL and the playlist's link, separated by a dash **with
 * whitespace on both sides**, and entries are separated by a blank line:
 *
 * ```
 * https://radiomyata.ru/covers/dance.jpg — https://links.radiomyata.ru/playlists/?slug=dance-mix
 *
 * https://radiomyata.ru/covers/rock.jpg - https://links.radiomyata.ru/playlists/?slug=rock
 * ```
 *
 * The whitespace is what keeps a hyphen inside a URL - `?slug=dance-mix` - from being
 * read as the separator. The link is handed on verbatim: nothing here splits, decodes,
 * normalises or otherwise touches the slug, and `PlaylistFeedTest` pins that, because a
 * slug cut at its first `-` (`dance-mix` -> `dance`) is a bug the mobile site has already
 * had once.
 *
 * Pure and Android-free so the whole file format is a JVM test. Extracted unchanged from
 * `MetadataRepository.fetchPlaylists`.
 */
object PlaylistFeed {

    /** One playlist: the [link] a tap opens, and the [image] its card shows. */
    data class Entry(val link: String, val image: String)

    private val ENTRY_SEPARATOR = Regex("\\n\\s*\\n")
    private val FIELD_SEPARATOR = Regex("\\s+[—–-]\\s+")

    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (entry in text.split(ENTRY_SEPARATOR)) {
            if (entry.isBlank()) continue
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size < 2) continue
            val image = parts[0].trim(' ', '﻿', '\n', '\r')
            val link = parts[1].trim(' ', '\n', '\r')
            entries += Entry(link = link, image = image)
        }
        return entries
    }
}
