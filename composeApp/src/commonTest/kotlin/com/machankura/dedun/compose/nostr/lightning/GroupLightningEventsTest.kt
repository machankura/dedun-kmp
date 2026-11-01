package com.machankura.dedun.compose.nostr.lightning

import com.machankura.dedun.compose.nostr.MarmotDirectMessage
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.chronicle.ChronicleRequestEvent
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.GroupKeyNoticeEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Lightning wallet's kinds stand apart from every family the inbound path
 * already dispatches on. The FROST family's note says its own separation from
 * the DKG's was "an accident of routing rather than a decision"; this test is
 * the decision, written down where a new kind would break it.
 */
class GroupLightningEventsTest {
    private val taken = DkgRitualEvents.ALL + FrostSigningEvents.ALL + setOf(
        GroupKeyStateEvent.KIND,
        ChronicleEvent.KIND,
        ChronicleRequestEvent.KIND,
        SubgroupBirthCertificateEvent.KIND,
        MarmotDirectMessage.ENVELOPE_KIND,
        GroupWalletStatementEvent.KIND,
        // The guarded-wallet family, which took 30333-30337 while this one was being built under
        // those numbers: the reason it moved.
        GuardedWalletNoticeEvent.KIND,
        MemberWalletCertificateEvent.KIND,
        MemberWalletRequestEvent.KIND,
        GroupKeyNoticeEvent.KIND,
        MemberWalletPolicyEvent.KIND,
    ) + (30300..30312).toSet()   // the nip30303 document kinds, gone but not to be reused

    @Test
    fun `no lightning kind is a kind the app already routes`() {
        assertEquals(emptySet(), GroupLightningEvents.ALL intersect taken)
    }

    @Test
    fun `the family starts past every kind the app uses, so the next family can follow it`() {
        assertTrue(GroupLightningEvents.ALL.min() > taken.max(), "a Lightning kind sits inside another family's range")
    }

    @Test
    fun `the family is one unbroken run from 30350 to 30366`() {
        assertEquals((30350..30366).toSet(), GroupLightningEvents.ALL)
    }

    @Test
    fun `the groups partition the family`() {
        val groups = listOf(
            GroupLightningEvents.CEREMONY,
            GroupLightningEvents.CO_SIGNING,
            GroupLightningEvents.WALLET_TRAFFIC,
            GroupLightningEvents.GROUP_SIGNED,
        )
        assertEquals(GroupLightningEvents.ALL.size, groups.sumOf { it.size })
        for (i in groups.indices) for (j in i + 1 until groups.size) {
            assertTrue((groups[i] intersect groups[j]).isEmpty(), "groups $i and $j overlap")
        }
    }
}
