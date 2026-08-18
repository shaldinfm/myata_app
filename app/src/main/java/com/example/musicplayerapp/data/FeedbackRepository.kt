package com.example.musicplayerapp.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Repository for sending track reactions to Google Sheets.
 *
 * The action is a [ReactionEvent] rather than a `String`. It used to be free text
 * documented as "LIKE or DISLIKE", which left no way to say that a reaction had
 * gone back to neutral, so every un-like site sent `DISLIKE` and the sheet recorded
 * listeners as disliking tracks they had merely taken out of their Collection.
 */
class FeedbackRepository(private val client: OkHttpClient) {
    companion object {
        private const val SPREADSHEET_WEBAPP_URL = "https://script.google.com/macros/s/AKfycbwB-6cuTEy2LShAQh5HORoeTZBmUZYa66md_Xa1BMoxe05n7ouzIiSwhntnCL-buAdpcg/exec"
    }

    /**
     * Reports a reaction to Google Sheets.
     *
     * Fire-and-forget: the local Collection is the source of truth and never waits
     * on, or is rolled back by, this call.
     *
     * @param artist Artist name
     * @param track Track title
     * @param stream Stream name (myata, gold, myata_hits)
     * @param action What the listener did - see [ReactionEvent]
     */
    fun reportFeedback(artist: String, track: String, stream: String, action: ReactionEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val formBody = FormBody.Builder(java.nio.charset.StandardCharsets.UTF_8)
                    .add("action", action.wire)
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
                        Log.d("Feedback", "Successfully reported ${action.wire} for $artist - $track")
                    }
                }
            } catch (e: Exception) {
                Log.e("Feedback", "Error reporting to sheet", e)
            }
        }
    }
}
