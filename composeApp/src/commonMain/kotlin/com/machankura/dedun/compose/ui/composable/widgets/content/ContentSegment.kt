package com.machankura.dedun.compose.ui.composable.widgets.content

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import fr.acinq.lightning.payment.Bolt11Invoice

internal sealed interface ContentSegment {

    data class TextSegment(val text: String) : ContentSegment {
        override fun toString(): String {
            return text
        }
    }
    data class ImageSegment(val meta: MediaMeta) : ContentSegment {
        override fun toString(): String {
            return meta.url
        }
    }
    data class VideoSegment(val meta: MediaMeta) : ContentSegment {
        override fun toString(): String {
            return meta.url
        }
    }
    data class AudioSegment(val meta: MediaMeta) : ContentSegment {
        override fun toString(): String {
            return meta.url
        }
    }
    data class UnknownMediaSegment(val meta: MediaMeta) : ContentSegment {
        override fun toString(): String {
            return meta.url
        }
    }
    data class LinkSegment(val url: String) : ContentSegment {
        override fun toString(): String {
            return url
        }
    }
    data class InlineLinkSegment(val url: String) : ContentSegment {
        override fun toString(): String {
            return url
        }
    }
    data class NostrNoteSegment(val eventId: String, val relayHints: List<String> = emptyList()) : ContentSegment {
        override fun toString(): String {
            return NEvent.create(
                idHex = eventId,
                author = null,
                kind = null,
                relays = relayHints.map { it.normalizeRelayUrl() },
            )
        }
    }
    data class NostrProfileSegment(
        val pubkey: String,
        val relayHints: List<String> = emptyList(),
        val startIndex: Int,
        val endIndex: Int
    ) : ContentSegment {
        override fun toString(): String {
            return NProfile.create(
                authorPubKeyHex = pubkey,
                relays = relayHints.map { it.normalizeRelayUrl() }
            )
        }
    }
    data class NostrAddressableSegment(val dTag: String, val relays: List<String>, val author: String?, val kind: Int?) : ContentSegment {
        override fun toString(): String {
            return author?.let {
                kind?.let {
                    NAddress.create(
                        kind = kind ,
                        relays = relays.map { it.normalizeRelayUrl() },
                        pubKeyHex = author,
                        dTag = dTag
                    )
                }
            } ?: "Malformed naddress"
        }
    }
    data class CustomEmojiSegment(val shortcode: String, val url: String) : ContentSegment
    data class HashtagSegment(val tag: String) : ContentSegment
    data class LightningInvoiceSegment(val invoice: String, val decoded: Bolt11Invoice) : ContentSegment
    data class GroupInviteSegment(val relayUrl: String, val groupId: String) : ContentSegment
}