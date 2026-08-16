package com.example.musicplayerapp.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.databinding.FragmentInfoBinding
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

class InfoFragment : Fragment() {

    private lateinit var vm: StreamsViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        val binding: FragmentInfoBinding = DataBindingUtil.inflate(
            inflater, R.layout.fragment_info, container, false
        )

        // Handle window insets for safe area.
        //
        // The inset goes on the ROOT, not on the header, which is what HOME
        // (main_content_container) and COLLECTION (collection_root) already do.
        // Putting it on the title spent the inset out of the header's own 64dp
        // band and left "О нас" sitting ~12dp above the other sections' titles;
        // below the root's padding the band is a full 64 and all three land on
        // the same baseline.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.aboutRoot) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)

            // The frozen clearance covers the chrome the design draws - navigation
            // bar, gap, mini player - but the navigation bar also takes the system
            // inset as padding (see MainActivity), so the content has to clear that
            // too or the last tile row ends up under the pill on a gesture-nav
            // device. Same addition HOME and COLLECTION make.
            val scroll = binding.aboutScroll
            scroll.setPadding(
                scroll.paddingLeft,
                scroll.paddingTop,
                scroll.paddingRight,
                resources.getDimensionPixelSize(R.dimen.content_bottom_clearance) + bars.bottom,
            )
            insets
        }

        vm = (activity as MainActivity).viewModel

        vm.currentFragmentLiveData.value = "info"

        // The eight tiles of the frozen `Section 3: Social Media`, in its order:
        // Telegram, Spotify, Instagram, TikTok / YouTube, Threads, Boosty,
        // ЯМузыка. Every URL below is the one this screen already opened, except
        // Threads - the frozen grid has eight slots and puts Threads in the one
        // Twitter held, so Twitter is dropped on the owner's instruction rather
        // than kept as a ninth tile the design has no room for.
        binding.telegram.opensLink("https://t.me/radiomyata")
        binding.spotify.opensLink("https://open.spotify.com/user/31b7rfatuqf7lc76thiudm5bxxuy")
        binding.instagram.opensLink("https://www.instagram.com/radiomyata/")
        binding.tiktok.opensLink("https://www.tiktok.com/@radio_myata")
        binding.youtube.opensLink("https://www.youtube.com/channel/UC30ExLCP-enuCrHH2qRRlCw")
        binding.threads.opensLink("https://www.threads.com/@radiomyata")
        binding.boosty.opensLink("https://boosty.to/myata")
        // The one link that never carried CATEGORY_BROWSABLE. Kept that way: the
        // category narrows which activities can match, and this is a band.link
        // redirector, so adding it would be a real change to what can open it.
        binding.yandex.opensLink("https://band.link/myata_yandex", browsable = false)

        // Highlight GOLD and XTRA in description
        //
        // They live in the third paragraph, which the frozen card starts
        // collapsed - the spans are applied here all the same, so the text is
        // already correct the first time "Читать подробнее" reveals it.
        val descriptionText = binding.description3.text.toString()
        val spannable = SpannableStringBuilder(descriptionText)
        // The paragraph around this span is Onest Regular, from
        // TextAppearance.Myata.Onest.Regular.15_24. GOLD and XTRA are emphasised
        // within it, so the span stays in the same family and keeps the weight it
        // had: Muller Black 900 -> Onest Black, per the contract's weight mapping.
        val emphasis = ResourcesCompat.getFont(requireContext(), R.font.onest_black)

        emphasis?.let { typeface ->
            // Highlight GOLD
            val goldIndex = descriptionText.indexOf("GOLD")
            if (goldIndex != -1) {
                spannable.setSpan(
                    CustomTypefaceSpan(typeface),
                    goldIndex,
                    goldIndex + 4,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // Highlight XTRA
            val xtraIndex = descriptionText.indexOf("XTRA")
            if (xtraIndex != -1) {
                spannable.setSpan(
                    CustomTypefaceSpan(typeface),
                    xtraIndex,
                    xtraIndex + 4,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        binding.description3.text = spannable

        // "Читать подробнее", `Button` 2424:758.
        //
        // The frozen card draws paragraph 1 and marks the other two
        // visible:false, with no destination anywhere in the app for this
        // button. Rather than ship it dead or drop two paragraphs the screen has
        // always shown, it expands them in place: the collapsed default is the
        // design's, and no copy is lost.
        binding.readMore.setOnClickListener {
            val expanded = binding.description2.visibility == View.VISIBLE
            val next = if (expanded) View.GONE else View.VISIBLE
            binding.description2.visibility = next
            binding.description3.visibility = next
            binding.readMore.setText(
                if (expanded) R.string.about_read_more else R.string.about_read_less
            )
        }

        // `Boosty Subscription Card` > Button 2411:31645. The frozen design adds
        // this second donation entry point; it opens the same Boosty page the
        // Boosty tile below already opens, so it introduces no new destination.
        binding.boostyCta.opensLink("https://boosty.to/myata")

        // The frozen 3.6.6 bar has no Donate destination: donation lives inside
        // "О нас". This is that entry point, and it is the only one into the app's
        // own payment flow: DonateFragment - payment form, YooMoney WebView and
        // Boosty subscriptions - unchanged. Navigating without popUpTo leaves info
        // on the back stack, so Back returns here rather than jumping to Home.
        binding.donateCta.setOnClickListener {
            if (findNavController().currentDestination?.id != R.id.donate) {
                findNavController().navigate(R.id.donate)
            }
        }

        vm.isInSplitMode.observe(viewLifecycleOwner, Observer {
            if(it){
//                binding.bottomStreams.visibility = View.GONE
                (activity as MainActivity).binding.bottomNavView.visibility = View.GONE
                binding.title.visibility = View.GONE
            }
            else{
//                binding.bottomStreams.visibility = View.VISIBLE
                (activity as MainActivity).binding.bottomNavView.visibility = View.VISIBLE
                binding.title.visibility = View.VISIBLE
            }
        })

        return binding.root
    }

    override fun onResume() {
        vm.currentFragmentLiveData.value = "info"
        super.onResume()
    }

    /**
     * Opens [url] in whatever handles it, the way this screen always has.
     *
     * `browsable` exists only to preserve the one asymmetry the original code
     * had - see the Yandex call site.
     */
    private fun View.opensLink(url: String, browsable: Boolean = true) {
        setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            if (browsable) intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.data = Uri.parse(url)
            startActivity(intent)
        }
    }

    // Helper span for custom typefaces on older Android versions
    class CustomTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(ds: TextPaint) {
            ds.typeface = typeface
        }
        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = typeface
        }
    }
}
