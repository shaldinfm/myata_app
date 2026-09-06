package com.example.musicplayerapp.data

import android.content.Context
import android.provider.Settings

/**
 * Which boot of this device we are on.
 *
 * ## Why the platform counter and not an elapsedRealtime comparison
 *
 * The obvious reboot detector - "the timer was armed at elapsedRealtime T, and
 * elapsedRealtime is now less than T, so the counter must have reset" - is not a
 * detector at all. elapsedRealtime restarts from zero and then *climbs*, so a
 * device that was up for 20 minutes when the timer was armed and has been up for
 * 40 minutes since rebooting reads as a perfectly ordinary 20 minutes of
 * progress. The check only fires in the narrow window where the new uptime has
 * not yet passed the old one; every reboot after that window is missed, and the
 * app would restore a deadline from a previous boot and stop playback at a moment
 * that means nothing.
 *
 * `Settings.Global.BOOT_COUNT` is the platform's own answer: an integer the system
 * increments once per boot, readable without any permission, and **available from
 * API 24**, which is exactly this app's `minSdk`. Nothing else that is readable
 * from an unprivileged app on API 24 distinguishes boots reliably.
 *
 * ## Unknown is not "same boot"
 *
 * The setting can be missing on a device whose provider does not carry it. That
 * is recorded as [UNKNOWN] and [matches] answers `false` for it in both
 * directions, so a record that cannot prove which boot it belongs to is discarded
 * rather than honoured. The frozen note - *"does not resume after a reboot"* - is
 * the promise, and losing a timer to a process restart is a smaller failure than
 * firing one that belongs to a previous boot. There is no BOOT_COMPLETED receiver
 * anywhere in the app, so nothing re-arms across a reboot by any other path
 * either.
 */
object BootIdentity {

    /** Stored in place of a boot id when the platform would not give one. */
    const val UNKNOWN = Int.MIN_VALUE

    /** The current boot's id, or null when the platform will not answer. */
    fun read(context: Context): Int? = try {
        Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        null
    } catch (e: Exception) {
        // A provider that throws is the same situation as one that has no value:
        // we cannot tell which boot this is, so we must not claim to.
        null
    }

    /** What [read] should be persisted as. */
    fun toStored(bootId: Int?): Int = bootId ?: UNKNOWN

    /**
     * True only when the stored id is a real id and it is the id of this boot.
     * [UNKNOWN] on either side is a mismatch.
     */
    fun matches(stored: Int, current: Int?): Boolean =
        stored != UNKNOWN && current != null && current != UNKNOWN && stored == current
}
