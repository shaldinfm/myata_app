package com.example.musicplayerapp.data.report

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.example.musicplayerapp.BuildConfig
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.Streams
import com.example.musicplayerapp.service.LastPlaybackError

/**
 * What the `Что будет отправлено` card says, and what actually leaves the device.
 *
 * ## One source, two renderings
 *
 * The frozen card exists so the listener can see the attachment **before**
 * sending rather than discover it afterwards. That promise only holds if the card
 * and the payload cannot disagree, so both come from one [collect] call: [lines]
 * is what is drawn, [wire] is what is posted, and every entry in one has a
 * counterpart in the other. A field added to the payload without a line on the
 * card is a field the listener was not shown, and `ReportPayloadTest` fails the
 * build for it.
 *
 * ## The whole list, and why each one is safe
 *
 * | line | value | why it cannot identify anybody |
 * |---|---|---|
 * | app | `3.6.5 (202611)` | the same for every install of a build |
 * | device | `Xiaomi Redmi Note 12` | a model, shared with millions; not a serial |
 * | android | `14 (API 34)` | a platform version |
 * | network | `Wi-Fi` / `Мобильная сеть` / `Нет сети` | the transport **class** only |
 * | last error | `ERROR_CODE_IO_BAD_HTTP_STATUS, 2 мин назад` or `нет` | a Media3 constant name |
 * | stream | `MYATA` | which of three public radio streams was selected |
 *
 * The network line is deliberately a class and not a network: no SSID, no
 * operator, no IP, no signal strength - an SSID is very often a household name or
 * an address, which is the exact thing this card promises is not being sent.
 *
 * ## What is not here, and is not reachable from here
 *
 * No access or refresh token, no `apikey`, no Authorization header, no
 * `auth.uid()`, no email, no display name, no avatar, no Collection, reaction or
 * history contents, no `ANDROID_ID`, serial, IMEI or advertising id, no location,
 * no logcat, no stack trace and no exception message. This class does not
 * construct a Supabase client, read `IdentityStore`, or touch the database - so
 * the absence is structural rather than a promise, and `ReportPayloadTest`
 * asserts it against the serialised body as well.
 */
object ReportDiagnostics {

    /** One captured snapshot, rendered two ways. */
    data class Snapshot(
        val appVersion: String,
        val device: String,
        val android: String,
        val network: String,
        val lastError: String,
        val stream: String,
    ) {
        /** The six lines of the frozen card, in the frozen order. */
        fun lines(context: Context): List<String> = listOf(
            context.getString(R.string.report_diagnostics_app, appVersion),
            context.getString(R.string.report_diagnostics_device, device),
            context.getString(R.string.report_diagnostics_android, android),
            context.getString(R.string.report_diagnostics_network, network),
            context.getString(R.string.report_diagnostics_last_error, lastError),
            context.getString(R.string.report_diagnostics_stream, stream),
        )

        /** The same six values as the fields the endpoint receives. */
        fun wire(): Map<String, String> = linkedMapOf(
            "app_version" to appVersion,
            "device" to device,
            "android" to android,
            "network" to network,
            "last_error" to lastError,
            "stream" to stream,
        )
    }

    /**
     * Reads the six values.
     *
     * [streamKey] is the selected stream as the app already knows it, normalised
     * through [Streams] so an unrecognised value becomes the default rather than
     * being echoed onto the card and into the payload. Nothing arriving from
     * outside the app reaches the wire as free text.
     */
    fun collect(context: Context, streamKey: String?): Snapshot = Snapshot(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        android = context.getString(
            R.string.report_diagnostics_android_value,
            Build.VERSION.RELEASE.orEmpty(),
            Build.VERSION.SDK_INT,
        ),
        network = networkClass(context),
        lastError = lastError(context),
        stream = streamName(context, streamKey),
    )

    /**
     * Wi-Fi, mobile, or nothing.
     *
     * `ACCESS_NETWORK_STATE` is already held for the reconnect policy, so this
     * asks a question the app already asks. Ethernet on a TV box and anything else
     * `NetworkCapabilities` can report both collapse into `Другая сеть` rather
     * than growing a taxonomy: the useful distinction for "the radio stopped" is
     * having a network at all, and whether it was the flaky kind.
     */
    private fun networkClass(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return context.getString(R.string.report_network_none)

        val transports: NetworkCapabilities? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.getNetworkCapabilities(cm.activeNetwork)
            } else {
                // API 24/25. getNetworkCapabilities(activeNetwork) needs 23+ for the
                // accessor and 23+ for activeNetwork; both exist here, but the
                // deprecated path below is what the rest of the app uses on 24 and
                // it answers the same question without a second code path to test.
                cm.allNetworks.firstOrNull { cm.getNetworkInfo(it)?.isConnected == true }
                    ?.let { cm.getNetworkCapabilities(it) }
            }

        return when {
            transports == null -> context.getString(R.string.report_network_none)
            transports.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                context.getString(R.string.report_network_wifi)
            transports.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                context.getString(R.string.report_network_mobile)
            else -> context.getString(R.string.report_network_other)
        }
    }

    /**
     * `ERROR_CODE_…, 2 мин назад`, or `нет`.
     *
     * Owner decision D3: when this process has captured no error the line says so
     * honestly rather than being hidden. A missing line would make the card six
     * entries in one report and five in the next, and "no error was recorded" is
     * itself evidence - it separates "the stream failed" from "the stream was fine
     * and the listener still could not hear anything".
     */
    private fun lastError(context: Context): String {
        val capture = LastPlaybackError.peek()
            ?: return context.getString(R.string.report_last_error_none)

        val agoMs = (SystemClock.elapsedRealtime() - capture.elapsedRealtimeMs).coerceAtLeast(0L)
        val minutes = agoMs / 60_000L
        val ago = if (minutes < 1L) {
            context.getString(R.string.report_last_error_just_now)
        } else {
            context.getString(R.string.report_last_error_minutes_ago, minutes)
        }
        return context.getString(R.string.report_last_error_value, capture.codeName, ago)
    }

    /** MYATA / GOLD / XTRA - the names the app already shows, and no bitrate. */
    private fun streamName(context: Context, streamKey: String?): String =
        when (Streams.normalise(streamKey) ?: Streams.DEFAULT) {
            Streams.GOLD -> context.getString(R.string.report_stream_gold)
            Streams.XTRA -> context.getString(R.string.report_stream_xtra)
            else -> context.getString(R.string.report_stream_myata)
        }
}
