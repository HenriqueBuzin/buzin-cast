plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    jacoco
}

import java.util.Properties
import java.security.MessageDigest

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

val legacyEdition = providers.gradleProperty("buzinLegacy").orNull == "true"
val coverageEnabled = providers.gradleProperty("buzinCoverage").orNull == "true"
val editionBuildDirectory = "build" + (if (legacyEdition) "-legacy" else "") + (if (coverageEnabled) "-coverage" else "")
layout.buildDirectory.set(file(editionBuildDirectory))

val allAbis = providers.gradleProperty("buzinAbis").orNull?.split(",")
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    if (localProps.containsKey("storeFile")) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.buzincast.tv"
        minSdk = if (legacyEdition) 22 else 25
        targetSdk = 37
        versionCode = if (legacyEdition) 25 else 26
        versionName = if (legacyEdition) "0.6.2-compat" else "0.6.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    buildTypes {
        debug {
            enableAndroidTestCoverage = coverageEnabled
            enableUnitTestCoverage = coverageEnabled
            externalNativeBuild { cmake { arguments += "-DBUZIN_COVERAGE=${if (coverageEnabled) "ON" else "OFF"}" } }
            ndk { abiFilters += allAbis }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            ndk { abiFilters += allAbis }
        }
        // debuggable build with HWASan (arm64) + UBSan in native code
        create("sanitize") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake { arguments += "-DSANITIZE=ON" }
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
    }
    sourceSets.getByName("debug").assets.directories.add("../dlna-domain/src/test/resources")
}

tasks.register("applyUxplayPatches") {
    doLast {
        fun git(vararg args: String): String {
            val proc = ProcessBuilder("git", "-C", "$projectDir/src/main/cpp/third_party/UxPlay", *args)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        val patches = file("src/main/cpp/patches/UxPlay").listFiles { f -> f.extension == "patch" }!!.sorted()
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val patchHash = digest(patches.flatMap { it.readBytes().toList() }.toByteArray())
        val marker = file("src/main/cpp/third_party/UxPlay/.buzin-patches.properties")
        val touched = patches.flatMap { git("apply", "--numstat", it.absolutePath).trim().lines() }
            .map { it.substringAfterLast("\t") }.distinct()
        if (marker.exists()) {
            val recorded = Properties().apply { marker.inputStream().use(::load) }
            check(recorded.getProperty("patchSet") == patchHash) { "UxPlay patch set changed; use a fresh submodule checkout." }
            check(touched.all { name -> recorded.getProperty(name) == digest(file("src/main/cpp/third_party/UxPlay/$name").readBytes()) }) {
                "Patched UxPlay files were edited. Review the local changes before rebuilding."
            }
            return@doLast
        }
        patches.forEach { patch ->
            // Also supports source distributions without Git metadata. Never reset local edits.
            val applied = ProcessBuilder("git", "-C", "$projectDir/src/main/cpp/third_party/UxPlay",
                "apply", "--reverse", "--check", "--unidiff-zero", patch.absolutePath)
                .redirectErrorStream(true).start().let { proc ->
                    proc.inputStream.bufferedReader().readText()
                    proc.waitFor() == 0
                }
            if (!applied) git("apply", "--unidiff-zero", patch.absolutePath)
        }
        val recorded = Properties().apply {
            setProperty("patchSet", patchHash)
            touched.forEach { name -> setProperty(name, digest(file("src/main/cpp/third_party/UxPlay/$name").readBytes())) }
        }
        marker.outputStream().use { recorded.store(it, "Buzin Cast applied UxPlay patches") }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    implementation(project(":receiver-core"))
    implementation(project(":dlna-domain"))
    implementation(if (legacyEdition) "androidx.core:core-ktx:${libs.versions.legacy.core.get()}" else libs.androidx.core.ktx.get())
    implementation(if (legacyEdition) "androidx.activity:activity-compose:${libs.versions.legacy.activity.get()}" else libs.androidx.activity.compose.get())
    implementation(if (legacyEdition) "androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.legacy.lifecycle.get()}" else libs.androidx.lifecycle.runtime.get())
    implementation(if (legacyEdition) "androidx.lifecycle:lifecycle-service:${libs.versions.legacy.lifecycle.get()}" else libs.androidx.lifecycle.service.get())
    implementation(if (legacyEdition) "androidx.lifecycle:lifecycle-viewmodel-compose:${libs.versions.legacy.lifecycle.get()}" else libs.androidx.lifecycle.viewmodel.get())
    implementation(if (legacyEdition) "androidx.media:media:${libs.versions.legacy.media.get()}" else libs.androidx.media.get())
    implementation(if (legacyEdition) "androidx.media3:media3-exoplayer:${libs.versions.legacy.media3.get()}" else libs.media3.exoplayer.asProvider().get())
    implementation(if (legacyEdition) "androidx.media3:media3-exoplayer-hls:${libs.versions.legacy.media3.get()}" else libs.media3.exoplayer.hls.get())
    implementation(if (legacyEdition) "androidx.media3:media3-transformer:${libs.versions.legacy.media3.get()}" else libs.media3.transformer.get())
    implementation(libs.kotlinx.coroutines)
    implementation(libs.oboe)

    implementation(platform(if (legacyEdition) "androidx.compose:compose-bom:${libs.versions.legacy.compose.bom.get()}" else libs.compose.bom.get()))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

// Includes every production class in the three source modules. Debug fixtures,
// generated Hilt/KSP code and external dependencies are outside this inventory.
jacoco { toolVersion = libs.versions.jacoco.get() }

if (legacyEdition) {
    // Align complete AndroidX families so a transitive dependency cannot silently
    // raise the legacy APK's minimum SDK. No overrideLibrary bypass is used.
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "androidx.core" -> if (requested.name in setOf("core", "core-ktx")) useVersion(libs.versions.legacy.core.get())
                "androidx.activity" -> useVersion(libs.versions.legacy.activity.get())
                "androidx.lifecycle" -> useVersion(libs.versions.legacy.lifecycle.get())
                "androidx.media3" -> useVersion(libs.versions.legacy.media3.get())
            }
        }
    }
}

val coverageFolder = if (legacyEdition) "emulator-checks-legacy" else "emulator-checks"
val productionClasses = files(
    fileTree(layout.buildDirectory.dir("intermediates/classes/debug/transformDebugClassesWithAsm/dirs")) {
        exclude("**/DebugMediaReceiver*", "**/DebugNativeBridge*", "**/DebugCoverage*", "**/DebugTvPreviewActivity*", "**/DebugTvPlaybackActivity*", "**/DebugNativeFixture*")
        exclude("**/Hilt_*", "**/*_Hilt*", "**/*_Factory*", "**/*_MembersInjector*", "**/hilt_aggregated_deps/**", "dagger/**")
    },
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")),
    project(":receiver-core").layout.buildDirectory.dir("classes/kotlin/main"),
    project(":dlna-domain").layout.buildDirectory.dir("classes/kotlin/main")
)
tasks.register<JacocoReport>("fullCoverageReport") {
    dependsOn(":receiver-core:test", ":dlna-domain:test")
    doFirst {
        check(layout.buildDirectory.file("intermediates/classes/debug/transformDebugClassesWithAsm/dirs/io/github/jqssun/airplay/ui/MainScreenKt.class").get().asFile.isFile) { "Build the coverage APK before reporting" }
        check(fileTree(rootProject.layout.buildDirectory.dir(coverageFolder)) { include("**/*.ec") }.files.isNotEmpty()) { "Android execution coverage is missing" }
    }
    executionData(fileTree(rootProject.layout.buildDirectory.dir(coverageFolder)) { include("**/*.ec", "**/*.exec") },
        project(":receiver-core").layout.buildDirectory.file("jacoco/test.exec"),
        project(":dlna-domain").layout.buildDirectory.file("jacoco/test.exec"))
    classDirectories.setFrom(productionClasses)
    sourceDirectories.setFrom(files("src/main/kotlin", "../receiver-core/src/main/kotlin", "../dlna-domain/src/main/kotlin"))
    reports { xml.required.set(true); html.required.set(true) }
}
