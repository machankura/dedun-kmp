package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.collection.LruCache

object Constants {
    internal const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

    internal val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")

//    internal inline val globalMuted: MutableStateFlow<Boolean> get() = PipController.globalMuted
//    internal inline val activeVideoUrl: MutableStateFlow<String?> get() = PipController.activeVideoUrl

    internal val videoExtensions = setOf("mp4", "mov", "webm", "m3u8")
    internal val audioExtensions = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac")

    internal val imageMimeTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml", "image/heic", "image/heif")
    internal val videoMimeTypes = setOf("video/mp4", "video/quicktime", "video/webm", "application/vnd.apple.mpegurl", "application/x-mpegurl")
    internal val audioMimeTypes = setOf("audio/mpeg", "audio/wav", "audio/ogg", "audio/mp4", "audio/flac", "audio/aac", "audio/x-wav")

    // Matches a bare SHA-256 hex hash as the URL path (no extension)
    internal val blossomPathRegex = Regex("""^/[0-9a-f]{64}$""", RegexOption.IGNORE_CASE)

    internal val combinedRegex = Regex("""nostr:(note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]+|(?<!\w)(npub1[a-z0-9]{58})(?!\w|\.[a-zA-Z])|(?:https?|wss?)://\S+|(?<!\w)((?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+(?:com|net|org|io|dev|app|pro|ai|co|me|info|xyz|cc|tv|to|gg|sh|im|is|it|rs|ly|site|online|store|tech|cloud|social|world|earth|space|lol|wtf|family|life|art|design|blog|news|live|video|media|chat|games|money|finance|agency|studio|build|run|codes|systems|network|zone|pub|blue|limo|fyi|wiki|page|link|click|exchange|markets|fun|club|today)(?:/\S*)?)(?!\w)|(?<!\w)#([a-zA-Z0-9_][a-zA-Z0-9_-]*)|(?<!\w)((?:note1|nevent1|nprofile1|naddr1)[a-z0-9]{10,})(?!\w)""", RegexOption.IGNORE_CASE)

    internal val emojiShortcodeRegex = Regex(""":([a-zA-Z0-9_-]+):""")

    internal val bolt11Regex = Regex("""(?i)(lightning:)?(lnbc|lntb|lnbcrt)[0-9a-z]{50,}""")

    internal val contentTypeCache = LruCache<String, String>(200)

    internal val ogCache = LruCache<String, OgData>(200)
}