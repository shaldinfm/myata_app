package com.example.musicplayerapp.service

/**
 * What one **handled** command tells the start path that ran it.
 *
 * Two different questions live here, and they used to be one `Boolean`:
 *
 * ```
 *   was this command handled?             -> a fact about the handler
 *   should this start keep the service?   -> the app's own answer about stickiness
 * ```
 *
 * One `Boolean` carried both, and that is the P1 this type exists for. The pass folded the
 * answer with a short-circuiting `&&`, so the first command that answered "do not keep me"
 * - a `switch` that arrived without a station - also stopped every command behind it from
 * being *offered to its handler*, while the inbox went on acknowledging them. A timer set
 * behind such a switch was consumed without ever arming a timer: the listener's gesture was
 * recorded as delivered and nothing happened.
 *
 * So the two are separate now:
 *
 *  - a handler that returns at all has handled its command - this value only exists because
 *    one did, and a handler that *failed* raises [PlaybackCommandFailure] instead;
 *  - [keepSticky] is the one answer about the start that the service itself owns. A
 *    [PlaybackCommandPass] folds it across a whole pass, and it never decides whether a
 *    later command is handled.
 */
internal class CommandHandling private constructor(val keepSticky: Boolean) {

    companion object {

        /** Handled, and this start keeps the service sticky - every command but one. */
        val KEEP_STICKY = CommandHandling(true)

        /**
         * Handled, and this start must **not** keep the service sticky.
         *
         * One command answers this and only one (see [forCommand]); its handler still runs,
         * and it is still acknowledged like any other. The service it woke is simply not
         * held on to afterwards - the answer a station-less `switch` has always given.
         */
        val RELEASE_STICKY = CommandHandling(false)

        /**
         * The answer [command] gives the start that runs it - a pure function of the record,
         * so the rule can be read without a service and without a device.
         *
         * A `switch` that arrived without a station is the gesture that names nothing: there
         * is no station to install and nothing else for it to do, so it is the one command
         * that answers "do not keep me". Everything else - including a `switch` that *does*
         * name a station - leaves the service sticky, which is what it has always done.
         */
        fun forCommand(command: PlaybackCommand): CommandHandling =
            if (command.action == "switch" && command.stream == null) RELEASE_STICKY else KEEP_STICKY
    }
}

/**
 * A command whose handling genuinely failed, raised from inside its handler.
 *
 * The inbox treats a handler that throws as "this command has not been delivered": the
 * record is not acknowledged, it stays pending, this pass stops there and a later start
 * retries it ([PlaybackCommandInbox.drain]). That is the only way a handler can report a
 * failure - a normal return **is** the acknowledgement - so a handler that could not do
 * what its command says must raise rather than return.
 *
 * The one command that needed this spelled out is an undo whose snapshot could not be
 * consumed ([SleepTimerStore.Consumed.NotConsumed]): it did nothing at all, and returning
 * from there would have recorded a `Вернуть` that never happened as done.
 */
internal class PlaybackCommandFailure(message: String) : RuntimeException(message)

/**
 * One pass over the durable inbox, with the two answers a pass produces kept apart.
 *
 * [PlaybackCommandInbox.drain] owns the order, the acknowledgement, supersession and what a
 * failed handler means; this class owns the one thing the service adds to that - the
 * per-command answer about stickiness - and it exists as its own class because this is the
 * seam where "handled" and "keep the service" could most easily be conflated again.
 *
 * The contract, in full:
 *
 *  - every eligible head the inbox offers is handed to [handle], **whatever the commands
 *    before it answered** and whatever [keepSticky] already is. The answer about stickiness
 *    is read *after* the handler returned and folded; it is never a gate in front of one;
 *  - a handler that returns means its command was handled, and the inbox acknowledges it as
 *    such - including the station-less `switch` that released stickiness;
 *  - a handler that throws ([PlaybackCommandFailure], or any other exception) has failed,
 *    and the inbox leaves that command and everything behind it pending;
 *  - [keepSticky] is one-way. Once a command has said this start should not keep the
 *    service, that is the answer for the whole start; a later command is still handled and
 *    still acknowledged, it just does not resurrect a service an earlier one let go.
 *
 * One pass at a time is a property of the thread that calls [run]: the service calls it from
 * `onStartCommand`, on the main thread, exactly as the inbox requires.
 */
internal class PlaybackCommandPass(
    private val handle: (PlaybackCommandInbox.Entry) -> CommandHandling,
) {

    /**
     * Whether this start should hand `START_STICKY` back to the platform.
     *
     * True until a command answers otherwise; [CommandHandling.RELEASE_STICKY] is the only
     * answer that can change it, and nothing can change it back.
     */
    var keepSticky: Boolean = true
        private set

    /** Runs everything waiting, oldest first, one command at a time. */
    fun run(
        inbox: PlaybackCommandInbox,
        prepare: (PlaybackCommandInbox.Entry) -> Boolean = { true },
    ) {
        inbox.drain(prepare = prepare) { entry ->
            val handling = handle(entry)
            // Folded, never branched on before the fact: the handler above has already run.
            if (!handling.keepSticky) keepSticky = false
        }
    }
}
