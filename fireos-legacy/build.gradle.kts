plugins {
    alias(libs.plugins.android.application)
}

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.security.MessageDigest

// JmDNS 3.6.5 uses StandardCharsets (Android API 19). Keep the maintained
// library and substitute its constant lookups with the equivalent API 1 call.
// No protocol, certificate or cryptography behavior is changed.
abstract class LegacyApi17Charsets : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) = classData.className.startsWith("javax.jmdns.")
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        if (opcode == Opcodes.GETSTATIC && owner == "java/nio/charset/StandardCharsets" && name == "UTF_8") {
                            super.visitLdcInsn("UTF-8")
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/charset/Charset", "forName", "(Ljava/lang/String;)Ljava/nio/charset/Charset;", false)
                        } else super.visitFieldInsn(opcode, owner, name, descriptor)
                    }
                }
            }
        }
}

androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(LegacyApi17Charsets::class.java, InstrumentationScope.ALL) {}
}

val noDownload = providers.gradleProperty("buzinNoDownload").orNull == "true"
if (noDownload) layout.buildDirectory.set(file("build-nodownload"))
abstract class SharedResourcesCopy : Sync() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty
}
val sharedResources = tasks.register<SharedResourcesCopy>("syncSharedResources") {
    from(rootProject.file("app/src/main/res")) {
        include("values*/strings.xml", "values*/*_strings.xml", "mipmap*/buzin_launcher.png",
            "drawable-xhdpi/buzin_tv_banner.png")
        // Android 4.2 resolves Hebrew resources under the historical Java code
        // "iw". Keep translations sourced from the modern app, changing only
        // their generated qualifier so the actual device locale can find them.
        eachFile { if (path.startsWith("values-he/")) path = "values-iw/" + path.removePrefix("values-he/") }
    }
    outputDir.set(layout.buildDirectory.dir("generated/shared-res"))
    into(outputDir)
}
// Share transports and photo controls with the modern frontend. Only platform
// lifecycle/resource bindings and the bounded API17 photo loader differ.
val sharedTransport = tasks.register<SharedResourcesCopy>("syncSharedTransport") {
    from(rootProject.file("app/src/main/kotlin")) {
        include("io/github/jqssun/airplay/discovery/FCastReceiver.kt", "io/github/jqssun/airplay/discovery/DlnaReceiver.kt",
            "io/leancast/app/ControlServer.kt", "io/leancast/app/SubscriptionRegistry.kt", "io/leancast/app/SsdpAgent.kt",
            "io/github/jqssun/airplay/media/PhotoActivity.kt")
        include("io/github/jqssun/airplay/media/ExternalSubtitles.kt")
        include("io/github/jqssun/airplay/media/ConnectionApproval.kt")
        include("io/github/jqssun/airplay/discovery/CastV2Receiver.kt")
        filesMatching("**/CastV2Receiver.kt") {
            var replacingTls = false
            filter { line: String -> when {
                line == "    private fun tls(): SSLContext {" -> {
                    replacingTls = true
                    "    private fun tls(): io.buzincast.fireos3probe.LegacyNativeTls = io.buzincast.fireos3probe.LegacyTlsIdentity.nativeContext(context)"
                }
                replacingTls && line == "    @Synchronized override fun start() {" -> { replacingTls = false; line }
                replacingTls -> ""
                else -> line
            } }
        }
        filesMatching("**/PhotoActivity.kt") {
            filter { line: String -> when {
                line == "import androidx.lifecycle.lifecycleScope" || line == "import androidx.activity.ComponentActivity" -> ""
                line == "import io.github.jqssun.airplay.R" -> "import io.buzincast.legacy.R\nimport io.buzincast.legacy.LegacyPhotoActivityBase\nimport io.buzincast.legacy.LegacyPhotoLoader"
                line == "class PhotoActivity : ComponentActivity() {" -> "class PhotoActivity : LegacyPhotoActivityBase() {"
                line.contains("val loaded = withContext(Dispatchers.IO) { loadPhoto(url) }") ->
                    line.replace("loadPhoto(url)", "LegacyPhotoLoader.load(this@PhotoActivity, url)")
                line.contains("private class PhotoLoadException") -> line.replace("private class PhotoLoadException", "class PhotoLoadException")
                else -> line
            } }
        }
    }
    doFirst {
        val cast = rootProject.file("app/src/main/kotlin/io/github/jqssun/airplay/discovery/CastV2Receiver.kt").readText().replace("\r\n", "\n")
        listOf("    private fun tls(): SSLContext {", "    @Synchronized override fun start() {").forEach {
            check(cast.split(it).size == 2) { "Review the API17 Cast TLS binding: upstream source changed" }
        }
        val tlsSection = cast.substring(cast.indexOf("    private fun tls(): SSLContext {"), cast.indexOf("    @Synchronized override fun start() {"))
        val tlsHash = MessageDigest.getInstance("SHA-256").digest(tlsSection.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        check(tlsHash == "0c336e18a5d5dedcd355e89fef5d0ca11d4ad2fc81a66ef7cac926d0e8de7257") {
            "Review the API17 Cast TLS binding: upstream identity policy changed"
        }
        val source = rootProject.file("app/src/main/kotlin/io/github/jqssun/airplay/media/PhotoActivity.kt").readText()
        listOf("import androidx.lifecycle.lifecycleScope", "class PhotoActivity : ComponentActivity() {",
            "val loaded = withContext(Dispatchers.IO) { loadPhoto(url) }", "private class PhotoLoadException").forEach {
            check(source.split(it).size == 2) { "Review the API17 photo platform binding: upstream source changed" }
        }
    }
    outputDir.set(layout.buildDirectory.dir("generated/shared-transport"))
    into(outputDir)
}
androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(sharedResources, SharedResourcesCopy::outputDir)
    variant.sources.java?.addGeneratedSourceDirectory(sharedTransport, SharedResourcesCopy::outputDir)
}
android {
    namespace = "io.buzincast.legacy"
    compileSdk = 37
    defaultConfig {
        applicationId = if (noDownload) "com.buzincast.tv.deat.legacy.preview" else "com.buzincast.tv.legacy.preview"
        minSdk = 17
        targetSdk = 37
        testInstrumentationRunner = "io.buzincast.legacy.LegacyGestureChecks"
        versionCode = 1
        versionName = "0.6.7-legacy-preview" + if (noDownload) "-nodownload" else ""
        multiDexEnabled = true
        // The synthetic provider can start in the test APK's own process,
        // before the instrumentation runner installs secondary dex files.
        multiDexKeepProguard = file("main-dex-rules.pro")
        ndk { abiFilters += setOf("armeabi-v7a", "x86") }
        buildConfigField("boolean", "VIDEO_DOWNLOAD_ENABLED", (!noDownload).toString())
        buildConfigField("boolean", "RECORDING_ENABLED", (!noDownload).toString())
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("fireos3-probe/src/main/assets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
tasks.named("preBuild") { dependsOn(sharedResources, sharedTransport) }
dependencies {
    implementation(project(":fireos3-runtime"))
    implementation(project(":dlna-domain"))
    implementation(project(":receiver-core"))
    implementation(libs.kotlinx.coroutines)
    implementation("androidx.core:core:1.8.0")
    implementation("org.jmdns:jmdns:3.6.5")
    implementation("androidx.multidex:multidex:2.0.1")
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit4)
    androidTestImplementation("org.jmdns:jmdns:3.6.5")
}
