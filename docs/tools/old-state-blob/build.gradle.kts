plugins {
    kotlin("jvm") version "2.4.10"
    application
}
repositories { google(); mavenCentral() }
dependencies {
    // The engine the app shipped before the move. This project exists only to write a
    // state blob the new engine then has to read. docs/marmot-kmp.md, Phase 4.
    implementation("com.vitorpamplona.quartz:quartz:1.15.2")
    runtimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("MainKt") }
