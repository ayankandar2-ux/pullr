package com.deniscerri.ytdl.util

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches just a title + thumbnail for a YouTube URL via the official oEmbed endpoint,
 * so the quick-pick sheet can show *something* real almost instantly while the much
 * heavier yt-dlp format extraction (~10s) runs in the background. No API key needed,
 * no yt-dlp/Python process spawn — just one small HTTPS GET.
 */
object InstantPreviewFetcher {

    data class Preview(val title: String, val thumbnailUrl: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** Returns null on any failure or timeout - callers should just keep showing the shimmer. */
    fun fetchYoutubeOembed(url: String): Preview? {
        return try {
            val oembedUrl = "https://www.youtube.com/oembed?url=${java.net.URLEncoder.encode(url, "UTF-8")}&format=json"
            val request = Request.Builder().url(oembedUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val title = json.optString("title").ifBlank { return null }
                val thumbnail = json.optString("thumbnail_url").ifBlank { null }
                Preview(title, thumbnail)
            }
        } catch (e: Exception) {
            null
        }
    }
}
