package com.machankura.dedun.compose.nostr.chronicle

import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent

/**
 * The kinds the chronicle tests chronicle, in the order they apply.
 *
 * The app's own list, [ChronicleEvent.KINDS], is empty, so the machinery is
 * exercised with this one instead. It is not a proposal for what the app should
 * carry -- see [ChronicleEvent.KINDS] for why each of these is left out there.
 *
 * Chosen for three properties:
 *
 *  - **none is a kind the app gives a meaning of its own** -- a profile and a
 *    relay list apply to nothing, and a kind:1 to the "unsupported" line it
 *    has always been -- so applying one neither throws nor writes a
 *    `GroupSignedEvent` of its own, and the `GroupSignedEvent` rows a receiver
 *    holds afterwards are exactly the ones the chronicle recorded: comparing
 *    those is comparing the work;
 *  - **the order disagrees with the kind numbers** (10002 before 0 before 1),
 *    so a test that the order is the list's cannot pass by sorting on kind.
 *
 * A key state and a birth certificate are deliberately not here: they are what
 * the allowlist has to refuse, so they are what the tests try to replay.
 */
internal val TEST_KINDS: ChronicleKinds = ChronicleKinds(
    listOf(
        AdvertisedRelayListEvent.KIND,
        MetadataEvent.KIND,
        TextNoteEvent.KIND,
    )
)
