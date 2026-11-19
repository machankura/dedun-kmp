import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair

/**
 * Writes an MLS group state blob with the engine the app shipped before it moved to
 * marmot-kmp: quartz 1.15.2, STATE_VERSION 4. The blob is pinned as `OldEngineBlob` and read
 * by `MlsGroupStateCompatibilityTest`, which decodes it with mls-kmp, and by
 * `ShippedProfileUpgradeJvmTest`, which opens a v36 database holding it.
 * docs/marmot-kmp.md, Phases 4 and 9.
 *
 * Deterministic where it can be: the identity and the group id are fixed, so re-running
 * this prints a blob of the same shape. The keys inside are random by construction.
 */
fun main() {
    val creator = KeyPair()
    val creatorHex = creator.pubKey.toHexKey()
    val roomId = "a".repeat(64)

    val metadata =
        MarmotGroupData.bootstrap(
            nostrGroupId = roomId,
            creatorPubKey = creatorHex,
            outboxRelays = listOf("wss://relay.example.com"),
            name = "Fixture",
            description = "A legacy MIP-01 room written by quartz 1.15.2",
        )

    val group =
        MlsGroup.create(
            creatorHex.let { hex -> ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } },
            null,
            listOf(metadata.toExtension()),
        )

    val state = group.saveState()
    val bytes = state.encodeTls()
    val hex = bytes.toHexKey()

    println("creator=$creatorHex")
    println("roomId=$roomId")
    println("epoch=${state.groupContext.epoch}")
    println("stateVersion=${bytes[0].toInt().and(0xff).shl(8) or bytes[1].toInt().and(0xff)}")
    println("bytes=${bytes.size}")
    println("blob=$hex")
}
