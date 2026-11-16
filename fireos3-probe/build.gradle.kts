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
abstract class Api17Charsets : AsmClassVisitorFactory<InstrumentationParameters.None> {
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
    variant.instrumentation.transformClassesWith(Api17Charsets::class.java, InstrumentationScope.ALL) {}
}

android {
    namespace = "io.buzincast.fireos3probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.buzincast.fireos3probe"
        minSdk = 17
        targetSdk = 37
        versionCode = 6
        versionName = "0.6"
        multiDexEnabled = true
        ndk { abiFilters += setOf("armeabi-v7a", "x86") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}
dependencies {
    implementation(project(":fireos3-runtime"))
    coreLibraryDesugaring(libs.desugar)
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("org.jmdns:jmdns:3.6.5")
    testImplementation(libs.junit4)
}
