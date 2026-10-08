package com.example.musicplayerapp.data

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `playlists.txt` in, playlist links out - with every slug whole.
 *
 * The mobile site once cut `?slug=dance-mix` down to `?slug=dance` (a slug split at its
 * first `-`). These pin that the app's own half of the path - the file parser - cannot do
 * the same: a hyphen is only a separator with whitespace on both sides, and the link is
 * passed on verbatim.
 *
 * `dance-mix` is the slug from the original site bug report. The others are written to
 * exercise a shape (no hyphen, several hyphens), not copied from the live file, which
 * this test deliberately does not depend on.
 */
class PlaylistFeedTest {

    private val danceMix = "https://links.radiomyata.ru/playlists/?slug=dance-mix"

    @Test
    fun `a slug with one hyphen stays whole`() {
        val feed = "https://radiomyata.ru/covers/dance.jpg — $danceMix"

        val entries = PlaylistFeed.parse(feed)

        assertEquals(1, entries.size)
        assertEquals(danceMix, entries[0].link)
        assertEquals("dance-mix", slugOf(entries[0].link))
        assertEquals("https://radiomyata.ru/covers/dance.jpg", entries[0].image)
    }

    @Test
    fun `a slug without a hyphen is unchanged`() {
        val link = "https://links.radiomyata.ru/playlists/?slug=chill"
        val entries = PlaylistFeed.parse("https://radiomyata.ru/covers/chill.jpg - $link")

        assertEquals(link, entries.single().link)
        assertEquals("chill", slugOf(entries.single().link))
    }

    @Test
    fun `a slug with several hyphens stays whole`() {
        val link = "https://links.radiomyata.ru/playlists/?slug=late-night-mint-mix"
        val entries = PlaylistFeed.parse("https://radiomyata.ru/covers/late.jpg – $link")

        assertEquals(link, entries.single().link)
        assertEquals("late-night-mint-mix", slugOf(entries.single().link))
    }

    @Test
    fun `every separator the file uses splits only the two fields`() {
        // Em dash, en dash and a plain hyphen, each with whitespace on both sides -
        // and a hyphen in a cover's file name, which has none.
        val feed = listOf(
            "https://radiomyata.ru/covers/a-1.jpg — https://links.radiomyata.ru/playlists/?slug=a-b",
            "https://radiomyata.ru/covers/c-2.jpg – https://links.radiomyata.ru/playlists/?slug=c-d",
            "https://radiomyata.ru/covers/e-3.jpg - https://links.radiomyata.ru/playlists/?slug=e-f",
        ).joinToString("\n\n")

        val entries = PlaylistFeed.parse(feed)

        assertEquals(listOf("a-b", "c-d", "e-f"), entries.map { slugOf(it.link) })
        assertEquals(
            listOf(
                "https://radiomyata.ru/covers/a-1.jpg",
                "https://radiomyata.ru/covers/c-2.jpg",
                "https://radiomyata.ru/covers/e-3.jpg",
            ),
            entries.map { it.image },
        )
    }

    @Test
    fun `windows line endings, a BOM and blank padding leave the link intact`() {
        val feed = "﻿https://radiomyata.ru/covers/dance.jpg — $danceMix\r\n\r\n" +
            "https://radiomyata.ru/covers/chill.jpg — https://links.radiomyata.ru/playlists/?slug=chill\r\n"

        val entries = PlaylistFeed.parse(feed)

        assertEquals(
            listOf(danceMix, "https://links.radiomyata.ru/playlists/?slug=chill"),
            entries.map { it.link },
        )
        assertEquals("https://radiomyata.ru/covers/dance.jpg", entries[0].image)
    }

    @Test
    fun `an entry without a separator is skipped rather than guessed at`() {
        val feed = "just some text\n\nhttps://radiomyata.ru/covers/dance.jpg — $danceMix"

        assertEquals(listOf(danceMix), PlaylistFeed.parse(feed).map { it.link })
    }

    /** The `slug` query parameter, read the way any URL parser reads it. */
    private fun slugOf(link: String): String? =
        URI(link).rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == "slug" }
            ?.getOrNull(1)
}
