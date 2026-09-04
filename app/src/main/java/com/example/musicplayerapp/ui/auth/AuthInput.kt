package com.example.musicplayerapp.ui.auth

/**
 * What the two auth forms will accept before anything is sent anywhere.
 *
 * Pure, and deliberately so: every rule here is a claim about a string, provable in
 * a unit test in microseconds, and none of it needs a device, a screen or a network.
 *
 * ## It is a courtesy, not a gate
 *
 * The server is the authority on every one of these. Local validation exists to save
 * somebody a round trip and a vague failure - not to enforce a policy, and above all
 * not to invent one. A client that refuses input the server would have accepted is a
 * client telling the listener a rule that does not exist, and there is no way for
 * them to discover it is wrong.
 *
 * That is why the password rule below is exactly the one the design states and not a
 * character more. No uppercase requirement, no digit requirement, no symbol
 * requirement: Supabase enforces a minimum length and nothing else unless the project
 * is configured otherwise, and if it ever refuses a password for a reason of its own
 * it says so - `AuthFailure.WeakOrInvalidPassword` carries the rule that was missed.
 */
object AuthInput {

    /**
     * `Минимум 8 символов`, which is what auth-create-account 2517:2640 says.
     *
     * The screen states it, so the screen may enforce it. Nothing else may.
     */
    const val MIN_PASSWORD_LENGTH = 8

    /**
     * A deliberately loose address check.
     *
     * It asks three things: something before the `@`, something after it, and at
     * least one dot with something on both sides. It does not attempt RFC 5322,
     * because the strict grammar admits addresses no mail server would route and
     * every practical approximation of it rejects addresses that work. The cost of
     * being too loose is one round trip and `AuthFailure.InvalidEmail`; the cost of
     * being too strict is somebody who cannot use their own address, ever, with no
     * way to find out why.
     *
     * Spaces are excluded rather than trimmed *inside* the value: [email] already
     * trims the ends, so a space that survives is one in the middle, and that is a
     * typo rather than an address.
     */
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$")

    /** The name as it will be stored - `user_metadata.display_name`. */
    fun name(raw: String): String = raw.trim()

    /** The address as it will be sent. Trimmed, because a trailing space is a slip. */
    fun email(raw: String): String = raw.trim()

    /** A name is anything that is not only whitespace. There is no uniqueness rule. */
    fun isNameValid(raw: String): Boolean = name(raw).isNotEmpty()

    fun isEmailValid(raw: String): Boolean = EMAIL.matches(email(raw))

    /**
     * Long enough to create an account with.
     *
     * **Not trimmed.** Leading and trailing spaces are part of a password: trimming
     * one would send a different secret than the person typed, and they would then
     * be unable to sign in anywhere that did not make the same mistake.
     */
    fun isPasswordLongEnough(raw: String): Boolean = raw.length >= MIN_PASSWORD_LENGTH

    /**
     * All a *sign-in* asks of a password: that there is one.
     *
     * Deliberately not [isPasswordLongEnough]. The minimum is a rule about creating
     * an account, and auth-sign-in 2517:2603 states no rule at all. Applying the
     * create-account minimum here would lock out any account whose password predates
     * it - Supabase's own default minimum is six - and it would do so with a message
     * that is simply false about an account that exists and works.
     */
    fun isPasswordPresent(raw: String): Boolean = raw.isNotEmpty()

    /**
     * The recovery code as it will be sent.
     *
     * Trimmed, and only trimmed. A code copied out of a mail client arrives with a
     * trailing space or a newline more often than not, and neither is part of the
     * token - but nothing in the middle is touched, because the shape of `{{ .Token }}`
     * is the server's to decide and an app that "cleaned" it would refuse codes that
     * are correct.
     */
    fun code(raw: String): String = raw.trim()

    /** A code is anything that is not only whitespace. Its length is not ours to judge. */
    fun isCodePresent(raw: String): Boolean = code(raw).isNotEmpty()
}
