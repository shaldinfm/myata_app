package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.AccountRefresh
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.IdentityReconciler
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.KnownAccount
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.databinding.FragmentProfileAuthenticatedBinding
import com.example.musicplayerapp.ui.auth.applyAuthInsets
import com.example.musicplayerapp.ui.profile.AvatarInitial
import com.example.musicplayerapp.ui.profile.DeleteAccountViewModel
import com.example.musicplayerapp.ui.profile.ProfileAccount
import com.example.musicplayerapp.ui.profile.ProfileAvatars
import com.example.musicplayerapp.ui.profile.leavesTheAccountScreen
import com.example.musicplayerapp.ui.profile.message
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Toast
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
 * ## It verifies again, having been routed here on a verified session
 *
 * [com.example.musicplayerapp.ui.profile.ProfileRoute] proves `REGISTERED(X)` and a
 * restored session for the same `X` **before** navigating, so this screen is not
 * where that decision is taken. It re-checks anyway, for the cases routing cannot
 * cover: arriving straight from the auth screens, and a session that ends while the
 * screen is open. The check goes through the existing machinery rather than a second
 * copy of it, and steps aside to the guest screen if the answer is no.
 *
 * Until the answer arrives the account card is `INVISIBLE` rather than filled with
 * fallbacks. Painting `Пользователь` on a card for somebody who turns out not to be
 * authenticated is the same class of lie as `Вы не вошли` to somebody who is.
 *
 * What it must never do while asking is equally fixed, and all of it is inherited
 * rather than re-implemented: no anonymous mint, no handoff, no Room write, no
 * fabricated session. [com.example.musicplayerapp.ui.profile.ProfileRoute] cannot
 * mint because it only reads preferences; `restore` cannot mint because it only
 * loads a session that already exists; and nothing here calls `ListenerSession
 * .identity`, which is the one function in the app that can.
 *
 * ## Three rows, two of which work
 *
 * `Сменить пароль` is drawn exactly as the frame draws it, chevron and all, and is
 * inert: the screen behind it is not built. `Аватар` opens the picker (G6a). `Выйти`
 * is wired, because it is the only way out of the authenticated state and a disabled
 * one would strand somebody here.
 */
class ProfileAuthenticatedFragment : Fragment() {

    private var _binding: FragmentProfileAuthenticatedBinding? = null
    private val binding get() = _binding!!

    /**
     * Scoped to the fragment, not the view, and that is the point: a rotation destroys
     * the view and rebuilds the row, while the deletion request underneath carries on.
     * The guard that stops a second request lives with the work, not with the button.
     */
    private val deletion: DeleteAccountViewModel by viewModels()

    /** The address the final confirmation names. Filled by [render]; null until then. */
    private var accountEmail: String? = null

    /**
     * The confirmation currently on screen, so it can be dismissed with the view.
     *
     * A dialog outlives the fragment's view otherwise, and a rotation with one open
     * leaks its window - `android.view.WindowLeaked`, and on the way out the listener
     * loses a confirmation they were half way through. Kept `internal` rather than
     * private because the instrumentation drives these buttons directly: Espresso's
     * root picker needs the dialog window to take focus, and on the emulator it does
     * not, which is a fact about the harness rather than about this screen.
     */
    internal var confirmation: AlertDialog? = null
        private set

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProfileAuthenticatedBinding.inflate(inflater, container, false)

        applyAuthInsets(binding.profileRoot, binding.profileScroll)

        binding.profileBack.setOnClickListener { findNavController().popBackStack() }
        binding.profileRowSignOut.setOnClickListener { signOut() }
        binding.profileRowAvatar.setOnClickListener { openAvatarPicker() }
        ProfileAvatarFragment.clipToCircle(binding.profileAccountAvatarImage)
        binding.profileRowDeleteAccount.setOnClickListener { confirmDeletion() }

        // The card is deliberately **not** painted here. ProfileRoute has already
        // proved a matching session before this destination was navigated to, so the
        // account arrives in the same frame - and painting fallbacks first would put
        // `Пользователь` and `Email недоступен` on screen for anyone whose session
        // turned out to be gone, which is rendering an account card for somebody who
        // is not authenticated.
        //
        // The exception is an account this process has already verified for the same uid
        // (KnownAccount): that is not a fallback, and hiding it for the frames the check
        // takes is what made the name blink out and back on every open. The check below
        // still runs, redraws from the session, and leaves if the answer is no.
        val known = KnownAccount.of(IdentityStore.state(requireContext()))
        if (known != null) {
            render(known.displayName, known.email, known.avatarId)
            binding.profileAccountCard.visibility = View.VISIBLE
        } else {
            binding.profileAccountCard.visibility = View.INVISIBLE
        }
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
        observeDeletion()
        observeAvatarPicker()
    }

    // ------------------------------------------------- account deletion --

    /**
     * `Удалить аккаунт` - the first of two confirmations, and it deletes nothing.
     *
     * Two steps because the contract requires them, and they are genuinely different
     * questions: the first states what is destroyed, the second names the account and
     * asks whether to do it anyway. Both are dismissible; only the second's
     * destructive button reaches [AccountDeletion].
     *
     * `MaterialAlertDialogBuilder` rather than a custom layout: the app's dialog idiom
     * is a themed bottom sheet for *choices*, and this is a confirmation, which is
     * exactly what the Material dialog is for. Nothing here needs a bespoke surface -
     * only the app's own card, type and colour, which `ThemeOverlay.Myata.ConfirmDialog`
     * lays over the stock one.
     */
    private fun confirmDeletion() {
        if (deletion.isBusy) return

        confirmation = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Myata_ConfirmDialog)
            .setTitle(R.string.delete_account_confirm_title)
            .setMessage(R.string.delete_account_confirm_body)
            .setNegativeButton(R.string.delete_account_cancel, null)
            .setPositiveButton(R.string.delete_account_confirm_continue) { _, _ ->
                confirmDeletionFinally()
            }
            .show()
    }

    /**
     * The last question, naming the account, and the only place the orchestrator is
     * invoked.
     *
     * `setCancelable(true)` up to the moment the destructive button is pressed - after
     * that the durable marker is already committed and there is nothing left to
     * cancel, which the row's disabled state and its spinner then say.
     */
    private fun confirmDeletionFinally() {
        if (deletion.isBusy) return

        val email = accountEmail
            ?: getString(R.string.profile_account_email_unavailable)

        confirmation = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Myata_ConfirmDialog_Destructive)
            .setTitle(R.string.delete_account_final_title)
            .setMessage(getString(R.string.delete_account_final_body, email))
            .setNegativeButton(R.string.delete_account_cancel, null)
            .setPositiveButton(R.string.delete_account_final_confirm) { _, _ ->
                deletion.delete()
            }
            .show()
    }

    /**
     * The row's two states and the five outcomes.
     *
     * `viewLifecycleOwner`, so a rotation re-subscribes rather than leaking - and the
     * ViewModel outlives that, which is what makes the in-flight request survive.
     */
    private fun observeDeletion() {
        deletion.state.observe(viewLifecycleOwner) { state ->
            if (_binding == null) return@observe

            // Disabled *and* visibly busy. The disabled flag is what stops a second
            // tap on this view; the ViewModel's job is what stops a second request.
            binding.profileRowDeleteAccount.isEnabled = !state.loading
            binding.profileRowDeleteAccount.isClickable = !state.loading
            binding.profileRowDeleteAccountProgress.visibility =
                if (state.loading) VISIBLE else GONE

            val outcome = state.outcome ?: return@observe

            outcome.message()?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
            }

            // Consumed before navigating, so a rotation cannot replay it.
            deletion.consumeOutcome()

            if (outcome.leavesTheAccountScreen()) {
                // The same exit the sign-out uses. Where the listener lands is decided
                // by the guest screen itself, which reads the deletion marker -
                // deleted, or one of the two pending presentations.
                leaveForGuest()
            }
        }
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

            if (account != null) KnownAccount.remember(account) else KnownAccount.forget()
            if (_binding == null) return@launch

            if (account == null) {
                // Not an account any more - a logout finished by reconciliation, a
                // revoked token, a state nobody designed, or a deletion that has just
                // finished underneath this check. The guest screen is the presentation
                // that claims least, and the destination is replaced rather than
                // stacked so Back cannot come back to a card that is no longer true.
                leaveForGuest()
                return@launch
            }

            render(account.displayName, account.email, account.avatarId)
            binding.profileAccountCard.visibility = View.VISIBLE

            // G6a: the card was drawn from the session's stored copy of the account. Ask
            // the server once, so an avatar or name saved on another device appears here -
            // and is stored for HOME and Settings - without a new sign-in. Offline or any
            // failure returns null and the card stays exactly as drawn; nobody is signed out.
            //
            // A Save handed back while this refresh is in flight is newer than whatever the
            // refresh returns, so a refresh that started before it is not drawn over it.
            val startedAt = pickerResultGeneration
            val refreshed = withContext(Dispatchers.IO) {
                runCatching { AccountRefresh.refresh(requireContext().applicationContext) }.getOrNull()
            } ?: return@launch
            if (_binding == null || refreshed.uid != account.uid) return@launch
            if (pickerResultGeneration != startedAt) return@launch
            render(refreshed.displayName, refreshed.email, refreshed.avatarId)
        }
    }

    private fun render(name: String?, email: String?, avatarId: String?) {
        val fallbackName = getString(R.string.profile_account_name_fallback)
        // Kept for the final confirmation, which names the account rather than asking
        // about it in the abstract.
        accountEmail = ProfileAccount.email(email)

        binding.profileAccountName.text = ProfileAccount.displayName(name) ?: fallbackName
        binding.profileAccountEmail.text =
            ProfileAccount.email(email) ?: getString(R.string.profile_account_email_unavailable)
        binding.profileAccountAvatarInitial.text = ProfileAccount.initial(name, fallbackName)
        // Centred by the glyph's own metrics, not by the line box - see AvatarInitial
        // for why the difference is visible and which way the frame gets it wrong.
        AvatarInitial.centre(binding.profileAccountAvatarInitial)

        renderAvatar(avatarId)
    }

    /**
     * The avatar in the card's 64dp circle, or the frame's initial when there is none.
     *
     * An unknown key is no avatar: the frame's initial, never a guess.
     */
    private fun renderAvatar(avatarId: String?) {
        val avatar = ProfileAvatars.resolve(avatarId)
        if (avatar != null) {
            binding.profileAccountAvatarImage.setImageResource(avatar.drawable)
            binding.profileAccountAvatarImage.visibility = View.VISIBLE
            binding.profileAccountAvatarInitial.visibility = View.GONE
            // Off, or the `primary` disc would show as a fringe round the artwork's
            // anti-aliased edge.
            binding.profileAccountAvatar.background = null
            binding.profileAccountAvatar.contentDescription = getString(
                R.string.profile_account_avatar_description,
                getString(R.string.avatar_numbered, avatar.number, ProfileAvatars.all.size),
            )
        } else {
            binding.profileAccountAvatarImage.setImageDrawable(null)
            binding.profileAccountAvatarImage.visibility = View.GONE
            binding.profileAccountAvatarInitial.visibility = View.VISIBLE
            binding.profileAccountAvatar.setBackgroundResource(R.drawable.bg_profile_avatar_account)
            binding.profileAccountAvatar.contentDescription = getString(
                R.string.profile_account_avatar_description,
                getString(R.string.avatar_none_description),
            )
        }
    }

    /**
     * What the picker hands back when it closes.
     *
     * The card is otherwise drawn only when this view is created, and returning from the
     * picker does not always create it: a Save that lands while the forward transition is
     * still running pops straight back to the *same* view, which never re-reads the
     * account. So the picker says what it saved, and whether the session needs checking
     * again, and this applies it to whichever view is showing. Both are taken once.
     */
    /**
     * Bumped whenever the picker hands back a saved avatar, so a refresh that started before
     * it can tell its answer is older than what the card now shows.
     */
    private var pickerResultGeneration = 0

    private fun observeAvatarPicker() {
        val handle = findNavController().currentBackStackEntry?.savedStateHandle ?: return
        handle.getLiveData<String?>(ProfileAvatarFragment.RESULT_SAVED_AVATAR, null)
            .observe(viewLifecycleOwner) { saved ->
                if (_binding == null || saved == null) return@observe
                pickerResultGeneration++
                renderAvatar(saved)
                // Cleared rather than removed: removing drops the LiveData this view observes.
                handle[ProfileAvatarFragment.RESULT_SAVED_AVATAR] = null
            }
        handle.getLiveData(ProfileAvatarFragment.RESULT_VERIFY_AGAIN, false)
            .observe(viewLifecycleOwner) { again ->
                if (_binding == null || !again) return@observe
                handle[ProfileAvatarFragment.RESULT_VERIFY_AGAIN] = false
                verifySession()
            }
    }

    /**
     * `Row / Аватар`. Only from this destination, so a second tap while the first
     * navigation is under way finds the picker already current and does nothing - the
     * action's `launchSingleTop` is the second line of the same defence.
     */
    private fun openAvatarPicker() {
        if (_binding == null) return
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.profile_authenticated) return
        controller.navigate(R.id.action_profile_authenticated_to_profile_avatar)
    }

    /**
     * `Последняя синхронизация`, from what actually happened.
     *
     * Read only - opening this screen is not a sync, and a row that updated the
     * timestamp it displays would always read "только что".
     */
    private fun renderLastSync() {
        // The account this screen is showing, and only that account. Timestamps are
        // per listener: an install that signed out of one and into another has
        // synchronised nothing as the new one, and showing the old one's time would
        // answer a question nobody asked.
        //
        // The more recent of the two directions, not the upload alone. Until G-A7c
        // there was only one direction to report; now a device that has restored an
        // account but not yet pushed anything has genuinely synchronised, and saying
        // otherwise was the last place this screen still claimed something untrue.
        val uid = (IdentityStore.state(requireContext()) as? IdentityState.Registered)?.uid
        val at = uid?.let { LastSyncStore.lastSyncAt(requireContext(), it) }

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

            leaveForGuest()
        }
    }

    /**
     * Leaves for the guest profile, at most once.
     *
     * Three things can now decide this screen should not be on display: the session
     * check concluding this install is not an account, a sign-out, and a deletion that
     * ended the account. They run on different coroutines and any two can finish in
     * either order - and the second one to call `navigate` used to crash, because by
     * then the current destination is already `profile` and the action does not exist
     * there.
     *
     * That is not hypothetical: it is what a rotation during a deletion produces. The
     * recreated fragment starts a fresh session check, the deletion settles underneath
     * it, and whichever resumes second finds the other has already left.
     *
     * The guard is the destination itself rather than a flag, because a flag would be
     * a second copy of a fact the NavController already holds - and one that a
     * recreation resets while the NavController's does not.
     */
    private fun leaveForGuest() {
        if (_binding == null) return
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.profile_authenticated) return
        controller.navigate(R.id.action_profile_authenticated_to_profile)
    }


    override fun onDestroyView() {
        super.onDestroyView()
        confirmation?.dismiss()
        confirmation = null
        _binding = null
    }
}
