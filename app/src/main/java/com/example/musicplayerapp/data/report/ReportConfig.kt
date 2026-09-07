package com.example.musicplayerapp.data.report

import androidx.annotation.VisibleForTesting
import com.example.musicplayerapp.BuildConfig

/**
 * Where a problem report goes, and whether this build can send one at all.
 *
 * The endpoint comes from `report.properties` in the project root through
 * `BuildConfig`, the same untracked-file route Supabase and release signing use.
 * It is a capability URL rather than a secret: it ships in the APK and anyone who
 * downloads the app can read it. What must never be here - or anywhere in the
 * client - is the Telegram bot token the endpoint forwards to. A token in an APK
 * is extractable, and lets a stranger post as the bot and read its updates.
 *
 * ## Unconfigured is an ordinary state, and it is what removes the entry points
 *
 * [isConfigured] being false is supported, not an error. CI and every fresh clone
 * build without the file, and the rule those builds live under is that **both**
 * `Сообщить о проблеме` rows - the PLAYER overflow's and Settings' - are absent.
 *
 * That is not defensive coding. It is the same rule `SettingsLayoutTest` has held
 * since G1 and `SleepTimerSurfacesTest` since G2: a row is drawn when the feature
 * behind it exists, and is absent otherwise. A form that cannot post is a feature
 * that does not exist, and a row that opens it is a dead control that says
 * otherwise.
 */
object ReportConfig {

    /**
     * Test-only endpoint, and the only way instrumentation can reach the form.
     *
     * G3 ships before its endpoint is deployed, so every build in existence - CI,
     * a fresh clone, this developer's - is currently the unconfigured one, and the
     * five frozen frames would be untestable on a device without this. The suites
     * set it both ways round: `ReportProblemFlowTest` to something, to reach the
     * screen at all, and `ReportEntryPointsTest` to null, to prove that both rows
     * then disappear.
     *
     * It changes nothing in a shipped app: nothing in `src/main` writes it, and a
     * release build has no code that can.
     */
    @VisibleForTesting
    @Volatile
    var endpointOverrideForTest: String? = null

    val endpoint: String
        get() = endpointOverrideForTest ?: BuildConfig.REPORT_ENDPOINT

    /**
     * Whether this build can send a report.
     *
     * The `https` check is not decoration, for the reason
     * [com.example.musicplayerapp.data.supabase.SupabaseConfig] gives about its
     * own: `network_security_config` permits cleartext for exactly one host, the
     * audio stream, so an endpoint that arrived without a scheme would fail at the
     * TLS layer in a way that reads like an outage rather than like a typo in a
     * properties file. And a report posted in the clear would put the listener's
     * own words on the wire for anyone on the network to read.
     */
    val isConfigured: Boolean
        get() = endpoint.startsWith("https://")
}
