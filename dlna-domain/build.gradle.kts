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
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            listOf("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS").forEach { metric ->
                limit { counter = metric; minimum = "1.00".toBigDecimal() }
            }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
jacoco { toolVersion = libs.versions.jacoco.get() }
