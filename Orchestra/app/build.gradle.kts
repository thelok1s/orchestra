import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.thelok1s.orchestra"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.thelok1s.orchestra"
        minSdk = 31
        targetSdk = 37
        versionCode = 30000
        versionName = "3.0.0"

        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ksPath = System.getenv("ORCHESTRA_KEYSTORE")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ORCHESTRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ORCHESTRA_KEY_ALIAS")
                keyPassword = System.getenv("ORCHESTRA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                if (System.getenv("ORCHESTRA_KEYSTORE") != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    // Extract native libs to the app's nativeLibraryDir on install, so the SystemUI/Bluetooth
    // hook processes can System.load() them by absolute path (a foreign process can't load an
    // uncompressed lib straight out of our APK). Needed for the DID hook (loaded into the BT stack).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        // Keep the modern-libxposed discovery files (module.prop/java_init.list/scope.list) in the
        // APK — the merge rule stops the default resource de-dup from dropping them.
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.register<Exec>("syncManifests") {
    workingDir = rootDir.parentFile
    commandLine("bash", "${rootDir}/sync-manifests.sh")
    isIgnoreExitValue = true // don't fail builds when the manifest repo isn't checked out
}
tasks.named("preBuild") { dependsOn("syncManifests") }

dependencies {
    // Xposed API — compileOnly: LSPosed provides it at runtime, must NOT be bundled.
    compileOnly("de.robv.android.xposed:api:82")
    // Modern libxposed API — compileOnly: the framework provides it at runtime (must NOT be bundled).
    // Enables the ModernModuleEntry path for frameworks that drop the legacy de.robv bridge.
    compileOnly("io.github.libxposed:api:102.0.0")
    // ShadowHook (BSD) — self-installing inline hook engine for the DID hook (see NativeBridge).
    implementation("com.bytedance.android:shadowhook:2.0.1")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    val compose = "1.12.0-beta02"
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.ui:ui-graphics:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.5.0-alpha23")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
