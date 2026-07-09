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
        versionCode = 20201
        versionName = "2.2.1"

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
        // Release signing from environment (CI injects these from repo secrets; see
        // .github/workflows/release.yml). Absent locally → release falls back to the debug key.
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
            // Use the real release key when CI provides ORCHESTRA_KEYSTORE; otherwise debug-sign so
            // local `assembleRelease` and unsigned dev builds keep working.
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
    // ShadowHook (BSD) — self-installing inline hook engine for the DID hook (see NativeBridge).
    // Ships prebuilt libshadowhook.so + a CMake/prefab package; consumed from
    // app/src/main/cpp/CMakeLists.txt via find_package(shadowhook REQUIRED CONFIG).
    // 1.0.10, NOT 1.1.1: 1.1.1 inline-hooks the linker during init and always fails with
    // errno 12 (INIT_LINKER) in the Bluetooth system process (bytedance/android-inline-hook#91).
    implementation("com.bytedance.android:shadowhook:1.0.10")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    // M3 Expressive (public MaterialExpressiveTheme/ExperimentalMaterial3ExpressiveApi) ships in
    // material3 1.5.0-alpha; it requires Compose foundation/ui 1.12.0-alpha03.
    val compose = "1.12.0-beta02"
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.ui:ui-graphics:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.5.0-alpha20")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json impl for unit tests: the android.jar used to compile main sources ships a
    // body-stripped org.json that throws "not mocked" at test runtime (same mechanism as other
    // android.* stubs). This real implementation shadows it on the unit-test runtime classpath so
    // DeviceDef's JSONObject-based parsing is exercisable without Robolectric.
    testImplementation("org.json:json:20240303")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
