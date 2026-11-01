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
        mavenCentral()
        // Second's Bark SDK is published to GitLab's registry, not to Maven Central
        // (docs/bark-wallet.md, Phase 2). Only its own group may resolve from there.
        maven {
            url = uri("https://gitlab.com/api/v4/projects/78057981/packages/maven")
            mavenContent { includeGroup("tech.second.bark") }
        }
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