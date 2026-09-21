package com.example.musicplayerapp.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The private command channel, on the JVM.
 *
 * This queue is the whole boundary now: the service is exported, a start intent
 * carries nothing, and the only way a command can reach playback is by being put
 * here by this process. So the two properties the arrangement depends on are
 * pinned here rather than left to the code that uses them:
 *
 *  1. a drain takes **everything** waiting, in order, and leaves nothing behind;
 *  2. a command whose start was refused can be taken back out - otherwise a Play
 *     the platform declined would be run by the next start of any kind, minutes
 *     later and for a different reason.
 *
 * There is no Android in this file, which is why it can be pinned here at all. The
 * two ends - ServiceUtils handing a command over, [MediaPlayerService] draining it
 * and acting on it - need a device, and are `PlaybackCommandBoundaryTest` there.
 */
class PlaybackCommandChannelTest {

    @After
    fun leaveNothingBehind() {
        PlaybackCommands.drain()
    }

    @Test
    fun `a drain takes the queue in order and leaves it empty`() {
        val play = PlaybackCommands.enqueue(PlaybackCommand("play", stream = "myata"))
        val stop = PlaybackCommands.enqueue(PlaybackCommand("stop"))

        assertEquals(listOf(play, stop), PlaybackCommands.drain())
        assertTrue("a second drain must find nothing", PlaybackCommands.drain().isEmpty())
    }

    @Test
    fun `a withdrawn command is not run by a later start`() {
        val refused = PlaybackCommands.enqueue(PlaybackCommand("play", stream = "myata"))

        PlaybackCommands.withdraw(refused)

        assertTrue(PlaybackCommands.drain().isEmpty())
    }

    /**
     * Identity, not equality: the start that failed takes back the object it queued,
     * so a command with the same fields that is still waiting stays waiting.
     */
    @Test
    fun `a withdraw takes back its own command and not an identical one`() {
        val stillWaiting = PlaybackCommands.enqueue(PlaybackCommand("switch_track", song = "one"))
        val refused = PlaybackCommands.enqueue(PlaybackCommand("switch_track", song = "one"))

        PlaybackCommands.withdraw(refused)

        assertEquals(listOf(stillWaiting), PlaybackCommands.drain())
    }

    /**
     * A command from a screen is not a foreground start, and the one that has to be
     * is the only one that can post the placeholder notification.
     */
    @Test
    fun `only a foreground start says so`() {
        assertEquals(false, PlaybackCommands.enqueue(PlaybackCommand("stop")).openForeground)
        assertFalse(
            "a sleep-timer command must never claim to be a foreground start",
            PlaybackCommands.enqueue(PlaybackCommand(SleepTimerContract.ACTION_SET)).openForeground,
        )
        assertTrue(PlaybackCommands.drain().isNotEmpty())
    }
}
