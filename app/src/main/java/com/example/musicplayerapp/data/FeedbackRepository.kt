package com.example.musicplayerapp.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Repository for sending track feedback (LIKE/DISLIKE) to Google Sheets.
 */
class FeedbackRepository(private val client: OkHttpClient) {
    companion object {
        private const val SPREADSHEET_WEBAPP_URL = "https://script.google.com/macros/s/AKfycbwB-6cuTEy2LShAQh5HORoeTZBmUZYa66md_Xa1BMoxe05n7ouzIiSwhntnCL-buAdpcg/exec"
    }

    /**
     * Reports feedback to Google Sheets.
     * @param artist Artist name
     * @param track Track title
     * @param stream Stream name (myata, gold, xtra)
     * @param action Action taken (LIKE or DISLIKE)
     */
    fun reportFeedback(artist: String, track: String, stream: String, action: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val formBody = FormBody.Builder(java.nio.charset.StandardCharsets.UTF_8)
                    .add("action", action)
                    .add("artist", artist)
                    .add("track", track)
                    .add("stream", stream)
                    .add("platform", "Android")
                    .build()

                val request = Request.Builder()
                    .url(SPREADSHEET_WEBAPP_URL)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .post(formBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("Feedback", "Failed to report: ${response.code}")
                    } else {
                        Log.d("Feedback", "Successfully reported $action for $artist - $track")
                    }
                }
            } catch (e: Exception) {
                Log.e("Feedback", "Error reporting to sheet", e)
            }
        }
    }
}
