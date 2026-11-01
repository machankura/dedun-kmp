package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.subgroup.SubgroupParentage
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.tags.WalletPolicyTag
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.machankura.dedun.compose.nostr.Relays
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianTag
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent

/**
 * What a parent's own record says about its children, and what it refuses to say.
 *
 * There is no table of subgroups. The certificates *are* the record -- the
 * group's own signed statement, checkable without a lookup, and held by every
 * device that followed the signing session rather than only by the signers. So
 * everything a parent knows about its children is a read over
 * `GroupSignedEvent`, and these are the properties that read has to have.
 *
 * The negative cases carry the weight, as they do for chronicles. A list built
 * from signed events is only as safe as its filter: a member can put a rumor of
 * this kind into the room, and a real certificate signed by some *other* group is
 * a valid signature over a true-looking statement with no standing behind it.
 * Neither may reach the list.
 *
 * The one positive case that matters as much is a certified child whose room this
 * device does not have. That is the normal position of a parent member who is not
 * in the subgroup, and of everybody between the certificate being signed and the
 * room being created -- so a list that could only show rooms would be empty
 * exactly when it is most needed.
 */
class SubgroupManagerJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val participants = 3
    private val threshold = 2

    /** The parent group, which does the certifying. */
    private val parentMaterial: KeyMaterial = keyMaterial(
        "1c0ffee0000000000000000000000000000000000000000000000000000000a1"
    )

    /** The child. Its own ceremony, its own key, its own room. */
    private val childMaterial: KeyMaterial = keyMaterial(
        "2bada550000000000000000000000000000000000000000000000000000000b2"
    )

    /** A second child, so "one entry per child" can be told from "one entry". */
    private val siblingMaterial: KeyMaterial = keyMaterial(
        "3decade0000000000000000000000000000000000000000000000000000000c3"
    )

    /** A group with no standing here at all. */
    private val strangerMaterial: KeyMaterial = keyMaterial(
        "4facade0000000000000000000000000000000000000000000000000000000d4"
    )

    private val path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH

    private val parentRoomId = roomOf(parentMaterial)
    private val childRoomId = roomOf(childMaterial)
    private val siblingRoomId = roomOf(siblingMaterial)

    private val user = KeyPair().pubKey.toHexKey()
    private val alice = KeyPair().pubKey.toHexKey()
    private val bob = KeyPair().pubKey.toHexKey()
    private val carol = KeyPair().pubKey.toHexKey()

    @Test
    fun `a parent lists a child it certified, room or no room`(): Unit = runBlocking {
        seedRoom(parentRoomId)
        record(certificate())

        val subgroups = SubgroupManager.subgroupsOf(db, parentRoomId)

        assertEquals(1, subgroups.size)
        val subgroup = subgroups.single()

        assertEquals(childRoomId, subgroup.chatRoomId)
        assertEquals(parentRoomId, subgroup.parentChatRoomId)
        assertEquals("Translation team", subgroup.name)
        assertEquals(listOf(user, alice, bob), subgroup.adminPublicKeys)

        // The whole point of reading certificates rather than rooms: this device
        // holds no room for the child and knows about it anyway. That is the
        // position of every parent member who is not in the subgroup.
        assertNull(subgroup.room)
        assertFalse(subgroup.isJoined)
        assertNull(subgroup.keyState)
    }

    @Test
    fun `a listed child carries what its parent decided about its wallet`(): Unit = runBlocking {
        seedRoom(parentRoomId)
        record(
            certificate(
                tags = SubgroupBirthCertificateEvent.assembleTags(
                    subgroupChatRoomId = childRoomId,
                    parentChatRoomId = parentRoomId,
                    thresholdPublicKey = childMaterial.thresholdPublicKey.value.toHex(),
                    adminPublicKeys = listOf(user, alice, bob),
                    name = "Translation team",
                    path = path,
                    walletPolicy = SubgroupWalletPolicy.UNGUARDED,
                )
            )
        )
        record(certificate(subgroupMaterial = siblingMaterial))

        val subgroups = SubgroupManager.subgroupsOf(db, parentRoomId).associateBy { it.chatRoomId }

        assertEquals(WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED), subgroups.getValue(childRoomId).walletPolicy)
        assertEquals(WalletPolicyReading.Undecided, subgroups.getValue(siblingRoomId).walletPolicy, "a certificate from before policies")
    }

    @Test
    fun `a child whose room this device holds reports it`(): Unit = runBlocking {
        seedRoom(parentRoomId)
        seedRoom(childRoomId)
        record(certificate())

        val subgroup = SubgroupManager.subgroupsOf(db, parentRoomId).single()

        assertTrue(subgroup.isJoined)
        assertEquals(childRoomId, subgroup.room?.chatRoom?.id)
    }

    @Test
    fun `a certificate signed by another group is not listed`(): Unit = runBlocking {
        seedRoom(parentRoomId)

        // A real quorum and a real signature by a group that has no standing to
        // say anything about this parent's children. Filed in the parent's room
        // -- which a member can cause -- and refused on read.
        record(certificate(signer = strangerMaterial, author = strangerMaterial))

        assertTrue(SubgroupManager.subgroupsOf(db, parentRoomId).isEmpty())
        assertNull(SubgroupManager.certificateFor(db, childRoomId, parentRoomId))
    }

    @Test
    fun `a certificate nobody signed is not listed`(): Unit = runBlocking {
        seedRoom(parentRoomId)
        record(certificate(signature = "f".repeat(128)))

        assertTrue(SubgroupManager.subgroupsOf(db, parentRoomId).isEmpty())
    }

    @Test
    fun `a certificate whose id does not derive from the key it names is not listed`(): Unit = runBlocking {
        seedRoom(parentRoomId)

        record(
            certificate(
                tags = SubgroupBirthCertificateEvent.assembleTags(
                    subgroupChatRoomId = childRoomId,
                    parentChatRoomId = parentRoomId,
                    thresholdPublicKey = strangerMaterial.thresholdPublicKey.value.toHex(),
                    adminPublicKeys = listOf(user, alice, bob),
                    name = "Translation team",
                    path = path
                )
            )
        )

        assertTrue(SubgroupManager.subgroupsOf(db, parentRoomId).isEmpty())
    }

    @Test
    fun `two certificates for one child collapse to the newest`(): Unit = runBlocking {
        seedRoom(parentRoomId)

        // Two parent admins pressing the button on the same admin set. Both
        // sessions can complete and both rows coexist, because GroupSignedEvent is
        // keyed on the event id -- and both say the same true thing, so which one
        // wins does not matter. What must not happen is two entries.
        record(certificate(createdAt = 1_700_000_000, name = "First name"))
        record(certificate(createdAt = 1_700_000_900, name = "Second name"))

        val subgroups = SubgroupManager.subgroupsOf(db, parentRoomId)

        assertEquals(1, subgroups.size)
        assertEquals("Second name", subgroups.single().name)
        assertEquals(
            "Second name",
            SubgroupBirthCertificateEvent.parseName(
                assertNotNull(SubgroupManager.certificateFor(db, childRoomId, parentRoomId)).tags
            )
        )
    }

    @Test
    fun `two children are two entries, newest first`(): Unit = runBlocking {
        seedRoom(parentRoomId)

        record(certificate(createdAt = 1_700_000_000, name = "Older"))
        record(
            certificate(
                createdAt = 1_700_000_900,
                name = "Newer",
                subgroupMaterial = siblingMaterial
            )
        )

        assertEquals(
            listOf(siblingRoomId, childRoomId),
            SubgroupManager.subgroupsOf(db, parentRoomId).map { it.chatRoomId }
        )
    }

    @Test
    fun `record refuses a certificate the room did not sign`(): Unit = runBlocking {
        seedRoom(parentRoomId)

        // The path a member takes to forge one: send a rumor of kind 30329 into
        // the room and let the inbound arm file it. `record` is reached with the
        // same arguments a completed session would use and cannot tell the two
        // apart, so it checks.
        val forged = certificate(signer = strangerMaterial, author = strangerMaterial)
        record(forged)

        assertNull(SubgroupManager.record(db, parentRoomId, forged))
        assertNotNull(SubgroupManager.record(db, parentRoomId, certificate().also { record(it) }))
    }

    @Test
    fun `record refuses a certificate that names no subgroup`(): Unit = runBlocking {
        seedRoom(parentRoomId)

        val nameless = certificate(
            tags = SubgroupBirthCertificateEvent.assembleTags(
                subgroupChatRoomId = childRoomId,
                parentChatRoomId = parentRoomId,
                thresholdPublicKey = childMaterial.thresholdPublicKey.value.toHex(),
                adminPublicKeys = listOf(user),
                name = "Translation team",
                path = path
            ).filterNot { it[0] == "d" }.toTypedArray()
        )

        assertNull(SubgroupManager.record(db, parentRoomId, nameless))
    }

    // ---- the refusals ------------------------------------------------------

    @Test
    fun `a member holding no share of the parent's key is refused`(): Unit = runBlocking {
        // Not a permission rule: `proposeSigningBatch` throws outright for a
        // device with no share, so without this the button would crash rather
        // than decline. It is also the one refusal the user cannot fix by picking
        // differently, which is why it is checked first.
        val parent = parentWith(listOf(alice, bob, carol), canSign = false)

        assertNotNull(refusal(parent, setOf(alice, bob)))
    }

    @Test
    fun `a member who is not an admin of the parent is refused`(): Unit = runBlocking {
        val parent = parentWith(listOf(alice, bob, carol), isAdmin = false)

        assertNotNull(refusal(parent, setOf(alice, bob)))
    }

    @Test
    fun `an admin is read off the group's data, not off a flag no creator ever sets`(): Unit =
        runBlocking {
            // The bug this replaced. `Participant.adminAt` is written in exactly
            // one place -- `processGroupMembershipChanges`, on an arriving commit
            // -- and a device that created a room has processed none, so every
            // row reads as a non-admin including the creator's. A guard on that
            // column refused the one person certain to be an admin.
            //
            // MarmotGroupData is right from epoch 0 on both sides, so this passes
            // with every adminAt still null.
            val parent = parentWith(listOf(alice, bob, carol))

            assertTrue(
                parent.localParticipants.all { it.participant.adminAt == null },
                "the fixture must not set the flag, or it is not testing the fix",
            )
            assertNull(refusal(parent, setOf(alice, bob)))
        }

    @Test
    fun `a group with no MLS state has no admin list to consult`(): Unit = runBlocking {
        // A NIP-17 room: everybody is an equal member and there is no group
        // context to put a certificate in. The button is hidden there for the
        // same reason, and this is the guard behind it.
        (listOf(alice, bob, carol) + user).forEach { seedProfile(it) }
        val nip17 = assertNotNull(
            db.nostrNip17Dao().createNip17ChatRoom(
                userPublicKey = user,
                participantPublicKeys = listOf(alice, bob, carol),
                subject = "Ekklesia",
            )
        )

        assertNotNull(refusal(nip17, setOf(alice, bob)))
    }

    @Test
    fun `a subgroup of fewer than three admins is refused`(): Unit = runBlocking {
        val parent = parentWith(listOf(alice, bob, carol))

        assertNotNull(refusal(parent, setOf(alice)))
        assertNull(refusal(parent, setOf(alice, bob)), "the coordinator is the third")
    }

    @Test
    fun `a subgroup containing somebody who is not in the group is refused`(): Unit = runBlocking {
        val parent = parentWith(listOf(alice, bob))
        val stranger = KeyPair().pubKey.toHexKey()

        assertNotNull(refusal(parent, setOf(alice, stranger)))
    }

    @Test
    fun `a subgroup may be the whole group`(): Unit = runBlocking {
        // A subgroup is a logical division -- a group deciding that some of its
        // work belongs to a differently-keyed room -- not a smaller membership.
        // "Everybody" is a normal answer.
        //
        // This used to be refused on set size, which was wrong twice: it turned
        // down a legitimate subgroup, and it did so on a proxy for a collision
        // that a parent whose membership has changed would not even have.
        val parent = parentWith(listOf(alice, bob))

        assertNull(refusal(parent, setOf(alice, bob)))
    }

    @Test
    fun `a ceremony in the parent's room for something else does not block one`(): Unit =
        runBlocking {
            // The parent's room hosts every ceremony the group ever runs, so "is
            // this room already running one" stopped being a question worth
            // asking the moment the ceremony moved here. Scoping on the room
            // would refuse a group's second subgroup on the strength of its
            // first, which is most of what the move was for.
            val parent = parentWith(listOf(alice, bob, carol))

            db.dkgSessionDao().upsert(
                subgroupCeremony(id = "other-admins", admins = setOf(alice, carol, user))
            )
            db.dkgSessionDao().upsert(ownCeremony(parentRoomId))

            assertNull(
                refusal(parent, setOf(alice, bob)),
                "another subgroup's ceremony, and the room's own, are not this one",
            )
        }

    @Test
    fun `a subgroup ceremony already under way over the same admins is refused`(): Unit =
        runBlocking {
            val parent = parentWith(listOf(alice, bob, carol))

            assertNull(refusal(parent, setOf(alice, bob)))

            db.dkgSessionDao().upsert(
                subgroupCeremony(id = "running", admins = setOf(alice, bob, user))
            )

            // Not because two subgroups over the same people are forbidden --
            // they are not, since the move -- but because nobody can answer for
            // two live ceremonies at once: every admin would be asked twice, on
            // two ladders, for two keys, one of which nobody will ever make a
            // room from.
            assertNotNull(refusal(parent, setOf(alice, bob)))

            // A different admin set is a different subgroup, and untouched.
            assertNull(refusal(parent, setOf(alice, carol)))
        }

    @Test
    fun `a finished subgroup does not stop a second over the same admins`(): Unit = runBlocking {
        // The limitation that went away with the sibling room. A ceremony room
        // derived from its admins meant one admin set got one subgroup forever:
        // asking again handed back the first ceremony's key, so the "new"
        // subgroup was the old one under a new name. In the parent's room a
        // second ask is a second ceremony, with a key of its own.
        val parent = parentWith(listOf(alice, bob, carol))

        db.dkgSessionDao().upsert(
            subgroupCeremony(id = "done", admins = setOf(alice, bob, user))
                .copy(stage = DkgRitualStage.COMPLETE)
        )

        assertNull(refusal(parent, setOf(alice, bob)))
    }

    @Test
    fun `the stored participant set is the same whichever way the admins were picked`() {
        // The coordinator assembles the set from a picker and every other device
        // from the proposal's p-tags. They have to match as strings or the guard
        // above finds nothing and every ask opens a ceremony.
        assertEquals(
            DkgSession.formatParticipants(setOf(alice, bob, user)),
            DkgSession.formatParticipants(setOf(user, bob, alice))
        )
    }

    @Test
    fun `certifying a child the parent has already certified is refused`(): Unit = runBlocking {
        seedRoom(parentRoomId)
        record(certificate())

        val parentRoom = assertNotNull(db.chatRoomDao().findChatRoomById(parentRoomId))

        // One certificate per child: a second is a `d`-tag replacement rather
        // than a second subgroup, and spending a quorum's attention to restate
        // something they have already signed is worse than doing nothing.
        //
        // Two coordinators racing still produce two -- neither can see the
        // other's session before it completes -- which is why `certificateFor`
        // folds them. This only stops the case somebody can actually see.
        assertFailsWith<IllegalStateException> {
            SubgroupManager.proposeBirthCertificate(
                database = db,
                parentRoom = parentRoom,
                userPublicKey = user,
                key = completedChildCeremony(),
                adminPublicKeys = listOf(user, alice, bob),
                name = "Translation team",
            )
        }
    }

    @Test
    fun `the name the parent is asked to sign is the name the room will carry`(): Unit =
        runBlocking {
            // A parent that can actually sign: the proposal has to get as far as
            // writing the event whose tags are what this is about.
            val parentRoom = parentWith(listOf(alice, bob))

            val session = SubgroupManager.proposeBirthCertificate(
                database = db,
                parentRoom = parentRoom,
                userPublicKey = user,
                key = completedChildCeremony(),
                adminPublicKeys = listOf(user, alice, bob),
                // Bare, as it comes off the ceremony room's subject -- that room
                // is NIP-17 and keeps the name unmarked.
                name = "Translation team",
            )

            val proposed = Event.fromJson(
                assertNotNull(db.frostSigningSessionDao().getItems(session.id).firstOrNull())
                    .unsignedEventJson
            )

            // A quorum is being asked to sign a name, and `MarmotGroupCreation`
            // will name the room by the same rule from the same subject. If only
            // one of the two applied it, the parent would certify "Translation
            // team" and the room would be called "#Translation team".
            assertEquals(
                MarmotGroupName.of("Translation team"),
                SubgroupBirthCertificateEvent.parseName(proposed.tags)
            )
        }

    @Test
    fun `an unnamed subgroup cannot be certified`(): Unit = runBlocking {
        seedRoom(parentRoomId)
        val parentRoom = assertNotNull(db.chatRoomDao().findChatRoomById(parentRoomId))

        // The parent's admins would be approving a hash. The name is what makes
        // the proposal legible, so it is required rather than defaulted.
        assertFailsWith<IllegalArgumentException> {
            SubgroupManager.proposeBirthCertificate(
                database = db,
                parentRoom = parentRoom,
                userPublicKey = user,
                key = completedChildCeremony(),
                adminPublicKeys = listOf(user, alice, bob),
                name = "  ",
            )
        }
    }

    @Test
    fun `a certificate grants the child the parent's guardian key`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))

        val proposed = proposedCertificate(parentRoom)

        // A parent with no parent of its own grants one key: its own, one step on.
        assertEquals(
            GuardianDerivation.grant(parentMaterial.thresholdPublicKey.value.toHex(), parentRoomId, emptyList(), childRoomId),
            SubgroupBirthCertificateEvent.parseGuardians(proposed.tags)
        )
        assertNull(SubgroupManager.refuseCertificate(db, parentRoomId, parentMaterial.thresholdPublicKey.value.toHex(), proposed))
    }

    @Test
    fun `a subgroup passes on the keys its own parent granted it, one step further`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        // The parent is itself a subgroup of a grandparent, whose key reached it
        // through the parent's own certificate.
        val grandparentKey = strangerMaterial.thresholdPublicKey.value.toHex()
        val grandparentRoomId = roomOf(strangerMaterial)
        val inherited = GuardianDerivation.grant(grandparentKey, grandparentRoomId, emptyList(), parentRoomId)
        fileParentage(inherited)

        val granted = assertNotNull(SubgroupBirthCertificateEvent.parseGuardians(proposedCertificate(parentRoom).tags))

        assertEquals(listOf(parentRoomId, grandparentRoomId), granted.map { it.chatRoomId })
        assertEquals(inherited[0].grantedTo(childRoomId), granted[1])
        assertEquals(
            GuardianDerivation.root(grandparentKey).extendedPublicKey
                .derivePublicKey(GuardianDerivation.childIndex(parentRoomId))
                .derivePublicKey(GuardianDerivation.childIndex(childRoomId))
                .publicKey,
            granted[1].publicKey,
            "the grandparent's key in the grandchild is R / i(parent) / i(child)"
        )
    }

    @Test
    fun `a signer refuses guardian keys the group does not grant`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        val parentKey = parentMaterial.thresholdPublicKey.value.toHex()
        val proposed = proposedCertificate(parentRoom)
        val granted = assertNotNull(SubgroupBirthCertificateEvent.parseGuardians(proposed.tags))
        val coordinators = GuardianDerivation.grant(strangerMaterial.thresholdPublicKey.value.toHex(), parentRoomId, emptyList(), childRoomId)

        fun withGuardians(guardians: List<GuardianDerivation.Guardian>) = certificate(
            tags = proposed.tags.filterNot { it[0] == GuardianTag.TAG_NAME }.toTypedArray() +
                guardians.map(GuardianTag::assemble)
        )

        val refusals = mapOf(
            "a key of the coordinator's own" to withGuardians(coordinators),
            "an extra ancestor" to withGuardians(granted + coordinators),
            "the key not stepped for this child" to withGuardians(listOf(granted[0].copy(publicKey = GuardianDerivation.root(parentKey).publicKey))),
            "a shorter delay" to withGuardians(listOf(granted[0].copy(delay = 1))),
        )
        refusals.forEach { (name, certificate) ->
            assertNotNull(SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, certificate), name)
        }

        // An older proposer's certificate carries none, and grants nothing.
        assertNull(SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, certificate()))
    }

    // ---- the wallet policy: docs/subgroup-wallet-policy.md -----------------

    /** The parent made a subgroup of a grandparent, under [policy]. */
    private suspend fun parentIs(policy: SubgroupWalletPolicy?) {
        val grandparentRoomId = roomOf(strangerMaterial)
        val inherited = if (policy == SubgroupWalletPolicy.UNGUARDED) {
            emptyList()
        } else {
            GuardianDerivation.grant(strangerMaterial.thresholdPublicKey.value.toHex(), grandparentRoomId, emptyList(), parentRoomId)
        }
        fileParentage(inherited, policy)
    }

    private fun ceremonyUnder(policy: SubgroupWalletPolicy?) =
        completedChildCeremony().copy(walletPolicy = policy?.tagValue)

    private val guardedReading = WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED)
    private val unguardedReading = WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED)

    @Test
    fun `a guarded ceremony is certified guarded, with the grant`(): Unit = runBlocking {
        val proposed = proposedCertificate(parentWith(listOf(alice, bob)), ceremonyUnder(SubgroupWalletPolicy.GUARDED))

        assertEquals(guardedReading, SubgroupBirthCertificateEvent.parseWalletPolicy(proposed.tags))
        assertEquals(1, SubgroupBirthCertificateEvent.parseGuardians(proposed.tags)?.size)
    }

    @Test
    fun `an unguarded ceremony is certified unguarded, with no keys`(): Unit = runBlocking {
        val proposed = proposedCertificate(parentWith(listOf(alice, bob)), ceremonyUnder(SubgroupWalletPolicy.UNGUARDED))

        assertEquals(unguardedReading, SubgroupBirthCertificateEvent.parseWalletPolicy(proposed.tags))
        assertEquals(emptyList(), SubgroupBirthCertificateEvent.parseGuardians(proposed.tags))
    }

    @Test
    fun `an older build's ceremony is certified as it always was`(): Unit = runBlocking {
        val proposed = proposedCertificate(parentWith(listOf(alice, bob)), ceremonyUnder(null))

        assertEquals(WalletPolicyReading.Undecided, SubgroupBirthCertificateEvent.parseWalletPolicy(proposed.tags))
        assertEquals(1, SubgroupBirthCertificateEvent.parseGuardians(proposed.tags)?.size)
    }

    @Test
    fun `under a guarded parent an older build's ceremony is certified guarded`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        parentIs(SubgroupWalletPolicy.GUARDED)

        val proposed = proposedCertificate(parentRoom, ceremonyUnder(null))

        assertEquals(guardedReading, SubgroupBirthCertificateEvent.parseWalletPolicy(proposed.tags))
        assertEquals(2, SubgroupBirthCertificateEvent.parseGuardians(proposed.tags)?.size, "its own key and the grandparent's")
    }

    @Test
    fun `under a guarded parent an unguarded ceremony is refused, and nothing is proposed`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        parentIs(SubgroupWalletPolicy.GUARDED)

        val refused = runCatching { proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.UNGUARDED)) }

        assertTrue(refused.exceptionOrNull() is IllegalStateException)
        assertTrue("Start the subgroup again" in refused.exceptionOrNull()?.message.orEmpty(), "the remedy is in the reason")
        assertTrue(db.frostSigningSessionDao().getSessionsForChatRoom(parentRoomId).isEmpty())
    }

    @Test
    fun `under an unguarded parent an unguarded ceremony is certified`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        parentIs(SubgroupWalletPolicy.UNGUARDED)

        val proposed = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.UNGUARDED))

        assertEquals(unguardedReading, SubgroupBirthCertificateEvent.parseWalletPolicy(proposed.tags))
    }

    @Test
    fun `a signer refuses a certificate whose policy does not read or contradicts its keys`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        val parentKey = parentMaterial.thresholdPublicKey.value.toHex()
        val guarded = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.GUARDED))
        val withoutPolicy = guarded.tags.filterNot { it[0] == WalletPolicyTag.TAG_NAME }.toTypedArray()

        val refusals = mapOf(
            "unguarded with keys" to certificate(tags = withoutPolicy + WalletPolicyTag.assemble(SubgroupWalletPolicy.UNGUARDED)),
            "guarded with no keys" to certificate(
                tags = withoutPolicy.filterNot { it[0] == GuardianTag.TAG_NAME }.toTypedArray() +
                    WalletPolicyTag.assemble(SubgroupWalletPolicy.GUARDED)
            ),
            "a policy that does not read" to certificate(tags = withoutPolicy + arrayOf(WalletPolicyTag.TAG_NAME, "maybe")),
        )
        refusals.forEach { (name, certificate) ->
            assertNotNull(SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, certificate), name)
        }

        assertNull(SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, guarded))
        assertNull(
            SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.UNGUARDED))),
            "an unguarded certificate of a root group's child is signable",
        )
    }

    @Test
    fun `an older proposer's certificate is still signable under any parent but a guarded one`(): Unit = runBlocking {
        val parentKey = parentMaterial.thresholdPublicKey.value.toHex()
        parentWith(listOf(alice, bob))

        // No keys and no policy: what a build before guardians proposed.
        assertNull(SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, certificate()), "under a root group")

        listOf(SubgroupWalletPolicy.UNGUARDED, null).forEach { policy ->
            parentIs(policy)
            assertNull(SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, certificate()), "under a parent decided $policy")
        }
    }

    /**
     * D4, at the signers. The picker stops a coordinator choosing this; the
     * signers are what stop one who skipped the picker. The undecided case is the
     * one a reviewer will want to loosen -- an older proposer's certificate, keys
     * and all -- and is here to fail if anybody does: an undecided child is
     * offered the plain wallet a guarded parent's subgroups may not have.
     */
    @Test
    fun `a guarded group's signers refuse anything but a guarded certificate`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        val parentKey = parentMaterial.thresholdPublicKey.value.toHex()
        // Proposed while this device still read the parent as a root group -- the
        // coordinator's device that saw no parent certificate.
        val unguarded = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.UNGUARDED))
        val undecided = proposedCertificate(parentRoom, ceremonyUnder(null))

        // The signer's own device holds the parent's certificate.
        parentIs(SubgroupWalletPolicy.GUARDED)

        assertEquals(
            "would make an unguarded subgroup of a guarded group",
            SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, unguarded),
        )
        assertEquals(
            "would make an unguarded subgroup of a guarded group",
            SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, undecided),
        )
        assertNull(
            SubgroupManager.refuseCertificate(
                db, parentRoomId, parentKey,
                proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.GUARDED).copy(id = "second-child-ceremony", thresholdPublicKey = siblingMaterial.thresholdPublicKey.value.toHex())),
            ),
            "a guarded one, with the parent's keys passed on, is signable",
        )
    }

    /** A child key state carrying [certificate], unsigned -- the refusal reads its tags and nothing else. */
    private fun keyStateCarrying(certificate: Event) = Event(
        id = "e".repeat(64),
        pubKey = childRoomId,
        createdAt = 1_700_000_000,
        kind = GroupKeyStateEvent.KIND,
        tags = GroupKeyStateEvent.assembleTags(
            chatRoomId = childRoomId,
            dkgSessionId = "child-ceremony",
            path = path,
            parent = SubgroupParentage(parentRoomId, certificate),
        ),
        content = "",
        sig = "",
    )

    @Test
    fun `a child signer refuses a certificate under a policy its ceremony did not announce`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        val unguarded = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.UNGUARDED))
        val guarded = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.GUARDED))
        val undecided = proposedCertificate(parentRoom, ceremonyUnder(null))

        // The admins joined a guarded subgroup.
        val ceremony = ceremonyUnder(SubgroupWalletPolicy.GUARDED)
        assertNotNull(SubgroupManager.refuseKeyState(ceremony, keyStateCarrying(unguarded)))
        assertNotNull(SubgroupManager.refuseKeyState(ceremony, keyStateCarrying(undecided)), "undecided is not what they agreed to either")
        assertNull(SubgroupManager.refuseKeyState(ceremony, keyStateCarrying(guarded)))

        // An older build's ceremony announced nothing, and has nothing to hold a certificate to.
        assertNull(SubgroupManager.refuseKeyState(ceremonyUnder(null), keyStateCarrying(unguarded)))
    }

    // ---- the guardian delay: docs/guardian-delay.md, Phase 6 --------------

    @Test
    fun `a guarded ceremony's delay is the parent's own leaf's in the certificate`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        parentIs(SubgroupWalletPolicy.GUARDED)

        val proposed = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.GUARDED).copy(guardianDelay = 4_320))

        val granted = assertNotNull(SubgroupBirthCertificateEvent.parseGuardians(proposed.tags))
        assertEquals(listOf(4_320L, GuardianDerivation.DEFAULT_DELAY), granted.map { it.delay }, "the grandparent's keeps its own")
        assertNull(SubgroupManager.refuseCertificate(db, parentRoomId, parentMaterial.thresholdPublicKey.value.toHex(), proposed))
    }

    @Test
    fun `an older guarded ceremony is certified at the default delay`(): Unit = runBlocking {
        val proposed = proposedCertificate(parentWith(listOf(alice, bob)), ceremonyUnder(SubgroupWalletPolicy.GUARDED))

        assertEquals(GuardianDerivation.DEFAULT_DELAY, SubgroupBirthCertificateEvent.parseGuardians(proposed.tags)?.single()?.delay)
    }

    @Test
    fun `a signer refuses a certificate that changes an ancestor's delay`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        val parentKey = parentMaterial.thresholdPublicKey.value.toHex()
        parentIs(SubgroupWalletPolicy.GUARDED)
        val proposed = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.GUARDED).copy(guardianDelay = 144))
        val granted = assertNotNull(SubgroupBirthCertificateEvent.parseGuardians(proposed.tags))

        // The grandparent's leaf brought closer, which was never this group's to do.
        val shortened = certificate(
            tags = proposed.tags.filterNot { it[0] == GuardianTag.TAG_NAME }.toTypedArray() +
                listOf(granted[0], granted[1].copy(delay = 6)).map(GuardianTag::assemble)
        )
        assertEquals("grants guardian keys this group does not", SubgroupManager.refuseCertificate(db, parentRoomId, parentKey, shortened))
    }

    @Test
    fun `a child signer refuses a certificate under a delay its ceremony did not announce`(): Unit = runBlocking {
        val parentRoom = parentWith(listOf(alice, bob))
        val atDefault = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.GUARDED))
        val atMonth = proposedCertificate(parentRoom, ceremonyUnder(SubgroupWalletPolicy.GUARDED).copy(guardianDelay = 4_320))

        val ceremony = ceremonyUnder(SubgroupWalletPolicy.GUARDED).copy(guardianDelay = 4_320)
        assertNotNull(SubgroupManager.refuseKeyState(ceremony, keyStateCarrying(atDefault)))
        assertNull(SubgroupManager.refuseKeyState(ceremony, keyStateCarrying(atMonth)))
        // A ceremony that announced none holds the certificate to no delay.
        assertNull(SubgroupManager.refuseKeyState(ceremonyUnder(SubgroupWalletPolicy.GUARDED), keyStateCarrying(atMonth)))
    }

    // ---- fixtures ----------------------------------------------------------

    /** The certificate [parentRoom] is asked to sign for the child, as the session holds it. */
    private suspend fun proposedCertificate(
        parentRoom: LocalChatRoom,
        key: DkgSession = completedChildCeremony(),
    ): Event {
        val session = SubgroupManager.proposeBirthCertificate(
            database = db,
            parentRoom = parentRoom,
            userPublicKey = user,
            key = key,
            adminPublicKeys = listOf(user, alice, bob),
            name = "Translation team",
        )
        return Event.fromJson(assertNotNull(db.frostSigningSessionDao().getItems(session.id).firstOrNull()).unsignedEventJson)
    }

    /**
     * The parent's own key state, naming a grandparent through a certificate
     * that grants [guardians]. Filed as `stateFrom` would leave it; nothing here
     * re-checks the certificate, which is what having passed `stateFrom` means.
     */
    private suspend fun fileParentage(
        guardians: List<GuardianDerivation.Guardian>,
        walletPolicy: SubgroupWalletPolicy? = null,
    ) {
        val grandparentRoomId = roomOf(strangerMaterial)
        val tags = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = parentRoomId,
            parentChatRoomId = grandparentRoomId,
            thresholdPublicKey = parentMaterial.thresholdPublicKey.value.toHex(),
            adminPublicKeys = listOf(user, alice, bob),
            name = "Ekklesia",
            path = path,
            guardians = guardians,
            walletPolicy = walletPolicy,
        )
        val parentCertificate = certificate(subgroupMaterial = parentMaterial, tags = tags, author = strangerMaterial)
        db.groupKeyStateDao().upsert(
            GroupKeyState(
                chatRoomId = parentRoomId,
                dkgSessionId = "parent-ceremony",
                thresholdPublicKey = parentMaterial.thresholdPublicKey.value.toHex(),
                derivationPath = SharedKeyDerivation.formatPath(path),
                announcedBy = parentRoomId,
                announcedAt = Instant.fromEpochSeconds(1_700_000_000),
                parentChatRoomId = grandparentRoomId,
                birthCertificateJson = parentCertificate.toJson(),
            )
        )
    }

    /**
     * The NIP-17 room a ceremony runs in, stood up the way the picker does it --
     * room first, because `DkgSession.chatRoomId` is a foreign key onto it.
     */
    /** A ceremony held in the parent's room to make a subgroup run by [admins]. */
    private fun subgroupCeremony(id: String, admins: Set<String>) =
        ownCeremony(parentRoomId).copy(
            id = id,
            parentChatRoomId = parentRoomId,
            participantPublicKeys = DkgSession.formatParticipants(admins),
            participantCount = admins.size,
        )

    /** A ceremony held in [chatRoomId] for no subgroup: the group's own. */
    private fun ownCeremony(chatRoomId: String) = DkgSession(
        id = "own-ceremony",
        chatRoomId = chatRoomId,
        coordinatorPublicKey = user,
        userPublicKey = user,
        threshold = 2,
        participantCount = 3,
        hostPublicKey = user,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
    )

    /** A finished ceremony for the child, as far as these guards can tell. */
    private fun completedChildCeremony() = DkgSession(
        id = "child-ceremony",
        chatRoomId = parentRoomId,
        coordinatorPublicKey = user,
        userPublicKey = user,
        threshold = 2,
        participantCount = 3,
        stage = DkgRitualStage.COMPLETE,
        hostPublicKey = user,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
        thresholdPublicKey = childMaterial.thresholdPublicKey.value.toHex(),
        secretShare = "dd".repeat(32),
    )


    private fun keyMaterial(secret: String): KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32(secret)),
        nParticipants = participants,
        threshold = threshold
    )

    private fun roomOf(material: KeyMaterial): String =
        SharedKeyDerivation.marmotGroupId(material.thresholdPublicKey.value.toHex(), path)

    private suspend fun refusal(parent: LocalChatRoom, admins: Set<String>): String? =
        SubgroupManager.refuseSubgroup(
            database = db,
            parentRoom = parent,
            adminPublicKeys = admins,
            coordinatorPublicKey = user
        )

    /**
     * A parent this device could actually make a subgroup of: an admin of it, and
     * holding a share of its key.
     *
     * Both are conditions of `refuseSubgroup` before it looks at the picking
     * at all, so a fixture without them tests only the first refusal. [canSign]
     * and [isAdmin] are here so the tests that are *about* those two can turn
     * each off on its own.
     */
    /**
     * A parent this device could actually make a subgroup of.
     *
     * Built as what a real parent *is* -- the admin room derived from the
     * group's own key -- rather than as a NIP-17 room with MLS state bolted on.
     * That is not fussiness: `ChatRoom.deriveChatRoomId` returns a 33-byte
     * compressed key (66 hex) and `MarmotGroupData.nostrGroupId` takes 32 bytes,
     * so a NIP-17 id put in that field produces an extension `currentMarmotData()`
     * reads back as **null**, silently. The fixture that did it tested nothing but
     * that null.
     *
     * [canSign] and [isAdmin] are switchable so the tests about those two can turn
     * each off on its own; both are conditions `refuseSubgroup` checks before
     * it looks at the picking at all.
     */
    private suspend fun parentWith(
        members: List<String>,
        canSign: Boolean = true,
        isAdmin: Boolean = true,
    ): LocalChatRoom {
        (members + user).forEach { seedProfile(it) }

        val admins = if (isAdmin) members + user else members

        val metadata = MarmotGroupData(
            nostrGroupId = parentRoomId,
            name = "Ekklesia",
            description = SharedKeyDerivation.describe("Admins of Ekklesia."),
            adminPubkeys = admins,
            relays = Relays.DefaultDMRelayList.map { it.url }
        )
        val mlsGroup = MlsGroup.create(
            KeyPair().pubKey,
            Ed25519.generateKeyPair().privateKey,
            listOf(metadata.toExtension())
        )

        db.chatRoomDao().upsert(
            ChatRoom(
                id = parentRoomId,
                userPublicKey = user,
                subject = "Ekklesia",
                description = metadata.description,
                mlsGroupState = mlsGroup.saveState().encodeTls().toHex(),
            )
        )

        // Deliberately with `adminAt` left null on every one of them. That column
        // is only ever written by `processGroupMembershipChanges`, on an arriving
        // commit, so on a device that made the room it is null for everybody --
        // which is the state the guard has to work in.
        db.participantDao().upsert(
            (members + user).map {
                Participant(
                    participantPublicKey = it,
                    chatRoomId = parentRoomId,
                    relayHint = null,
                )
            }
        )

        if (canSign) {
            // `completedKey` rederives each key-holding ceremony's room and
            // matches, which is exactly how a real admin room resolves its key.
            db.dkgSessionDao().upsert(
                DkgSession(
                    id = "parent-ceremony",
                    chatRoomId = parentRoomId,
                    coordinatorPublicKey = user,
                    userPublicKey = user,
                    threshold = 2,
                    participantCount = members.size + 1,
                    stage = DkgRitualStage.COMPLETE,
                    hostPublicKey = user,
                    round1Random = "aa".repeat(32),
                    round2AuxRandom = "bb".repeat(32),
                    thresholdPublicKey = parentMaterial.thresholdPublicKey.value.toHex(),
                    secretShare = "cc".repeat(32),
                )
            )

            // A ceremony's signer ids come from its host keys, sorted -- there is
            // no stored signer id to seed. Without these the parent holds a key it
            // cannot find itself in, and `proposeSigningBatch` refuses before it
            // writes anything.
            (members + user).forEachIndexed { index, member ->
                db.dkgSessionDao().upsert(
                    DkgParticipantMessage(
                        sessionId = "parent-ceremony",
                        participantPublicKey = member,
                        kind = DkgRitualEvents.HOST_KEY,
                        payload = "0${index + 1}".repeat(33)
                    )
                )
            }
        }

        return assertNotNull(db.chatRoomDao().findChatRoomById(parentRoomId))
    }

    private suspend fun seedProfile(publicKey: String) {
        if (db.profileDao().getProfileByPublicKey(publicKey) != null) return

        val nostrEventId = publicKey.take(63) + "f"
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = publicKey,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(
            Profile(publicKey = publicKey, userName = "member", nostrEventId = nostrEventId)
        )
    }

    private suspend fun seedRoom(id: String) {
        seedProfile(user)
        db.chatRoomDao().upsert(
            ChatRoom(
                id = id,
                userPublicKey = user,
                subject = null,
                description = null,
                mlsGroupState = null,
            )
        )
    }

    /** Files a certificate in the parent's room, the way a session would. */
    private suspend fun record(event: Event) =
        db.groupSignedEventDao().upsert(
            GroupSignedEvent.fromEvent(
                event = event,
                chatRoomId = parentRoomId,
                derivationPath = SharedKeyDerivation.formatPath(path),
                frostSigningSessionId = null,
            )
        )

    private fun certificate(
        subgroupMaterial: KeyMaterial = childMaterial,
        name: String = "Translation team",
        adminPublicKeys: List<String> = listOf(user, alice, bob),
        tags: Array<Array<String>>? = null,
        createdAt: Long = 1_700_000_000,
        author: KeyMaterial = parentMaterial,
        signer: KeyMaterial = author,
        signature: String? = null
    ): Event {
        val subgroupChatRoomId = roomOf(subgroupMaterial)
        val eventTags = tags ?: SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = subgroupChatRoomId,
            parentChatRoomId = parentRoomId,
            thresholdPublicKey = subgroupMaterial.thresholdPublicKey.value.toHex(),
            adminPublicKeys = adminPublicKeys,
            name = name,
            path = path
        )

        val pubKey = SharedKeyDerivation
            .derive(author.thresholdPublicKey.value.toHex(), path)
            .hex

        val id = EventHasher.hashId(
            pubKey = pubKey,
            createdAt = createdAt,
            kind = SubgroupBirthCertificateEvent.KIND,
            tags = eventTags,
            content = subgroupChatRoomId
        )

        return Event(
            id = id,
            pubKey = pubKey,
            createdAt = createdAt,
            kind = SubgroupBirthCertificateEvent.KIND,
            tags = eventTags,
            content = subgroupChatRoomId,
            sig = signature ?: groupSignature(signer, id)
        )
    }

    private fun groupSignature(material: KeyMaterial, eventId: String): String {
        val cache = SharedKeyDerivation
            .derive(material.thresholdPublicKey.value.toHex(), path)
            .cache
        val message = ByteVector(eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
                secretShare = material.secretShares[signerId],
                publicShare = material.publicShares[signerId],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null
            )
        }

        val signingSession = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { material.publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = cache,
            message = message
        )

        val partials = signerIds.mapIndexed { position, signerId ->
            signingSession.sign(
                nonces[position].first,
                material.secretShares[signerId],
                signerId.toUInt()
            ).right!!
        }

        return signingSession.aggregateSigs(partials).right!!.toByteArray().toHex()
    }
}
