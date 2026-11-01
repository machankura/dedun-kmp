import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
//    alias(libs.plugins.androidx.room)
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.ksp)
    // No sqldelight plugin here on purpose. The fr.acinq.phoenix.db.sqldelight databases this
    // module used to generate are the same three lightning-kmp-app's :library generates from an
    // identical copy of the .sq sources, and nothing under com.machankura.dedun touches them. Generating
    // them on both sides put every generated class in the apk twice, which debug tolerates and
    // mergeDexRelease rejects as duplicate types. The library's copies are the ones that count.
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    
    // Declared only on a mac, mirroring the gate lightning-kmp-app's :library applies to its own ios
    // targets. Down the composite chain secp256k1-kmp declares a libsecp256k1 cinterop, which makes
    // gradle switch off klib cross compilation for apple targets, so on a linux/windows host nothing
    // in the build tree offers an ios variant of fr.acinq.phoenix:lightning-kmp-app. Declaring these
    // targets anyway leaves every ios compilation unable to resolve it and fails the build outright.
    // Building an ios binary needs a mac regardless.
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        listOf(
            iosArm64(),
            iosSimulatorArm64()
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }
    
    jvm()
    
    sourceSets {
        androidMain.dependencies {
            // The android secp256k1 natives. Nothing pulls these in transitively: lightning-kmp-core
            // publishes no android variant, so the android target resolves it to the jvm one, which
            // asks for secp256k1-kmp-jni-jvm and gets desktop .so/.dylib/.dll files that cannot load
            // on a device. The app has to name the android artifact itself.
            //
            // Resolved from source via the composite build, not from Maven Central -- bitcoin-kmp's
            // settings.gradle.kts substitutes this coordinate for secp256k1-kmp's :jni:android. That
            // matters: the stock artifact has no ChillDKG module, so ChillDKG would compile and then
            // fail with UnsatisfiedLinkError. The version is never resolved; substitution matches on
            // group:name.
            implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0")

            implementation(libs.androidx.activity.compose)
//            implementation(libs.androidx.room.sqlite.wrapper)
            implementation(libs.compose.uiToolingPreview)

            implementation(libs.sqldelight.android.driver)
        }
        commonMain.dependencies {
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)

            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

//            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation (libs.compose.material.icons.core)
            implementation (libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)


            implementation(libs.kermit)

            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)


            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)



//            implementation(libs.lightning.kmp.core)

            // resolved via the lightning-kmp-app composite build (see settings.gradle.kts)
            implementation("fr.acinq.phoenix:lightning-kmp-app:1.0.0")

            implementation(libs.navigation.compose)

            implementation(libs.okio)

            implementation(libs.qrose)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)

            implementation(libs.vitorpamplona.quartz)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // runTest: the DAO, model and relay layers are all suspending, and there is no
            // runBlocking in a common source set — so anything worth asserting about them
            // needs a coroutine, and a scheduler, to assert it in.
            implementation(libs.kotlinx.coroutinesTest)
            // MockEngine: the Lightning address resolver's every answer, a redirect and an
            // oversized body included, without a server (docs/group-payout-destination.md, Phase 3).
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            // A layout modifier cannot be asserted by reading it. `readableContent()` is
            // three modifiers whose order decides whether the content is capped, centred,
            // both or neither, and every ordering compiles and renders something -- so the
            // check has to be a measurement of a real composition. Pinned to the same
            // version as the rest of Compose Multiplatform; test-only.
            implementation(compose.desktop.uiTestJUnit4)
            implementation(libs.kotlin.testJunit)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

            // The jvm counterpart to sqldelight-android-driver / -native-driver above.
            implementation(libs.sqldelight.sqlite.driver)
        }
        // Only exists when the ios targets above were declared; the default hierarchy template
        // creates this source set from them.
        if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            iosMain.dependencies {
                implementation(libs.sqldelight.native.driver)

                // CIO has no TLS implementation on native ("TLS sessions are not
                // supported on Native platform"), so every wss/https call it makes on
                // a device fails at the handshake. Darwin (NSURLSession) is the engine
                // apple targets must use.
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

/**
 * Kotlin 2.4.10's release backend crashes linking this module's ios binaries:
 *
 *     e: java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
 *         at org.jetbrains.kotlin.backend.konan.optimizations.StaticInitializersOptimization
 *            $InterproceduralAnalysis.getCalledInitializer(StaticInitializersOptimization.kt:244)
 *
 * `RemoveRedundantCallsToStaticInitializersPhase` -- an LTO pass, so only optimized links hit
 * it, which is why the debug test binary links fine -- assumes every function in the call
 * graph has at least one statement in its body, and something in this module's graph arrives
 * with an empty one. The pass is an optimization only; disabling it costs the redundant
 * initializer calls it would have removed and nothing else.
 */
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    if (name.startsWith("linkRelease")) {
        toolOptions.freeCompilerArgs.add("-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase")
    }
}

android {
    namespace = "com.machankura.dedun.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        // The store identity and the namespace are one value again. They were split when
        // the app was com.it.curated so the base package could land verbatim as the
        // applicationId; the rebrand collapsed them, so `BuildConfig` and the manifest's
        // relative `android:name=".DedunApplication"` resolve against this same package.
        //
        // Dedun is a new app to Play and to the device, not an update of Bennu: the old
        // `com.machankura.bennu.android` sandbox, its database and its keys stay where they
        // were and nothing here reads them. That is deliberate -- Dedun's users are new
        // users (docs/dedun-rename.md, D5).
        applicationId = "com.machankura.dedun.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 9
        versionName = "0.1.5"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests {
            // Quartz logs through android.util.Log on the error paths -- parsing a
            // malformed event, for one. Unmocked, those methods throw, so a test
            // covering such a path fails on the log line rather than on what it
            // came to check. Default values let the code under test carry on.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
//    ksp(libs.androidx.room3.compiler)
    add("kspAndroid", libs.androidx.room3.compiler)
    // These configurations only exist when the ios targets are declared, which the kotlin block
    // above does only on a mac.
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
//        add("kspIosX64", libs.androidx.room.compiler)
        add("kspIosArm64", libs.androidx.room3.compiler)
    }
    add("kspJvm", libs.androidx.room3.compiler)
    // Add any other platform target you use in your project, for example kspDesktop
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

/**
 * The Material Design conformance audit, as a build task.
 *
 * `docs/scripts/m3-audit.sh --check` counts what the phases in
 * docs/material-design-conformance.md drove to zero -- hardcoded colours, dp literals in
 * spacing positions, bare `.clickable`, title case, untriaged `contentDescription = null`
 * -- and exits 1 when one of them has come back. It also holds two floors, for the
 * adaptive and navigation work, which regress by being *removed*.
 *
 * Wired into `check` rather than left as a script somebody remembers to run: the whole
 * point of a budget is that it is enforced at the moment the number moves, and a number
 * that is only checked when a person thinks to look is a number that drifts.
 *
 * It reads the source tree with grep and needs no gradle, no android SDK and no
 * submodules, so it is also the one part of this build that a bare CI runner can do.
 */
val m3Audit = tasks.register("m3Audit") {
    group = "verification"
    description = "Checks the Material Design conformance budgets in docs/material-design-conformance.md."

    val script = rootProject.layout.projectDirectory.file("docs/scripts/m3-audit.sh").asFile
    val uiSources = rootProject.layout.projectDirectory
        .dir("composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui")
    val projectDirectory = rootProject.layout.projectDirectory.asFile

    inputs.file(script).withPropertyName("auditScript")
    inputs.dir(uiSources).withPropertyName("uiSources")
    // No outputs, so this would run every time. A marker file is what makes it
    // up-to-date-able, and it is the only thing the task writes.
    val marker = layout.buildDirectory.file("m3-audit/passed.txt")
    outputs.file(marker)

    doLast {
        // Windows has no bash unless somebody installed one. Skipping loudly beats
        // failing a build for a reason that has nothing to do with the change under it;
        // the CI runner and every developer machine here are unix.
        val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() }
        if (bash == null) {
            logger.warn("m3Audit: no bash found, skipping. Run docs/scripts/m3-audit.sh --check by hand.")
            marker.get().asFile.apply { parentFile.mkdirs() }.writeText("skipped: no bash\n")
            return@doLast
        }

        val result = providers.exec {
            commandLine(bash, script.absolutePath, "--check")
            workingDir = projectDirectory
            isIgnoreExitValue = true
        }
        val text = result.standardOutput.asText.get()
        val exit = result.result.get().exitValue
        logger.lifecycle(text)
        if (exit != 0) {
            throw GradleException(
                "Material Design conformance budgets exceeded. See the report above and " +
                    "docs/material-design-conformance.md."
            )
        }
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    }
}

tasks.named("check") { dependsOn(m3Audit) }

// The group Lightning wallet's regtest run (testing/regtest/README.md) runs only when DEDUN_REGTEST=1.
// An environment variable is not a task input, so without this a skipped run would leave jvmTest up to
// date, and asking for the run afterwards would replay the skip.
tasks.withType<Test>().configureEach {
    inputs.property("dedunRegtest", providers.environmentVariable("DEDUN_REGTEST").orElse(""))
}

compose.desktop {
    application {
        mainClass = "com.machankura.dedun.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.machankura.dedun.desktop"
            packageVersion = "1.0.0"
        }
    }
}
