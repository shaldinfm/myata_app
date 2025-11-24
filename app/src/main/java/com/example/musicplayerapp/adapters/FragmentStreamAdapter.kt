package com.example.musicplayerapp.adapters

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.musicplayerapp.fragments.MyataStreamFragment
import com.example.musicplayerapp.fragments.STREAM

class FragmentStreamAdapter(fragment: Fragment) : FragmentStateAdapter(fragment){
    override fun getItemCount(): Int = 3
    override fun createFragment(position: Int): Fragment {
        val fragment = MyataStreamFragment()
        when(position){
            0->{
                fragment.arguments = Bundle().apply {
                    putString(STREAM, "myata")
                }
            }
            1->{
                fragment.arguments = Bundle().apply {
                    putString(STREAM, "gold")
                }
            }
            2->{
                fragment.arguments = Bundle().apply {
                    putString(STREAM, "myata_hits")
                }
            }
        }
        return fragment
    }

}