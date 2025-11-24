package com.example.musicplayerapp.fragments

import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.databinding.FragmentDonateBinding


class DonateFragment : Fragment() {

    lateinit var vm: StreamsViewModel
    lateinit var binding: FragmentDonateBinding

    //summ = 0 - пользователь выбрал свою сумму
    var summ = 100

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {


        binding = DataBindingUtil.inflate(
            inflater, R.layout.fragment_donate, container, false
        )

        binding.sum100.setBackgroundResource(R.drawable.summ_green)

        // Handle window insets for safe area
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.title) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Add status bar height to existing top padding/margin
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        vm = (activity as MainActivity).viewModel

        vm.currentFragmentLiveData.value = "donate"
        vm.isInSplitMode.observe(viewLifecycleOwner, Observer {
            if(it){
                binding.commentText.visibility = View.GONE
            }
            else{
                binding.commentText.visibility = View.VISIBLE
            }
        })

        binding.sum500.setOnClickListener{
            if (summ != 500){
                summ = 500
                binding.sum500.setBackgroundResource(R.drawable.summ_green)
                binding.sum0.setBackgroundResource(R.drawable.summ)
                binding.sum100.setBackgroundResource(R.drawable.summ)
                binding.sum200.setBackgroundResource(R.drawable.summ)
                binding.sum1000.setBackgroundResource(R.drawable.summ)
            }
        }

        binding.sum1000.setOnClickListener{
            if (summ != 1000){
                summ = 1000
                binding.sum500.setBackgroundResource(R.drawable.summ)
                binding.sum0.setBackgroundResource(R.drawable.summ)
                binding.sum100.setBackgroundResource(R.drawable.summ)
                binding.sum200.setBackgroundResource(R.drawable.summ)
                binding.sum1000.setBackgroundResource(R.drawable.summ_green)
            }
        }

        binding.sum100.setOnClickListener{
            if (summ != 100){
                summ = 100
                binding.sum500.setBackgroundResource(R.drawable.summ)
                binding.sum0.setBackgroundResource(R.drawable.summ)
                binding.sum100.setBackgroundResource(R.drawable.summ_green)
                binding.sum200.setBackgroundResource(R.drawable.summ)
                binding.sum1000.setBackgroundResource(R.drawable.summ)
            }
        }

        binding.sum200.setOnClickListener{
            if (summ != 200){
                summ = 200
                binding.sum500.setBackgroundResource(R.drawable.summ)
                binding.sum0.setBackgroundResource(R.drawable.summ)
                binding.sum100.setBackgroundResource(R.drawable.summ)
                binding.sum200.setBackgroundResource(R.drawable.summ_green)
                binding.sum1000.setBackgroundResource(R.drawable.summ)
            }
        }

        binding.sum0.setOnClickListener{
            if (summ != 0){
                summ = 0
                binding.sum500.setBackgroundResource(R.drawable.summ)
                binding.sum0.setBackgroundResource(R.drawable.summ_green)
                binding.sum100.setBackgroundResource(R.drawable.summ)
                binding.sum200.setBackgroundResource(R.drawable.summ)
                binding.sum1000.setBackgroundResource(R.drawable.summ)
            }
        }

        binding.sendBtn.setOnClickListener{

            binding.form.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            setWebViewClient()

            (activity as MainActivity).binding.infoBtn.setColorFilter(Color.parseColor("#999999"))
            (activity as MainActivity).binding.donateBtn.setColorFilter(Color.parseColor("#FFFFFF"))
            (activity as MainActivity).binding.homeBtn.setColorFilter(Color.parseColor("#999999"))
            (activity as MainActivity).binding.playerBtn.setColorFilter(Color.parseColor("#999999"))
            makePay(summ, binding.commentText.text.toString(), "ac")
        }

        (activity as MainActivity).binding.playerBtn.setOnClickListener {
            findNavController().navigate(R.id.player, Bundle().apply {
                when(vm.currentStreamLive.value){
                    "myata"->putInt(CURRENT_ITEM, 0)
                    "gold"->putInt(CURRENT_ITEM, 1)
                    "myata_hits"->putInt(CURRENT_ITEM, 2)
                }
            })
        }

        (activity as MainActivity).binding.infoBtn.setOnClickListener {
            findNavController().navigate(R.id.info)
        }
        (activity as MainActivity).binding.homeBtn.setOnClickListener {
            findNavController().navigate(R.id.home)
        }

        // Boosty subscription banner click handlers
        binding.boosty79Banner.setOnClickListener {
            openBoostyUrl("https://boosty.to/myata/purchase/2344380?ssource=DIRECT&share=subscription_link")
        }
        
        binding.boosty249Banner.setOnClickListener {
            openBoostyUrl("https://boosty.to/myata/purchase/1668?ssource=DIRECT&share=subscription_link")
        }
        
        binding.boosty499Banner.setOnClickListener {
            openBoostyUrl("https://boosty.to/myata/purchase/713873?ssource=DIRECT&share=subscription_link")
        }
        
        binding.boosty1499Banner.setOnClickListener {
            openBoostyUrl("https://boosty.to/myata/purchase/713875?ssource=DIRECT&share=subscription_link")
        }

        return binding.root
    }

    private fun openBoostyUrl(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse(url)
        startActivity(intent)
    }

    private fun makePay(sum:Int, comment: String, paymentType: String)
    {
        val order_id = "1"
        var url = "https://yoomoney.ru/quickpay/confirm.xml"
        if(sum!=0) {
            val postData = "receiver=410015757768507" +
                    "&formcomment=" + comment +
                    "&short-dest=" + "Пожертовование Radio Myata" +
                    "&label=$order_id" +
                    "&quickpay-form=donate" +
                    "&targets=" + "Пожертовование Radio Myata" +
                    "&sum=$sum" +
                    "&comment=" + comment
            "&need-fio=false" +
                    "&need-email=false" +
                    "&need-phone=false" +
                    "&need-address=false" +
                    "&paymentType=${paymentType}"

            binding.webView.postUrl(url, postData.toByteArray())
        }
        else{
            binding.webView.loadUrl("https://yoomoney.ru/to/410015757768507")
        }
    }

    private fun setWebViewClient()
    {
        val web_view = binding.webView
        val settings = web_view.settings
        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true

        binding.webView.webViewClient = object : WebViewClient()
        {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean
            {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?)
            {
                Log.e("ActPayment", "WebPage finished $url")

                super.onPageFinished(view, url)
            }
        }
    }

    override fun onResume() {
        vm.currentFragmentLiveData.value = "donate"
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}