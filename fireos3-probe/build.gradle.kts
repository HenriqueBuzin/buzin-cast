plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.buzincast.fireos3probe"
    compileSdk = 37
    ndkVersion = "23.2.8568313"

    defaultConfig {
        applicationId = "io.buzincast.fireos3probe"
        minSdk = 17
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += setOf("armeabi-v7a", "x86")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) {
        dependsOn(":app:applyUxplayPatches", ":app:applyOpensslCmakePatches")
    }
}

dependencies {
    testImplementation(libs.junit4)
}
