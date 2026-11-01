package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip28PublicChat.list.tags.ChannelTag
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayInfo
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayType

object Relays {
    // Still on the mantra.press host four rebrands later -- Mantra to Curated, Curated
    // to Curare, Curare to Bennu, Bennu to Dedun -- because this is a running relay, not a brand string. It is in the
    // bootstrap, publish, finder and DM sets, so pointing it at a name nobody has stood
    // up yet would take the app off the network. Swap it when a Dedun relay exists to
    // swap it for.
    val ephemeral = RelayUrlNormalizer.normalize("wss://ephemeral.mantra.press")

    val nos = RelayUrlNormalizer.normalize("wss://nos.lol")
    val mom = RelayUrlNormalizer.normalize("wss://nostr.mom")
    val primal = RelayUrlNormalizer.normalize("wss://relay.primal.net")
    val damus = RelayUrlNormalizer.normalize("wss://relay.damus.io")
    val wine = RelayUrlNormalizer.normalize("wss://nostr.wine")

    val where = RelayUrlNormalizer.normalize("wss://relay.noswhere.com")
    val elites = RelayUrlNormalizer.normalize("wss://nostrelites.org")

    val bitcoiner = RelayUrlNormalizer.normalize("wss://nostr.bitcoiner.social")
    val oxtr = RelayUrlNormalizer.normalize("wss://nostr.oxtr.dev")

    val nostoday = RelayUrlNormalizer.normalize("wss://search.nos.today")
    val antiprimal = RelayUrlNormalizer.normalize("wss://antiprimal.net")
    val ditto = RelayUrlNormalizer.normalize("wss://relay.ditto.pub")

    val auth = RelayUrlNormalizer.normalize("wss://auth.nostr1.com")
    val oxchat = RelayUrlNormalizer.normalize("wss://relay.0xchat.com")

    val news = RelayUrlNormalizer.normalize("wss://news.utxo.one")

    val purplepages = RelayUrlNormalizer.normalize("wss://purplepag.es")
    val coracle = RelayUrlNormalizer.normalize("wss://indexer.coracle.social")
    val userkinds = RelayUrlNormalizer.normalize("wss://user.kindpag.es")
    val yabu = RelayUrlNormalizer.normalize("wss://directory.yabu.me")
    val nostr1 = RelayUrlNormalizer.normalize("wss://profiles.nostr1.com")

    val bootstrapInboxRelaySet = setOf(ephemeral, damus, primal, mom, nos, bitcoiner, oxtr, yabu)
    val eventFinderRelaySet = setOf(ephemeral, wine, damus, mom, primal, nos, bitcoiner, oxtr)
    val eventPublishRelaySet = setOf(ephemeral, oxtr, damus, wine, mom, primal, nos, bitcoiner)

    val negentropicRelaySet = setOf(ephemeral, damus, yabu, oxtr, bitcoiner, nos)
    val searchQuerableRelaySet = setOf(primal)

    val DefaultNIP65RelaySet = setOf(mom, nos, bitcoiner, wine)

    val DefaultNIP65List =
        listOf(
            AdvertisedRelayInfo(mom, AdvertisedRelayType.BOTH),
            AdvertisedRelayInfo(nos, AdvertisedRelayType.BOTH),
            AdvertisedRelayInfo(bitcoiner, AdvertisedRelayType.BOTH),
        )

    val DefaultGlobalRelays = listOf(wine, news)

    val DefaultDMRelayList = listOf(ephemeral)

    val DefaultSearchRelayList = setOf(ephemeral, wine, where, nostoday, antiprimal, ditto)

    val DefaultIndexerRelayList = setOf(purplepages, coracle, userkinds, yabu, nostr1)

    val DefaultChannels =
        listOf(
            // Anigma's Nostr
            ChannelTag("25e5c82273a271cb1a840d0060391a0bf4965cafeb029d5ab55350b418953fbb", nos),
            // Amethyst's Group
            ChannelTag("42224859763652914db53052103f0b744df79dfc4efef7e950fc0802fc3df3c5", nos),
        )
}