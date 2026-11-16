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
androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(sharedResources, SharedResourcesCopy::outputDir)
}
android {
    namespace = "io.buzincast.legacy"
    compileSdk = 37
    defaultConfig {
        applicationId = if (noDownload) "com.buzincast.tv.deat.legacy.preview" else "com.buzincast.tv.legacy.preview"
        minSdk = 17
        targetSdk = 37
        versionCode = 1
        versionName = "0.6.7-legacy-preview" + if (noDownload) "-nodownload" else ""
        multiDexEnabled = true
        ndk { abiFilters += setOf("armeabi-v7a", "x86") }
        buildConfigField("boolean", "VIDEO_DOWNLOAD_ENABLED", (!noDownload).toString())
        buildConfigField("boolean", "RECORDING_ENABLED", (!noDownload).toString())
    }
    buildFeatures { buildConfig = true }
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
tasks.named("preBuild") { dependsOn(sharedResources) }
dependencies {
    implementation(project(":fireos3-runtime"))
    implementation("androidx.multidex:multidex:2.0.1")
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit4)
}
