plugins {
    id("com.android.application") version "9.1.1" apply false
    // AGP 9.0 has built-in Kotlin (KGP 2.2.10); do NOT apply kotlin.android. The Compose
    // compiler plugin version must match the bundled Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
