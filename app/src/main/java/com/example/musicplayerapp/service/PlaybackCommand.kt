package com.example.musicplayerapp.service

/**
 * One app-private playback command, and the only path one can travel.
 *
 * ## Why this is not an `Intent` any more
 *
 * These commands - `play`, `startStop`, `switch`, `switch_track`, `get_status`,
 * `stop`, the four sleep-timer actions and the two debug seams - used to reach
 * [MediaPlayerService] as `ACTION` / `STREAM` / `ARTIST` / `SONG` / `force_play`
 * extras on a start intent.
 *
 * The service is a `MediaSessionService` and it is `android:exported="true"`, so
 * *any* app on the device could send exactly that intent, with exactly those
 * extras, and have this app start a station, stop it, rewrite what the
 * notification says is playing, or arm a timer. That is the finding this file
 * closes, and it could not be closed by guarding the extras: `onStartCommand` is
 * handed no caller identity, so an exported component cannot authenticate a start
 * command at all. A start intent that carries instructions is a start intent any
 * app may send.
 *
 * So the instructions stopped being an intent and became process memory, which no
 * other app can reach:
 *
 * ```
 *   Activity / Fragment / ViewModel        (this process)
 *            │
 *            ├── PlaybackCommands.enqueue(command) ──┐
 *            │                                       │
 *            └── bare start intent, no extras ───────┤
 *                                                    ▼
 *                        MediaPlayerService.onStartCommand
 *                             drain() ──▶ run the commands
 * ```
 *
 * The start intent is now a wake-up and a lifecycle signal and nothing else: it
 * carries no instruction, so no external app can make one mean anything. The
 * exported endpoint stays exported - it is the Media3 session the platform and
 * other media controllers discover, and it is unchanged - but what it exposes is
 * a session, not a remote control for the app's own state.
 *
 * ## What is deliberately not used
 *
 * No caller package or component check, no unguessable action name, no shared
 * secret: none of those is a boundary - the first two are trivially spoofed and
 * the third is a constant in a shipping APK - and each would have looked like
 * one. The boundary here is the process.
 *
 * ## The one thing that still has to ride the start
 *
 * Whether the start was a `startForegroundService` call, because Android gives
 * the service five seconds to call `startForeground` after one and the caller is
 * the only one who knows. It travels as [openForeground]; failing to post the
 * placeholder would crash the app, and posting it on a start nobody asked for
 * would be a notification out of nowhere.
 *
 * ## Threading
 *
 * Commands are enqueued by the thread that handled the gesture (in practice the
 * main thread) and drained by the service's own `onStartCommand`, which runs on
 * the main thread. The queue synchronizes anyway, so the invariant does not
 * depend on that staying true and an instrumentation suite may post from its own
 * thread.
 */
internal class PlaybackCommand(
    val action: String,
    val stream: String? = null,
    val artist: String? = null,
    val song: String? = null,
    val forcePlay: Boolean = false,
    val minutes: Int = 0,
    val isCustom: Boolean = false,
    val openForeground: Boolean = false,
)

/**
 * The queue between the app's own screens and the one service that owns playback.
 *
 * Deliberately tiny and deliberately memory-only, and deliberately a plain
 * object rather than a component: anything that can be reached by name from
 * outside this process is a surface, and this one is not a surface at all. A
 * command that is not handed over by this process in this process's lifetime is
 * a command that does not exist - which is exactly the property that keeps the
 * protocol private, and the reason there is nothing here to persist.
 */
internal object PlaybackCommands {

    private val pending = ArrayDeque<PlaybackCommand>()

    /**
     * Puts one command in the queue and hands it back, so a start that fails can
     * take back the same object.
     */
    fun enqueue(command: PlaybackCommand): PlaybackCommand {
        synchronized(pending) { pending.addLast(command) }
        return command
    }

    /**
     * Takes one command back out, unused.
     *
     * This is for a refused start - Android 12+ declining a background
     * `startForegroundService`, say. A command that never reached the service has
     * to leave nothing behind, or the next start of any kind, minutes later and
     * for a different reason, would silently act on a gesture the listener had
     * already stopped expecting anything from.
     *
     * Identity, not equality: it takes back the object [enqueue] returned.
     */
    fun withdraw(command: PlaybackCommand) {
        synchronized(pending) { pending.remove(command) }
    }

    /**
     * Everything waiting, in the order it was enqueued, and empty again.
     *
     * All of it rather than one at a time: two gestures can be enqueued before the
     * service's first start command runs, and draining half of them would leave
     * the rest to whatever unrelated start came next - still in order, which is
     * what the queue is for, but later than the caller meant.
     */
    fun drain(): List<PlaybackCommand> {
        synchronized(pending) {
            if (pending.isEmpty()) return emptyList()
            val all = pending.toList()
            pending.clear()
            return all
        }
    }
}
