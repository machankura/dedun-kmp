package com.machankura.dedun.compose.ui.composable.widgets.content

import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

internal object ContentParser {

    fun parse(
        content: String,
        emojiMap: Map<String, String> = emptyMap(),
        imetaMap: Map<String, MediaMeta> = emptyMap(),
        trimBlankLines: Boolean = true
    ): List<ContentSegment> {
        val segments = mutableListOf<ContentSegment>()
        var lastEnd = 0

        for (match in Constants.combinedRegex.findAll(content)) {
            if (match.range.first > lastEnd) {
                segments.add(ContentSegment.TextSegment(content.substring(lastEnd, match.range.first)))
            }
            val token = match.value
            val bareDomainCapture = match.groupValues.getOrNull(3)
            val hashtagCapture = match.groupValues.getOrNull(4)
            if (!hashtagCapture.isNullOrEmpty() && token.startsWith("#")) {
                segments.add(ContentSegment.HashtagSegment(hashtagCapture))
            } else if (!bareDomainCapture.isNullOrEmpty() && !token.startsWith("http")) {
                val url = "https://$bareDomainCapture"
                val meta = imetaMap[url]
                val imetaMime = meta?.mime?.let {
                    Helpers.classifyByMime(
                        it
                    )
                }
                val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
                when {
                    imetaMime == "image" -> segments.add(ContentSegment.ImageSegment(meta!!))
                    imetaMime == "video" -> segments.add(ContentSegment.VideoSegment(meta!!))
                    imetaMime == "audio" -> segments.add(ContentSegment.AudioSegment(meta!!))
                    ext in Constants.imageExtensions -> segments.add(ContentSegment.ImageSegment(meta ?: MediaMeta(url)))
                    ext in Constants.videoExtensions -> segments.add(ContentSegment.VideoSegment(meta ?: MediaMeta(url)))
                    ext in Constants.audioExtensions -> segments.add(ContentSegment.AudioSegment(meta ?: MediaMeta(url)))
                    Helpers.isBlossomUrl(
                        url
                    ) -> segments.add(ContentSegment.UnknownMediaSegment(meta ?: MediaMeta(url)))
                    Helpers.isStandaloneUrl(
                        content,
                        match.range
                    ) -> segments.add(ContentSegment.LinkSegment(url))
                    else -> segments.add(ContentSegment.InlineLinkSegment(url))
                }
            }else if (token.startsWith("nostr:")) {
                val nip19ParseResult = Nip19Parser.parseAll(token)
                when (val entity = nip19ParseResult.firstOrNull()) {
                    is NAddress -> {
                        segments.add(ContentSegment.NostrAddressableSegment(entity.dTag, entity.relay.map { it.url }, entity.author, entity.kind))
                    }
                    is NProfile -> {
                        segments.add(
                            ContentSegment.NostrProfileSegment(
                                pubkey = entity.hex,
                                relayHints = entity.relay.map { it.url },
                                startIndex = match.range.first,
                                endIndex = match.range.last
                            )
                        )
                    }
                    is NPub -> {
                        segments.add(
                            ContentSegment.NostrProfileSegment(
                                pubkey = entity.hex,
                                startIndex = match.range.first,
                                endIndex = match.range.last
                            )
                        )
                    }
                    is NNote -> {
                        segments.add(ContentSegment.NostrNoteSegment(entity.hex))
                    }
                    is NEvent -> {
                        segments.add(
                            ContentSegment.NostrNoteSegment(
                                eventId = entity.hex,
                                relayHints = entity.relay.map { it.url }
                            )
                        )
                    }
                    else -> {
                        segments.add(ContentSegment.TextSegment(token))
                    }
                }
            } else {
                val url = token.trimEnd('.', ',', ')', ']', ';', ':', '!', '?')
                val isWebSocket = url.startsWith("wss://") || url.startsWith("ws://")
                val meta = imetaMap[url]
                val imetaMime = meta?.mime?.let {
                    Helpers.classifyByMime(
                        it
                    )
                }
                val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
                when {
                    imetaMime == "image" -> segments.add(ContentSegment.ImageSegment(meta!!))
                    imetaMime == "video" -> segments.add(ContentSegment.VideoSegment(meta!!))
                    imetaMime == "audio" -> segments.add(ContentSegment.AudioSegment(meta!!))
                    ext in Constants.imageExtensions -> segments.add(ContentSegment.ImageSegment(meta ?: MediaMeta(url)))
                    ext in Constants.videoExtensions -> segments.add(ContentSegment.VideoSegment(meta ?: MediaMeta(url)))
                    ext in Constants.audioExtensions -> segments.add(ContentSegment.AudioSegment(meta ?: MediaMeta(url)))
                    // TODO: Support websockets
//                    isWebSocket && '\'' in url -> {
//                        val parsed = Nip29.parseGroupIdentifier(url)
//                        if (parsed != null) segments.add(ContentSegment.GroupInviteSegment(parsed.first, parsed.second))
//                        else segments.add(ContentSegment.InlineLinkSegment(url))
//                    }
                    isWebSocket -> segments.add(ContentSegment.InlineLinkSegment(url))
                    Helpers.isBlossomUrl(
                        url
                    ) -> segments.add(ContentSegment.UnknownMediaSegment(meta ?: MediaMeta(url)))
                    Helpers.isStandaloneUrl(
                        content,
                        match.range
                    ) -> segments.add(ContentSegment.LinkSegment(url))
                    else -> segments.add(ContentSegment.InlineLinkSegment(url))
                }
            }
            lastEnd = match.range.last + 1
        }

        if (lastEnd < content.length) {
            segments.add(ContentSegment.TextSegment(content.substring(lastEnd)))
        }

        // Second pass: split TextSegments that contain :shortcode: where shortcode is in emojiMap
        val afterEmoji = if (emojiMap.isEmpty()) {
            segments
        } else {
            val result = mutableListOf<ContentSegment>()
            for (segment in segments) {
                if (segment is ContentSegment.TextSegment) {
                    result.addAll(
                        Helpers.splitTextForEmojis(
                            segment.text,
                            emojiMap
                        )
                    )
                } else {
                    result.add(segment)
                }
            }
            result
        }

        // Third pass: detect lightning invoices in text segments
        val finalResult = mutableListOf<ContentSegment>()
        for (segment in afterEmoji) {
            if (segment is ContentSegment.TextSegment) {
                finalResult.addAll(
                    Helpers.splitTextForInvoices(
                        segment.text
                    )
                )
            } else {
                finalResult.add(segment)
            }
        }

        // Final pass: trim trailing blank lines from text segments that precede
        // block-level segments (links, images, embeds, etc.) to avoid extra vertical space
        if (trimBlankLines) for (i in 0 until finalResult.size - 1) {
            val segment = finalResult[i]
            val next = finalResult[i + 1]
            if (segment is ContentSegment.TextSegment && next !is ContentSegment.TextSegment && next !is ContentSegment.InlineLinkSegment && next !is ContentSegment.CustomEmojiSegment) {
                val trimmed = segment.text.trimEnd('\n')
                if (trimmed != segment.text) {
                    finalResult[i] = ContentSegment.TextSegment(if (trimmed.isEmpty()) trimmed else "$trimmed\n")
                }
            }
        }

        return finalResult
    }
}