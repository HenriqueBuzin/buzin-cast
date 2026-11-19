plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.buzincast.fireos3runtime"
    compileSdk = 37
    ndkVersion = "23.2.8568313"
    defaultConfig {
        minSdk = 17
        multiDexEnabled = true
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += setOf("armeabi-v7a", "x86") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = libs.versions.cmake.get() }
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(":app:applyUxplayPatches", ":app:applyOpensslCmakePatches")
}
dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation("org.jmdns:jmdns:3.6.5")
    // Media3 1.2.1 declares API16; 1.3.x raises that floor to API19.
    // Never override a newer artifact's manifest to force installation.
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
    implementation("androidx.media3:media3-muxer:1.2.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.7")
    testImplementation(libs.junit4)
}
