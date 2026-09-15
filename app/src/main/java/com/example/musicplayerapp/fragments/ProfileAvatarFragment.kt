package com.example.musicplayerapp.fragments

import android.graphics.Outline
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.databinding.FragmentProfileAvatarBinding
import com.example.musicplayerapp.ui.auth.applyAuthInsets
import com.example.musicplayerapp.ui.profile.AvatarInitial
import com.example.musicplayerapp.ui.profile.ProfileAccount
import com.example.musicplayerapp.ui.profile.ProfileAvatar
import com.example.musicplayerapp.ui.profile.ProfileAvatarViewModel
import com.example.musicplayerapp.ui.profile.ProfileAvatars

/**
 * profile-avatar 2523:137 / 2517:3678 (G6a) - `Выбор аватара`.
 *
 * Reached from `Row / Аватар` on the authenticated profile, which is where the frames
 * put the entry: the account card's circle is not drawn as a control.
 *
 * A tap rings a cell and moves `Текущий аватар` to it; `Сохранить` writes the choice to
 * the account and returns. See [com.example.musicplayerapp.ui.profile.AvatarSelection]
 * for why those are two steps, and [ProfileAvatarViewModel] for the write.
 */
class ProfileAvatarFragment : Fragment() {

    private var _binding: FragmentProfileAvatarBinding? = null
    private val binding get() = _binding!!

    private val picker: ProfileAvatarViewModel by viewModels()

    /** The cells, in [ProfileAvatars.all] order, so a key finds its view without a search. */
    private val cells = mutableListOf<View>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProfileAvatarBinding.inflate(inflater, container, false)
        // Not the shared 154dp Mini Player band: this screen is pushed, so the pill is
        // hidden, and with 24 avatars the picker scrolls. The scroll ends 24dp under
        // `Сохранить`, as the frame does, plus the navigation bar.
        applyAuthInsets(binding.avatarRoot, binding.avatarScroll, R.dimen.avatar_scroll_bottom_clearance)

        binding.avatarBack.setOnClickListener { leave() }
        binding.avatarSave.setOnClickListener { picker.save() }

        clipToCircle(binding.avatarCurrentImage)
        buildCells(inflater)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        picker.state.observe(viewLifecycleOwner) { state ->
            if (_binding == null) return@observe
            render(state)

            when (state.outcome) {
                null -> Unit
                ProfileAvatarViewModel.Outcome.Done -> {
                    picker.consumeOutcome()
                    // Handed to the profile explicitly rather than left for it to re-read:
                    // see ProfileAuthenticatedFragment.observeAvatarPicker.
                    handBack(RESULT_SAVED_AVATAR, state.account?.avatarId)
                    leave()
                }
                ProfileAvatarViewModel.Outcome.NotAnAccount -> {
                    picker.consumeOutcome()
                    // The profile underneath checks the session again and steps aside to
                    // the guest screen.
                    handBack(RESULT_VERIFY_AGAIN, true)
                    leave()
                }
                ProfileAvatarViewModel.Outcome.SaveFailed -> {
                    picker.consumeOutcome()
                    Toast.makeText(requireContext(), R.string.avatar_save_failed, Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
        picker.load()
    }

    private fun buildCells(inflater: LayoutInflater) {
        cells.clear()
        binding.avatarGrid.removeAllViews()
        for (avatar in ProfileAvatars.all) {
            val cell = inflater.inflate(R.layout.item_profile_avatar_cell, binding.avatarGrid, false)
            val image = cell.findViewById<ImageView>(R.id.avatar_cell_image)
            image.setImageResource(avatar.drawable)
            clipToCircle(image)

            cell.tag = avatar.key
            cell.contentDescription = describe(avatar)
            cell.setOnClickListener { picker.select(avatar.key) }
            // Spoken as one of a set of options, with its checked state, rather than as
            // an unlabelled image - so TalkBack says "Аватар 6 из 24, выбран".
            ViewCompat.setAccessibilityDelegate(cell, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            })

            binding.avatarGrid.addView(cell)
            cells += cell
        }
    }

    private fun render(state: ProfileAvatarViewModel.State) {
        val account = state.account
        val ready = account != null

        // Inert until the account is known: a choice made before then would have no
        // account to belong to.
        binding.avatarGrid.alpha = if (ready) 1f else 0f
        binding.avatarCurrent.visibility = if (ready) View.VISIBLE else View.INVISIBLE
        binding.avatarSave.isEnabled = ready && !state.saving
        cells.forEach { it.isEnabled = ready && !state.saving }

        val chosen = ProfileAvatars.resolve(state.selected)
        cells.forEachIndexed { index, cell ->
            val selected = ProfileAvatars.all[index].key == chosen?.key
            if (cell.isSelected != selected) {
                cell.isSelected = selected
                cell.findViewById<View>(R.id.avatar_cell_ring).background = ContextCompat.getDrawable(
                    requireContext(),
                    if (selected) R.drawable.bg_avatar_cell_ring_selected
                    else R.drawable.bg_avatar_cell_ring,
                )
                cell.findViewById<View>(R.id.avatar_cell_badge).visibility =
                    if (selected) View.VISIBLE else View.GONE
            }
            ViewCompat.setStateDescription(
                cell,
                getString(if (selected) R.string.avatar_state_selected else R.string.avatar_state_not_selected),
            )
        }

        if (account == null) return

        if (chosen != null) {
            binding.avatarCurrentImage.setImageResource(chosen.drawable)
            binding.avatarCurrentImage.visibility = View.VISIBLE
            binding.avatarCurrentDisc.visibility = View.GONE
            binding.avatarCurrentInitial.visibility = View.GONE
        } else {
            val fallbackName = getString(R.string.profile_account_name_fallback)
            binding.avatarCurrentInitial.text = ProfileAccount.initial(account.displayName, fallbackName)
            AvatarInitial.centre(binding.avatarCurrentInitial)
            binding.avatarCurrentImage.visibility = View.GONE
            binding.avatarCurrentDisc.visibility = View.VISIBLE
            binding.avatarCurrentInitial.visibility = View.VISIBLE
        }
        binding.avatarCurrent.contentDescription = getString(
            R.string.avatar_current_description,
            chosen?.let { describe(it) } ?: getString(R.string.avatar_none_description),
        )
    }

    private fun describe(avatar: ProfileAvatar): String =
        getString(R.string.avatar_numbered, avatar.number, ProfileAvatars.all.size)

    private fun handBack(key: String, value: Any?) {
        if (_binding == null) return
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.profile_avatar) return
        controller.previousBackStackEntry?.savedStateHandle?.set(key, value)
    }

    /**
     * Back and a finished save both land here, and only once: the guard is the
     * destination itself, so a second tap, or a save that completes while Back is
     * already on its way, cannot pop the profile as well.
     */
    private fun leave() {
        if (_binding == null) return
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.profile_avatar) return
        controller.popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cells.clear()
        _binding = null
    }

    companion object {
        /** The avatar key the account holds after `Сохранить`. */
        const val RESULT_SAVED_AVATAR = "profile_avatar.saved"

        /** Set when the picker found this install is not the account it opened for. */
        const val RESULT_VERIFY_AGAIN = "profile_avatar.verify_again"

        /**
         * Clips to the circle the frame clips to. The artwork is already round with
         * transparent corners; this keeps it round if a drawable ever is not.
         */
        fun clipToCircle(view: View) {
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            view.clipToOutline = true
        }
    }
}
