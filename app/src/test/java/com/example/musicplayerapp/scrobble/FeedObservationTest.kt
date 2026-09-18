package com.example.musicplayerapp.scrobble

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The freshness gate and the no-fallback parse.
 *
 * The parse is exercised the way the service does it - Gson into a `Map`, so
 * numbers arrive as `Double` - against a payload in the live shape.
 */
class FeedObservationTest {

    private fun obs(
        artist: String? = "Artist",
        title: String? = "Title",
        startedAt: Long? = 1_000L,
        duration: Long? = 200L,
        endsAt: Long? = startedAt?.let { s -> duration?.let { s + it } },
        serverTime: Long? = 1_050L,
    ) = FeedObservation("myata", artist, title, startedAt, duration, endsAt, serverTime)

    // ---- validity ----

    @Test
    fun `a healthy observation is valid and fresh`() {
        val o = obs()
        assertTrue(o.isValid)
        assertTrue(o.isFresh)
        assertNotNull(o.trackKey)
    }

    @Test
    fun `a blank artist or title is not a track`() {
        assertFalse(obs(artist = "").isValid)
        assertFalse(obs(title = "  ").isValid)
        assertFalse(obs(artist = null).isValid)
    }

    @Test
    fun `the station jingle is not a track`() {
        assertFalse(obs(artist = "YOUR MUSIC! YOUR STATION!").isValid)
        assertFalse(obs(artist = "your  music!  your station!").isValid)
    }

    @Test
    fun `zero or missing timing is never valid`() {
        assertFalse(obs(startedAt = 0L, endsAt = 200L).isValid)
        assertFalse(obs(duration = 0L, endsAt = 1_000L).isValid)
        assertFalse(obs(startedAt = null, endsAt = 1_200L).isValid)
        assertFalse(obs(duration = null, endsAt = 1_200L).isValid)
        assertFalse(obs(endsAt = null).isValid)
    }

    @Test
    fun `server_time must really be present`() {
        assertFalse(obs(serverTime = null).isValid)
        assertFalse(obs(serverTime = null).isFresh)
    }

    @Test
    fun `ends_at must agree with started_at plus duration within a second`() {
        assertTrue(obs(endsAt = 1_201L).isValid)
        assertTrue(obs(endsAt = 1_199L).isValid)
        assertFalse(obs(endsAt = 1_202L).isValid)
        assertFalse(obs(endsAt = 1_198L).isValid)
    }

    @Test
    fun `started_at may lead server_time by at most 30 seconds`() {
        assertTrue(obs(startedAt = 1_080L, serverTime = 1_050L).isValid)
        assertFalse(obs(startedAt = 1_081L, serverTime = 1_050L).isValid)
    }

    // ---- freshness ----

    @Test
    fun `fresh until 120 seconds past ends_at, stale one second later`() {
        assertTrue(obs(serverTime = 1_200L).isFresh)
        assertTrue(obs(serverTime = 1_320L).isFresh)
        assertFalse(obs(serverTime = 1_321L).isFresh)
        assertTrue("stale is still valid - just old", obs(serverTime = 5_000L).isValid)
    }

    // ---- parse ----

    private val livePayload = """
        {"server_time":1789758535,"data":{
          "myata":{"artist":"Artist A","track":"Song A","ends_at":1789758636,"started_at":1789758387,"duration":249},
          "gold":{"artist":"Artist G","track":"Song G","ends_at":1789758645},
          "myata_hits":{"artist":"","track":"","ends_at":0,"started_at":0,"duration":0}
        }}
    """.trimIndent()

    private fun parse(stream: String): FeedObservation {
        val root = Gson().fromJson(livePayload, Map::class.java)
        val data = root["data"] as Map<*, *>
        return FeedObservation.from(stream, data[stream] as Map<*, *>, root["server_time"])
    }

    @Test
    fun `the live shape parses exactly, Doubles and all`() {
        val o = parse("myata")
        assertEquals("myata", o.streamId)
        assertEquals(1_789_758_387L, o.startedAt)
        assertEquals(249L, o.durationSec)
        assertEquals(1_789_758_636L, o.endsAt)
        assertEquals(1_789_758_535L, o.serverTime)
        assertTrue(o.isValid)
        assertTrue(o.isFresh)
    }

    @Test
    fun `missing fields stay missing - no fallbacks`() {
        val o = parse("gold")
        assertNull(o.startedAt)
        assertNull(o.durationSec)
        assertFalse(o.isValid)
    }

    @Test
    fun `an absent server_time is null, not the device clock`() {
        val o = FeedObservation.from("myata", mapOf("artist" to "A", "track" to "T"), null)
        assertNull(o.serverTime)
        val wrongType = FeedObservation.from("myata", mapOf("started_at" to "1789758387"), "now")
        assertNull(wrongType.startedAt)
        assertNull(wrongType.serverTime)
    }

    @Test
    fun `a zeroed stream is invalid`() {
        assertFalse(parse("myata_hits").isValid)
    }

    @Test
    fun `toString carries no artist or title`() {
        val text = parse("myata").toString()
        assertFalse(text.contains("Artist A"))
        assertFalse(text.contains("Song A"))
    }
}
