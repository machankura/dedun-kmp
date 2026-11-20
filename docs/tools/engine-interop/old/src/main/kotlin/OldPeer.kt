import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.runBlocking

/**
 * One Marmot member played by the engine the app shipped before marmot-kmp: quartz 1.15.2,
 * whose MLS engine is the one a Bennu peer still on that version runs. It reads one command
 * per line on stdin and answers one line on stdout, so the new engine can drive it from
 * another JVM -- the two cannot share a classpath, being two versions of one Maven group.
 *
 *   pubkey                -> <hex>                    this member's nostr pubkey
 *   kp                    -> <hex>                    a current-profile KeyPackage; its bundle is kept
 *   create <roomIdHex>    -> ok                       a current-profile group, this member its admin
 *   join <welcomeHex>     -> ok                       joins with the bundle `kp` kept
 *   add <kpHex>           -> <welcomeHex> <commitHex> commits the add, applied here
 *   commit <commitHex>    -> ok                       applies a framed commit someone else sent
 *   state                 -> <epoch> <exporterHex>    the epoch and the kind:445 exporter secret
 *   enc <plaintextHex>    -> <ciphertextHex>
 *   dec <ciphertextHex>   -> <plaintextHex>
 *
 * Any failure answers `error <message>` and the driver fails the scenario.
 * docs/quartz-mls-marmot-update.md, Phase 3.
 */
fun main() {
    val signer = NostrSignerInternal(KeyPair())
    var bundle: KeyPackageBundle? = null
    var group: MlsGroup? = null

    fun g() = checkNotNull(group) { "no group yet" }

    while (true) {
        val line = readLine() ?: return
        val parts = line.trim().split(" ")
        val reply =
            runCatching {
                runBlocking {
                    when (parts[0]) {
                        "pubkey" -> signer.pubKey
                        "kp" -> CurrentProfileGroupFactory.createKeyPackage(signer).also { bundle = it }.keyPackage.toTlsBytes().toHexKey()
                        "create" -> {
                            group = CurrentProfileGroupFactory.createGroup(signer, parts[1].hexToByteArray(), listOf("wss://relay.example.com"))
                            "ok"
                        }
                        "join" -> {
                            group = MlsGroup.processWelcome(parts[1].hexToByteArray(), checkNotNull(bundle) { "no bundle" })
                            "ok"
                        }
                        "add" -> {
                            val result = g().addMember(parts[1].hexToByteArray())
                            "${checkNotNull(result.welcomeBytes).toHexKey()} ${result.framedCommitBytes.toHexKey()}"
                        }
                        "commit" -> {
                            g().processFramedCommit(parts[1].hexToByteArray())
                            "ok"
                        }
                        "state" -> "${g().epoch} ${g().exporterSecret("marmot", "group-event".encodeToByteArray(), 32).toHexKey()}"
                        "enc" -> g().encrypt(parts[1].hexToByteArray()).toHexKey()
                        "dec" -> g().decrypt(parts[1].hexToByteArray()).content.toHexKey()
                        else -> error("unknown command ${parts[0]}")
                    }
                }
            }.getOrElse { "error ${it::class.simpleName}: ${it.message?.lines()?.first()}" }
        println(reply)
        System.out.flush()
    }
}
