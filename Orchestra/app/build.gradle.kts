import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.thelok1s.orchestra"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.thelok1s.orchestra"
        minSdk = 31
        targetSdk = 37
        // versionCode encodes the semver as MMmmpp (1.0.0 -> 1_00_00). Must increase every release.
        versionCode = 10000
        versionName = "1.0.0"
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

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    // M3 Expressive (public MaterialExpressiveTheme/ExperimentalMaterial3ExpressiveApi) ships in
    // material3 1.5.0-alpha; it requires Compose foundation/ui 1.12.0-alpha03.
    val compose = "1.12.0-alpha03"
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.ui:ui-graphics:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.5.0-alpha20")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
}
