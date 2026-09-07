package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.report.ReportCategory
import com.example.musicplayerapp.databinding.FragmentReportProblemBinding
import com.example.musicplayerapp.ui.report.ReportFormState
import com.example.musicplayerapp.ui.report.ReportProblemViewModel

/**
 * report-empty / -filled / -sending / -error / -success (G3).
 *
 * A pushed destination like settings and the profile: the band carries a back
 * button, the frame has no bottom bar, and MainActivity hides the bar for it.
 *
 * ## One destination, two doors
 *
 * Reached from `Menu / Плеер` > `Сообщить о проблеме` and from
 * `Settings > Прочее > Сообщить о проблеме`. Both frozen frames draw the entry,
 * and both open this - the same screen in the same state, with no argument
 * distinguishing them. A listener whose radio will not start is by definition not
 * mid-playback, which is why the player menu alone would not do; it is the same
 * reasoning the sleep timer's own frame note gives for being in both places.
 *
 * ## The screen owns no state
 *
 * Everything that survives a rotation - the chosen category, the typed words,
 * whether a send is running - is in [ReportProblemViewModel]. This renders it.
 * That is what makes "the error state keeps what you typed" true across a
 * configuration change as well as across a failure, and what stops a rotation
 * mid-send starting a second post.
 */
class ReportProblemFragment : Fragment() {

    private var _binding: FragmentReportProblemBinding? = null
    private val binding get() = _binding!!

    private val vm: ReportProblemViewModel by viewModels()

    /** The five rows, in the frozen order, paired with the enum they select. */
    private lateinit var rows: List<Triple<LinearLayout, ImageView, ReportCategory>>

    /** The six diagnostics lines, in the frozen order. */
    private lateinit var diagnosticLines: List<TextView>

    /**
     * Guards [ReportProblemViewModel.message] against the watcher firing for text
     * this fragment has just written back into the field.
     */
    private var writingMessage = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReportProblemBinding.inflate(inflater, container, false)

        // The 64dp band sits below the status bar, as on every pushed destination.
        // systemBars() OR displayCutout() for the reason profile-guest gives.
        ViewCompat.setOnApplyWindowInsetsListener(binding.reportRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)

            // This screen hides the bottom bar, so nothing else reserves the
            // system navigation inset - the scroll and the success panel clear it
            // themselves. There is no Mini Player over a pushed destination, so
            // this is the bar alone rather than content_bottom_clearance.
            listOf<View>(binding.reportScroll, binding.reportSuccess).forEach { panel ->
                panel.setPadding(
                    panel.paddingLeft, panel.paddingTop, panel.paddingRight, bars.bottom
                )
            }
            insets
        }

        rows = listOf(
            Triple(binding.reportCategory0, binding.reportCategory0Check, ReportCategory.PLAYBACK_WONT_START),
            Triple(binding.reportCategory1, binding.reportCategory1Check, ReportCategory.STOPPED_BY_ITSELF),
            Triple(binding.reportCategory2, binding.reportCategory2Check, ReportCategory.HEADPHONES),
            Triple(binding.reportCategory3, binding.reportCategory3Check, ReportCategory.INTERFACE),
            Triple(binding.reportCategory4, binding.reportCategory4Check, ReportCategory.OTHER),
        )

        diagnosticLines = listOf(
            binding.reportDiagnosticsLine0,
            binding.reportDiagnosticsLine1,
            binding.reportDiagnosticsLine2,
            binding.reportDiagnosticsLine3,
            binding.reportDiagnosticsLine4,
            binding.reportDiagnosticsLine5,
        )

        // Back returns to whatever opened this - the player or settings - which is
        // what popBackStack does and what a named destination would get wrong for
        // one of the two.
        binding.reportBack.setOnClickListener { findNavController().popBackStack() }
        binding.reportSuccessDone.setOnClickListener { findNavController().popBackStack() }

        rows.forEach { (row, _, category) -> row.setOnClickListener { vm.select(category) } }

        binding.reportMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!writingMessage) vm.message(s?.toString().orEmpty())
            }
        })

        // The button is a FrameLayout rather than a Button, so `enabled` is not
        // what stops a tap - the ViewModel is. It refuses a send with no category
        // and refuses a second one while the first is running, which is the rule
        // stated once rather than mirrored in a view flag that could disagree.
        binding.reportSend.setOnClickListener {
            hideKeyboard()
            vm.send()
        }

        vm.state.observe(viewLifecycleOwner) { render(it) }
        vm.diagnostics.observe(viewLifecycleOwner) { snapshot ->
            snapshot.lines(requireContext()).forEachIndexed { i, line ->
                diagnosticLines[i].text = line
            }
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()

        // The card is a promise about what is about to be sent, so it is re-read on
        // every visit: the network can change while this screen sits on the back
        // stack, and so can the last playback error. The snapshot that is actually
        // posted is taken again at send time - see ReportProblemViewModel.
        vm.refreshDiagnostics((activity as? MainActivity)?.viewModel?.currentStreamLive?.value)
    }

    private fun render(state: ReportFormState) {
        if (state.sent) {
            // report-success. The form is gone rather than invisible: it is a
            // different arrangement, and leaving 950dp of it measured underneath a
            // 456dp panel would leave the scroll position and the focus behind it.
            binding.reportScroll.visibility = View.GONE
            binding.reportSuccess.visibility = View.VISIBLE
            return
        }
        binding.reportScroll.visibility = View.VISIBLE
        binding.reportSuccess.visibility = View.GONE

        renderCategories(state)
        renderMessage(state)
        renderBanner(state)
        renderButton(state)
    }

    /**
     * The chosen row takes the 2dp `primary` stroke and shows its check; the other
     * four keep the 1dp `outline` plate and hide theirs.
     *
     * The check is toggled between VISIBLE and INVISIBLE rather than GONE, so no
     * row's measured width changes when the selection moves - the frozen selected
     * row narrows its *label* to 254 because the check is there, and it is there on
     * every row.
     */
    private fun renderCategories(state: ReportFormState) {
        rows.forEach { (row, check, category) ->
            val selected = state.category == category
            row.setBackgroundResource(
                if (selected) R.drawable.bg_settings_row_selected else R.drawable.bg_profile_row
            )
            check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            row.isEnabled = !state.sending
        }
    }

    private fun renderMessage(state: ReportFormState) {
        if (binding.reportMessage.text.toString() != state.message) {
            writingMessage = true
            binding.reportMessage.setText(state.message)
            binding.reportMessage.setSelection(state.message.length)
            writingMessage = false
        }
        // `report-sending`: "the form stays visible and editable-looking but the
        // button is inert". The field stops accepting input without being greyed,
        // which is what the frozen frame draws - it is identical to report-filled
        // apart from the button.
        binding.reportMessage.isEnabled = !state.sending
    }

    private fun renderBanner(state: ReportFormState) {
        binding.reportErrorBanner.visibility = if (state.failed) View.VISIBLE else View.GONE

        // The button sits 24 below the diagnostics card, or 16 below the banner
        // when there is one: 874 in three frames, 946 in report-error.
        val params = binding.reportSend.layoutParams as ViewGroup.MarginLayoutParams
        params.topMargin = resources.getDimensionPixelSize(
            if (state.failed) R.dimen.report_button_margin_top_after_banner
            else R.dimen.report_button_margin_top
        )
        binding.reportSend.layoutParams = params
    }

    /**
     * The four button states of the frozen frames.
     *
     * `Отправить` disabled, `Отправить` primary, `Отправляем…` disabled with the
     * indicator, and `Отправить ещё раз` primary. The label and the fill move
     * together because they are one state, not two flags.
     */
    private fun renderButton(state: ReportFormState) {
        val enabled = state.canSend

        binding.reportSend.setBackgroundResource(
            if (enabled) R.drawable.bg_profile_button_primary else R.drawable.bg_button_disabled
        )
        binding.reportSendLabel.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.profile_primary_button_label else R.color.disabled_content,
            )
        )
        binding.reportSendLabel.setText(
            when {
                state.sending -> R.string.report_sending
                state.failed -> R.string.report_send_again
                else -> R.string.report_send
            }
        )
        binding.reportSend.isClickable = enabled
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.reportRoot.windowToken, 0)
        binding.reportMessage.clearFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
