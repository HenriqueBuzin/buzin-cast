plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties
import java.security.MessageDigest

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

val allAbis = providers.gradleProperty("buzinAbis").orNull?.split(",")
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

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
        minSdk = 25
        targetSdk = 36
        versionCode = 20
        versionName = "0.5.7"

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
        }
    }

    buildTypes {
        debug {
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
    }
    sourceSets.getByName("debug").assets.srcDir("../dlna-domain/src/test/resources")
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
    implementation(project(":dlna-domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore.prefs)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose.material3)
    implementation(libs.media3.transformer)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.oboe)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
