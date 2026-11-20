import com.vitorpamplona.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.marmot.groups.MarmotGroupPolicy
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import java.io.BufferedReader
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * The engine the app ships (marmot-kmp 2.3.0 over mls-kmp 1.3.1) against the one it shipped
 * before (quartz 1.15.2), both ways round, one commit past the Welcome each way.
 *
 * mls-kmp 1.3.1 changed what a new group's epoch 0 is -- a random epoch secret and the
 * RFC 9420 §11 interim transcript hash where quartz 1.15.2 left it empty -- so a room one of
 * them creates starts from an epoch 0 the other would not have built. The Welcome carries the
 * confirmed transcript, which argues that nobody notices; the commits after it are where a
 * disagreement would surface, because the joiner checks each one's confirmation tag against a
 * transcript it computed itself. So in each scenario the room's creator -- its only admin, and
 * under MIP-03 the only member who may commit an Add -- commits two more adds, and the other
 * engine applies them. Between the two scenarios each engine authors commits and each applies
 * the other's.
 *
 * Usage: `run --args=<dir holding the old peer's jars>`. docs/tools/engine-interop/README.md.
 * docs/quartz-mls-marmot-update.md, Phase 3.
 */
fun main(args: Array<String>) {
    val libDir = File(args.firstOrNull() ?: error("pass the old peer's lib directory"))
    val failures = mutableListOf<String>()
    for ((name, scenario) in listOf(
        "the new engine creates the room, the old one joins" to ::newCreatesOldJoins,
        "the old engine creates the room, the new one joins" to ::oldCreatesNewJoins,
    )) {
        println("== $name")
        OldPeer(libDir).use { old ->
            runCatching { runBlocking { scenario(old) } }.onFailure {
                println("   FAIL ${it.message}")
                failures += name
            }
        }
    }
    println(if (failures.isEmpty()) "ALL PASSED" else "FAILED: ${failures.joinToString("; ")}")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

private suspend fun freshKeyPackage() = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(KeyPair())).keyPackage.toTlsBytes().toHexKey()

private fun MlsGroup.state() = "$epoch ${exporterSecret("marmot", "group-event".encodeToByteArray(), 32).toHexKey()}"

private fun check(what: String, ok: Boolean, detail: () -> String = { "" }) {
    if (!ok) error("$what ${detail()}")
    println("   ok   $what")
}

private fun agree(step: String, new: MlsGroup, old: OldPeer) {
    val ours = new.state()
    val theirs = old.call("state")
    check("$step: both at epoch ${new.epoch} with the same kind:445 key", ours == theirs) { "-- new '$ours', old '$theirs'" }
}

private fun talk(step: String, new: MlsGroup, old: OldPeer) {
    val toOld = "new to old, $step"
    check("$step: the old engine reads the new one", old.call("dec ${new.encrypt(toOld.encodeToByteArray()).toHexKey()}").hexToByteArray().decodeToString() == toOld)
    val toNew = "old to new, $step"
    val sealed = old.call("enc ${toNew.encodeToByteArray().toHexKey()}").hexToByteArray()
    check("$step: the new engine reads the old one", new.decrypt(sealed).content.decodeToString() == toNew)
}

private suspend fun newCreatesOldJoins(old: OldPeer) {
    val room = CurrentProfileGroupFactory.createGroup(NostrSignerInternal(KeyPair()), ByteArray(32) { 1 }, listOf("wss://relay.example.com"))
    check("the new room starts at epoch 0", room.epoch == 0L)

    val (welcome, _) = room.addMember(old.call("kp").hexToByteArray()).let { it.welcomeBytes!! to it.framedCommitBytes }
    old.call("join ${welcome.toHexKey()}")
    agree("after the Welcome", room, old)
    talk("epoch 1", room, old)

    old.call("commit ${room.addMember(freshKeyPackage().hexToByteArray()).framedCommitBytes.toHexKey()}")
    agree("after the new engine's first commit past the Welcome", room, old)
    talk("epoch 2", room, old)

    old.call("commit ${room.addMember(freshKeyPackage().hexToByteArray()).framedCommitBytes.toHexKey()}")
    agree("after its second", room, old)
    talk("epoch 3", room, old)
}

private suspend fun oldCreatesNewJoins(old: OldPeer) {
    old.call("create ${ByteArray(32) { 2 }.toHexKey()}")
    check("the old room starts at epoch 0", old.call("state").startsWith("0 "))

    val bundle = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(KeyPair()))
    val (welcome, _) = old.call("add ${bundle.keyPackage.toTlsBytes().toHexKey()}").split(" ")
    val joined = MlsGroup.processWelcome(welcome.hexToByteArray(), bundle, MarmotGroupPolicy)
    agree("after the Welcome", joined, old)
    talk("epoch 1", joined, old)

    joined.processFramedCommit(old.call("add ${freshKeyPackage()}").split(" ")[1].hexToByteArray())
    agree("after the old engine's first commit past the Welcome", joined, old)
    talk("epoch 2", joined, old)

    joined.processFramedCommit(old.call("add ${freshKeyPackage()}").split(" ")[1].hexToByteArray())
    agree("after its second", joined, old)
    talk("epoch 3", joined, old)
}

/** The quartz 1.15.2 member, in its own JVM. See OldPeer.kt in ../old for its commands. */
private class OldPeer(libDir: File) : AutoCloseable {
    private val java = ProcessHandle.current().info().command().orElse("java")
    private val process = ProcessBuilder(java, "-cp", "${libDir.absolutePath}/*", "OldPeerKt").redirectErrorStream(false).start()
    private val input = process.outputStream.bufferedWriter()
    private val output: BufferedReader = process.inputStream.bufferedReader()

    fun call(command: String): String {
        input.write(command)
        input.newLine()
        input.flush()
        val reply = output.readLine() ?: error("the old peer exited: ${process.errorStream.bufferedReader().readText().take(500)}")
        if (reply.startsWith("error ")) error("old peer, '${command.take(12)}…': ${reply.removePrefix("error ")}")
        return reply
    }

    override fun close() {
        input.close()
        process.waitFor()
    }
}
