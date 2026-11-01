package com.machankura.dedun.compose.ui.composable.widgets.content

import com.vitorpamplona.quartz.nip68Picture.PictureEvent
import com.vitorpamplona.quartz.nip71Video.VideoNormalEvent
import com.vitorpamplona.quartz.nip71Video.VideoShortEvent
import fr.acinq.lightning.payment.Bolt11Invoice
import io.ktor.http.Url
import io.ktor.http.fullPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal object Helpers {
    fun isBlossomUrl(url: String): Boolean {
        return try {
            val uri = Url(url)
            val path = uri.fullPath ?: return false
            Constants.blossomPathRegex.matches(path)
        } catch (_: Exception) {
            false
        }
    }

    fun isStandaloneUrl(content: String, matchRange: IntRange): Boolean {
        // Look back: only whitespace between previous newline (or start) and match start
        val before = content.substring(0, matchRange.first)
        val lineStart = before.lastIndexOf('\n') + 1
        val prefix = before.substring(lineStart)
        if (prefix.isNotBlank()) return false

        // Look forward: only whitespace between match end and next newline (or end)
        val after = content.substring(matchRange.last + 1)
        val lineEnd = after.indexOf('\n')
        val suffix = if (lineEnd == -1) after else after.substring(0, lineEnd)
        return suffix.isBlank()
    }


    fun classifyByMime(mime: String): String? = when {
        Constants.imageMimeTypes.any { mime.startsWith(it) } -> "image"
        Constants.videoMimeTypes.any { mime.startsWith(it) } -> "video"
        Constants.audioMimeTypes.any { mime.startsWith(it) } -> "audio"
        else -> null
    }

    fun splitTextForEmojis(text: String, emojiMap: Map<String, String>): List<ContentSegment> {
        val result = mutableListOf<ContentSegment>()
        var lastEnd = 0
        for (match in Constants.emojiShortcodeRegex.findAll(text)) {
            val shortcode = match.groupValues[1]
            val url = emojiMap[shortcode] ?: continue
            if (match.range.first > lastEnd) {
                result.add(ContentSegment.TextSegment(text.substring(lastEnd, match.range.first)))
            }
            result.add(ContentSegment.CustomEmojiSegment(shortcode, url))
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            result.add(ContentSegment.TextSegment(text.substring(lastEnd)))
        } else if (lastEnd == 0 && result.isEmpty()) {
            result.add(ContentSegment.TextSegment(text))
        }
        return result
    }

    fun splitTextForInvoices(text: String): List<ContentSegment> {
        val matches = Constants.bolt11Regex.findAll(text).toList()
        if (matches.isEmpty()) return listOf(ContentSegment.TextSegment(text))
        val result = mutableListOf<ContentSegment>()
        var lastEnd = 0
        var anyFound = false
        for (match in matches) {
            val raw = match.value
            val invoice = raw.removePrefix("lightning:").removePrefix("LIGHTNING:").lowercase()
            val bolt11Invoice = Bolt11Invoice.read(invoice)
            if (bolt11Invoice.isFailure) {
                continue
            }
            val decoded = bolt11Invoice.get()
            anyFound = true
            if (match.range.first > lastEnd) {
                result.add(ContentSegment.TextSegment(text.substring(lastEnd, match.range.first)))
            }
            result.add(ContentSegment.LightningInvoiceSegment(invoice, decoded))
            lastEnd = match.range.last + 1
        }
        if (!anyFound) return listOf(ContentSegment.TextSegment(text))
        if (lastEnd < text.length) {
            result.add(ContentSegment.TextSegment(text.substring(lastEnd)))
        }
        return result
    }

    fun formatQuotedTimestamp(epoch: Long): String {
        val diff = Clock.System.now().toEpochMilliseconds() - epoch * 1000
        if (diff < 0) return ""
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> "${seconds}s"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            days == 1L -> "yesterday"
            else -> "once upon a time"
        }
    }

    suspend fun fetchOgData(url: String): OgData? = withContext(Dispatchers.IO) {
        Constants.ogCache.get(url)?.let { return@withContext it }

        null
        // TODO: YouTube blocks bot User-Agents; use their oEmbed API instead
//        if (youtubeRegex.containsMatchIn(url)) {
//            val yt = fetchYoutubeOembed(url)
//            if (yt != null) { ogCache.put(url, yt); return@withContext yt }
//        }
//        try {
//            val request = Request.Builder()
//                .url(url)
//                .header("User-Agent", "Mozilla/5.0 (compatible; Wisp/1.0)")
//                .build()
//            httpClient.newCall(request).execute().use { response ->
//                if (!response.isSuccessful) return@withContext null
//                val contentType = response.header("Content-Type") ?: ""
//                if (!contentType.contains("text/html", ignoreCase = true)) return@withContext null
//                // Read only the first 32KB — OG tags are in <head>
//                val body = response.body?.source()?.let { source ->
//                    source.request(32 * 1024)
//                    source.buffer.snapshot().utf8()
//                } ?: return@withContext null
//
//                val ogProps = mutableMapOf<String, String>()
//                for (match in ogTagRegex.findAll(body)) {
//                    val prop = (match.groupValues[1].ifEmpty { match.groupValues[4] }).lowercase()
//                    val content = match.groupValues[2].ifEmpty { match.groupValues[3] }
//                    if (prop.isNotEmpty() && content.isNotEmpty()) {
//                        ogProps.putIfAbsent(prop, content)
//                    }
//                }
//
//                val title = ogProps["title"]
//                    ?: titleTagRegex.find(body)?.groupValues?.get(1)?.trim()
//                val ogData = OgData(
//                    title = title?.let { unescapeHtml(it) },
//                    description = ogProps["description"]?.let { unescapeHtml(it) },
//                    image = ogProps["image"],
//                    siteName = ogProps["site_name"]?.let { unescapeHtml(it) }
//                )
//                if (ogData.title != null || ogData.image != null) {
//                    ogCache.put(url, ogData)
//                    ogData
//                } else null
//            }
//        } catch (_: Exception) {
//            null
//        }
    }

    fun isGalleryEvent(event: com.machankura.dedun.compose.database.model.NostrEvent): Boolean = event.kind in arrayOf(
        PictureEvent.KIND,
        VideoNormalEvent.KIND,
        VideoShortEvent.KIND
    )

}