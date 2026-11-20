plugins {
    kotlin("jvm") version "2.4.10"
    application
}
dependencies {
    // The engine the app shipped before marmot-kmp, and the one a Bennu peer on that version
    // runs. From Maven Central: this build is separate from ../new because Magpie serves the
    // quartz group exclusively, and its relay has nothing older than 2.19.0.
    implementation("com.vitorpamplona.quartz:quartz:1.15.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("OldPeerKt") }
