rootProject.name = "engine-interop-new"
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
// The same groups, plugin and versions as the app's own settings and catalog.
plugins {
    id("com.vitorpamplona.magpie") version "0.2.3"
}
magpie {
    group("com.vitorpamplona.quartz")
    group("com.vitorpamplona.marmot")
    group("com.vitorpamplona.mls")
    group("com.vitorpamplona.crypto")
    group("com.vitorpamplona.negentropy")
    group("com.vitorpamplona.schnorr256k1")
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
