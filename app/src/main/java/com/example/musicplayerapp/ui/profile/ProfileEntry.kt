package com.example.musicplayerapp.ui.profile

import android.content.Context
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.AccountInfo
import com.example.musicplayerapp.data.supabase.AccountRefresh
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.KnownAccount
import com.example.musicplayerapp.fragments.ProfileAvatarFragment
import com.example.musicplayerapp.ui.HomeGreeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 40x40 profile control in the section headers - HOME, ABOUT US and the empty
 * COLLECTION - and the one place that decides what it shows.
 *
 * It used to live inside MainFragment, so only HOME ever showed the account's
 * avatar while ABOUT US and the empty COLLECTION kept the layout's person glyph for
 * the same signed-in listener (owner decision G6a, reversed in the UI polish pass).
 * Every header now paints the same state through the same code, and a changed
 * avatar reaches all of them the same way: [KnownAccount] is updated in place when
 * the avatar is saved, and each header re-paints on resume.
 *
 * [Header] is what the control can show:
 *
 *  - [Header.Guest] - the generic person glyph;
 *  - [Header.Account] - the account's avatar, or the glyph when it has none;
 *  - [Header.Unresolved] - a registered install whose session has not been read
 *    yet: neither the glyph nor an avatar, so neither answer is claimed early.
 */
object ProfileEntry {

    /** The control's tag while the account is unresolved - see [Header.Unresolved]. */
    const val NEUTRAL_TAG = "account-unresolved"

    sealed interface Header {
        object Guest : Header
        object Unresolved : Header
        data class Account(val name: String?, val avatar: ProfileAvatar?) : Header
    }

    /**
     * What this process already knows, synchronously: one `SharedPreferences` read
     * the startup gate has already loaded. Never the guest default for somebody
     * who may be signed in.
     */
    fun known(context: Context): Header {
        val state = IdentityStore.state(context)
        val known = KnownAccount.of(state)
        return when {
            state !is IdentityState.Registered -> Header.Guest
            known != null ->
                Header.Account(ProfileAccount.displayName(known.displayName), ProfileAvatars.resolve(known.avatarId))
            KnownAccount.isAbsent(state) -> Header.Guest
            else -> Header.Unresolved
        }
    }

    /**
     * Reads the account from the stored session, off the main thread, and records
     * the answer in [KnownAccount]. Null when the read failed: that says nothing
     * about who this is, so the header keeps what it shows.
     *
     * Never mints: `currentAccount` reads the session the Auth plugin already holds.
     * A registered install waits for the local session restore first, so a cold
     * start does not read "no account" for a signed-in listener.
     */
    suspend fun resolve(context: Context): Header? {
        val state = withContext(Dispatchers.IO) { IdentityStore.state(context) }
        if (state !is IdentityState.Registered) {
            KnownAccount.forget()
            return Header.Guest
        }
        val read: Result<AccountInfo?> = withContext(Dispatchers.IO) {
            runCatching {
                val api = EmailAuthBackend.api(context)
                api.awaitSessionRestored()
                // Stored, not live: an offline expired token is still this account.
                api.storedAccount()
            }
        }
        if (read.isFailure) return null
        val account = read.getOrNull()
        if (account != null && account.uid == state.uid) KnownAccount.remember(account)
        else KnownAccount.markAbsent(state.uid)
        return Header.Account(HomeGreeting.name(state) { account }, HomeGreeting.avatar(state) { account })
    }

    /**
     * At most once a minute, brings the session's copy of the account up to date
     * with the server, so an avatar or name changed on another device reaches the
     * headers without a new sign-in. Null - offline, a guest, nothing newer, or not
     * due yet - keeps the header.
     */
    suspend fun refreshed(context: Context): Header? = withContext(Dispatchers.IO) {
        runCatching {
            AccountRefresh.refresh(context.applicationContext, AccountRefresh.HOME_MIN_INTERVAL_MS)
        }.getOrNull()?.let { account ->
            val current = IdentityStore.state(context)
            Header.Account(HomeGreeting.name(current) { account }, HomeGreeting.avatar(current) { account })
        }
    }

    /**
     * Paints [known], then [resolve] and [refreshed], into [icon] for as long as
     * [fragment]'s view lives - everything a header needs, in one call. [onHeader]
     * sees each header as it is painted, for the one screen that also greets by name.
     */
    fun bind(fragment: Fragment, icon: ImageView, onHeader: (Header) -> Unit = {}) {
        val context = fragment.requireContext()
        val paintNow = { header: Header ->
            paint(icon, header)
            onHeader(header)
        }
        paintNow(known(context))
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            resolve(context)?.let(paintNow)
            refreshed(context)?.let(paintNow)
        }
    }

    fun paint(icon: ImageView, header: Header) {
        when (header) {
            Header.Guest -> paintGlyph(icon)
            Header.Unresolved -> {
                icon.setImageDrawable(null)
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                icon.setBackgroundResource(R.drawable.bg_profile_entry)
                icon.clipToOutline = false
                icon.tag = NEUTRAL_TAG
            }
            is Header.Account -> {
                val avatar = header.avatar
                if (avatar == null) {
                    paintGlyph(icon)
                } else {
                    icon.setImageResource(avatar.drawable)
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    // No disc behind the artwork: its anti-aliased rim would show as a fringe.
                    icon.background = null
                    ProfileAvatarFragment.clipToCircle(icon)
                    icon.tag = avatar.key
                }
            }
        }
    }

    /** The generic person glyph on its disc - the layout's own default. */
    private fun paintGlyph(icon: ImageView) {
        icon.setImageResource(R.drawable.ic_profile_entry)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.setBackgroundResource(R.drawable.bg_profile_entry)
        icon.clipToOutline = false
        icon.tag = null
    }
}
