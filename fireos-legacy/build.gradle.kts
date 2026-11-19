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
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties

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

val storeEdition = providers.gradleProperty("buzinLegacyStore").orNull == "true"
val regionalAssets = providers.gradleProperty("buzinRegionalAppstoreAssets").orNull?.let(::file)
val noDownload = regionalAssets != null || providers.gradleProperty("buzinNoDownload").orNull == "true"
val storeVersionCode = providers.gradleProperty("buzinLegacyVersionCode").orNull?.toIntOrNull()
val storeVersionName = providers.gradleProperty("buzinLegacyVersionName").orNull
val signingProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val storeKey = if (noDownload) regionalAssets?.resolve("AppstoreAuthenticationKey.pem")
    else rootProject.file("app/src/main/assets/AppstoreAuthenticationKey.pem")
if (storeEdition) {
    require(storeVersionCode != null && storeVersionCode > 0 && !storeVersionName.isNullOrBlank()) {
        "A store candidate requires explicit buzinLegacyVersionCode and buzinLegacyVersionName"
    }
    require(storeKey?.isFile == true) { "Provide the matching app public key; DE/AT requires buzinRegionalAppstoreAssets" }
    val publicKeyText = storeKey!!.readText(Charsets.US_ASCII).trim()
    require(publicKeyText.matches(Regex("-----BEGIN PUBLIC KEY-----\\s+[A-Za-z0-9+/=\\s]+\\s+-----END PUBLIC KEY-----"))) {
        "The Appstore asset must contain only a public key"
    }
    val encodedKey = Base64.getMimeDecoder().decode(publicKeyText.removePrefix("-----BEGIN PUBLIC KEY-----").removeSuffix("-----END PUBLIC KEY-----"))
    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encodedKey))
    if (noDownload) {
        val generalKeyText = rootProject.file("app/src/main/assets/AppstoreAuthenticationKey.pem").readText(Charsets.US_ASCII).trim()
        val generalEncodedKey = Base64.getMimeDecoder().decode(generalKeyText.removePrefix("-----BEGIN PUBLIC KEY-----").removeSuffix("-----END PUBLIC KEY-----"))
        require(!encodedKey.contentEquals(generalEncodedKey)) {
            "The separate DE/AT app must not reuse the general app public key"
        }
    }
    require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !signingProperties.getProperty(it).isNullOrBlank() }) {
        "A store candidate requires the release signing configuration in local.properties"
    }
}
if (storeEdition || noDownload) layout.buildDirectory.set(file("build" + (if (storeEdition) "-store" else "") + (if (noDownload) "-nodownload" else "")))
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
        include("io/github/jqssun/airplay/media/MirrorRelay.kt")
        filesMatching("**/MirrorRelay.kt") {
            filter { line: String -> when {
                line == "import io.github.jqssun.airplay.R" -> "import io.buzincast.legacy.R"
                line == "    private val queue = ArrayBlockingQueue<MirrorPacket>(120)" ->
                    "    private val queue = io.buzincast.legacy.LegacyRelayQueue(120)"
                line == "    private val alive = AtomicBoolean(false)" ->
                    line + "\n    fun sessionActive(): Boolean = alive.get()"
                line.trimStart().startsWith("val channel = SecureMirrorChannel(") ->
                    line.dropLast(1) + ", io.buzincast.legacy.LegacyMirrorCipher.INSTANCE)"
                else -> line
            } }
        }
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
        val relay = rootProject.file("app/src/main/kotlin/io/github/jqssun/airplay/media/MirrorRelay.kt").readText()
        check(relay.lineSequence().count { it.trimStart().startsWith("val channel = SecureMirrorChannel(") && it.endsWith("))") } == 2 &&
            relay.contains("    private val queue = ArrayBlockingQueue<MirrorPacket>(120)") &&
            relay.split("    private val alive = AtomicBoolean(false)").size == 2 &&
            relay.contains("import io.github.jqssun.airplay.R")) { "Review API17 relay crypto/resource/queue bindings: shared source changed" }
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
if (storeEdition) {
    val storeSources = tasks.register<SharedResourcesCopy>("syncAppstoreSource") {
        from(rootProject.file("app/src/main/kotlin")) { include("io/github/jqssun/airplay/billing/AppstoreBilling.kt") }
        outputDir.set(layout.buildDirectory.dir("generated/appstore-source")); into(outputDir)
    }
    val storeAssets = tasks.register<SharedResourcesCopy>("syncAppstoreKey") {
        from(storeKey); outputDir.set(layout.buildDirectory.dir("generated/appstore-assets")); into(outputDir)
    }
    androidComponents.onVariants { variant ->
        if (variant.buildType == "release") {
            variant.sources.java?.addGeneratedSourceDirectory(storeSources, SharedResourcesCopy::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(storeAssets, SharedResourcesCopy::outputDir)
        }
    }
}
android {
    namespace = "io.buzincast.legacy"
    compileSdk = 37
    defaultConfig {
        applicationId = (if (noDownload) "com.buzincast.tv.deat" else "com.buzincast.tv") + if (storeEdition) "" else ".legacy.preview"
        minSdk = 17
        targetSdk = 37
        testInstrumentationRunner = "io.buzincast.legacy.LegacyGestureChecks"
        versionCode = if (storeEdition) storeVersionCode!! else 1
        versionName = if (storeEdition) storeVersionName!! else "0.6.7-legacy-preview" + if (noDownload) "-nodownload" else ""
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
    if (storeEdition) {
        sourceSets.getByName("release").apply {
            manifest.srcFile("src/store/AndroidManifest.xml")
            java.srcDir("src/store/java")
        }
        signingConfigs.create("storeRelease") {
            storeFile = rootProject.file("app").resolve(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
            enableV1Signing = true // Android 4.2 cannot verify APK Signature Scheme v2.
            enableV2Signing = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        debug {
            if (storeEdition) {
                applicationIdSuffix = ".legacy.preview"
                versionNameSuffix = "-preview"
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (storeEdition) signingConfig = signingConfigs.getByName("storeRelease")
        }
    }
}
tasks.named("preBuild") { dependsOn(sharedResources, sharedTransport) }
dependencies {
    if (storeEdition) add("releaseImplementation", "com.amazon.device:amazon-appstore-sdk:3.0.9")
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
