plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // XmlPullParser API: provided by Android at runtime; kxml2 backs JVM tests only.
    compileOnly(libs.kxml)

    testImplementation(libs.junit4)
    testImplementation(libs.kxml)
}

tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.jacocoTestReport { reports { xml.required.set(true); html.required.set(true) } }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
jacoco { toolVersion = libs.versions.jacoco.get() }
