rootProject.name = "Dedun"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        // The wavelength daemon's gomobile bindings, built from the source the submodule pins
        // by the script named below. wavelength-kmp's own settings declare the same repository,
        // but its wavelength-embedded names them as `implementation`, so they reach this build's
        // runtime classpath and have to resolve here too.
        exclusiveContent {
            forRepository {
                maven(file("experimental/wavelength-kmp/bindings/maven")) { name = "wavelengthBindings" }
            }
            filter { includeModule("engineering.lightning.wavelength", "wavelength-bindings-android") }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")

// Composite build: substitute the external module coordinate with the submodule's :library project.
includeBuild("lightning-kmp-app") {
    dependencySubstitution {
        // the submodule's project stays named :library (compose-resources derives the Res package
        // from it); only the coordinate consumers declare is renamed.
        substitute(module("fr.acinq.phoenix:lightning-kmp-app")).using(project(":library"))
    }
}

// Declared explicitly, which also turns off automatic substitution for this build: the
// submodule has a :wavelength-bindings-android project with the bindings' own coordinate, which
// only publishes and has nothing to consume. Those come from the file repository above.
includeBuild("experimental/wavelength-kmp") {
    dependencySubstitution {
        substitute(module("engineering.lightning.wavelength:wavelength-core")).using(project(":wavelength-core"))
        substitute(module("engineering.lightning.wavelength:wavelength-engine")).using(project(":wavelength-engine"))
        substitute(module("engineering.lightning.wavelength:wavelength-embedded")).using(project(":wavelength-embedded"))
        substitute(module("engineering.lightning.wavelength:wavelength-testing")).using(project(":wavelength-testing"))
    }
}

// The bindings have to be there before Gradle resolves anything, so no task of this build can
// stage them: its dependency graph is worked out before any task runs. They take some minutes to
// build and only Android needs them, so a stale or missing set is a warning rather than a build
// step, and the desktop and jvmTest go on without them.
val wavelengthStamp = file("experimental/wavelength-kmp/bindings/android/SOURCE")
val wavelengthCommit = providers.exec {
    workingDir = file("experimental/wavelength-kmp/experimental/wavelength")
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()
if (!wavelengthStamp.isFile || wavelengthStamp.readText().trim() != wavelengthCommit) {
    logger.warn(
        "The wavelength bindings are missing or not built from $wavelengthCommit. Android builds " +
            "need them: experimental/wavelength-kmp/scripts/fetch-bindings.sh android",
    )
}
