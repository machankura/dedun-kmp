plugins {
    kotlin("jvm") version "2.4.10"
    application
}
dependencies {
    // What the app resolves. Keep these at gradle/libs.versions.toml's versions: the point of
    // this probe is the engine the app ships against the one it shipped before.
    implementation("com.vitorpamplona.marmot:marmot:2.3.0")
    implementation("com.vitorpamplona.mls:mls:1.3.1")
    implementation("com.vitorpamplona.quartz:quartz:2.20.0")
    implementation("com.vitorpamplona.crypto:crypto:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("DriverKt") }
