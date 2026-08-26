package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.IdentityReconciler
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.databinding.FragmentProfileAuthenticatedBinding
import com.example.musicplayerapp.ui.auth.applyAuthInsets
import com.example.musicplayerapp.ui.profile.AvatarInitial
import com.example.musicplayerapp.ui.profile.ProfileAccount
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * profile-authenticated 2517:2671 / 2517:3638 - the screen this phase exists for.
 *
 * Until it landed, a listener who had just registered came back to the profile and
 * was told `Вы не вошли`, on the one screen they opened to check. The identity
 * underneath was right the whole time; only the presentation was lying.
 *
 * ## It verifies rather than assumes
 *
 * `REGISTERED` on disk is what routed here, and it is not proof of a live session:
 * a token revoked on another device, or an account deleted, still reads `REGISTERED`
 * locally until something asks. So this asks - through the existing machinery and
 * not a second copy of it - and steps aside to the guest screen if the answer is no.
 *
 * What it must never do while asking is equally fixed, and all of it is inherited
 * rather than re-implemented: no anonymous mint, no handoff, no Room write, no
 * fabricated session. [com.example.musicplayerapp.ui.profile.ProfileRoute] cannot
 * mint because it only reads preferences; `restore` cannot mint because it only
 * loads a session that already exists; and nothing here calls `ListenerSession
 * .identity`, which is the one function in the app that can.
 *
 * ## Three rows, one of which works
 *
 * `Аватар` and `Сменить пароль` are drawn exactly as the frame draws them, chevrons
 * and all, and are inert: the screens behind them are not in this PR. `Выйти` is
 * wired, because it is the only way out of the authenticated state and a disabled
 * one would strand somebody here.
 */
class ProfileAuthenticatedFragment : Fragment() {

    private var _binding: FragmentProfileAuthenticatedBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProfileAuthenticatedBinding.inflate(inflater, container, false)

        applyAuthInsets(binding.profileRoot, binding.profileScroll)

        binding.profileBack.setOnClickListener { findNavController().popBackStack() }
        binding.profileRowSignOut.setOnClickListener { signOut() }

        // Painted from storage first, so the card is never blank while the session is
        // being checked. The uid is already known; the name and address are filled in
        // a moment later if a session can supply them.
        render(name = null, email = null)
        renderLastSync()

        return binding.root
    }

    /**
     * The session check starts here rather than in `onCreateView`.
     *
     * `viewLifecycleOwner` does not exist until the view does, so a coroutine
     * launched during inflation has no scope tied to the view - it would either be
     * refused or, worse, outlive the view it is about to touch. `onViewCreated` is
     * the first moment both are true.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        verifySession()
    }

    /**
     * Asks who this device actually is, and leaves if the answer is not an account.
     *
     * `IdentityReconciler.reconcile` is the same call `MyataApplication` makes at
     * startup: it restores whatever session exists and repairs the persisted identity
     * around it. Running it here rather than reading the session directly is what
     * keeps one reconciliation algorithm in the app instead of two - including the
     * interrupted-logout case, which this screen can genuinely be opened into.
     */
    private fun verifySession() {
        viewLifecycleOwner.lifecycleScope.launch {
            val account = withContext(Dispatchers.IO) {
                val api = EmailAuthBackend.api(requireContext())
                // The session first: reconciliation needs to be told what restored,
                // and this reaches nothing that can mint.
                val sessionUid = api.currentUid()
                IdentityReconciler.reconcile(requireContext(), sessionUid)

                val state = IdentityStore.state(requireContext())
                if (state !is IdentityState.Registered) null else api.currentAccount()
            }

            if (_binding == null) return@launch

            if (account == null) {
                // Not an account any more - a logout finished by reconciliation, a
                // revoked token, a state nobody designed. The guest screen is the
                // presentation that claims least, and this destination is replaced
                // rather than stacked so Back cannot come back to a card that is no
                // longer true.
                findNavController().navigate(R.id.action_profile_authenticated_to_profile)
                return@launch
            }

            render(account.displayName, account.email)
        }
    }

    private fun render(name: String?, email: String?) {
        val fallbackName = getString(R.string.profile_account_name_fallback)

        binding.profileAccountName.text = ProfileAccount.displayName(name) ?: fallbackName
        binding.profileAccountEmail.text =
            ProfileAccount.email(email) ?: getString(R.string.profile_account_email_unavailable)
        binding.profileAccountAvatarInitial.text = ProfileAccount.initial(name, fallbackName)
        // Centred by the glyph's own metrics, not by the line box - see AvatarInitial
        // for why the difference is visible and which way the frame gets it wrong.
        AvatarInitial.centre(binding.profileAccountAvatarInitial)
    }

    /**
     * `Последняя синхронизация`, from what actually happened.
     *
     * Read only - opening this screen is not a sync, and a row that updated the
     * timestamp it displays would always read "только что".
     */
    private fun renderLastSync() {
        val at = LastSyncStore.lastSuccessAt(requireContext())

        binding.profileRowLastSyncValue.text = when (val ago = ProfileAccount.relativeSync(at)) {
            is ProfileAccount.Relative.Never -> getString(R.string.profile_account_sync_never)
            is ProfileAccount.Relative.JustNow -> getString(R.string.profile_account_sync_just_now)
            is ProfileAccount.Relative.Minutes ->
                getString(R.string.profile_account_sync_minutes, ago.count)
            is ProfileAccount.Relative.Hours ->
                getString(R.string.profile_account_sync_hours, ago.count)
            is ProfileAccount.Relative.Days ->
                getString(R.string.profile_account_sync_days, ago.count)
            // Past a week the relative form stops helping, so the device's own date
            // format takes over rather than this inventing one.
            is ProfileAccount.Relative.Older ->
                DateFormat.getMediumDateFormat(requireContext()).format(Date(ago.at))
        }
    }

    /**
     * `Выйти` - the frozen LOCAL logout, run by the repository that owns its ordering.
     *
     * The row is closed for the duration so a second tap cannot start a second
     * sign-out, and the navigation replaces this destination rather than stacking on
     * it: after signing out, Back must not be able to reach an account card that is
     * no longer true.
     */
    private fun signOut() {
        binding.profileRowSignOut.isEnabled = false
        binding.profileRowSignOut.isClickable = false

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { EmailAuthRepository.signOut(requireContext()) }

            if (_binding == null) return@launch
            findNavController().navigate(R.id.action_profile_authenticated_to_profile)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
